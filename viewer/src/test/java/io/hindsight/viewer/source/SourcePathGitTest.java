// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The §26.3 commit-pinned source reading against a real temp git repository: content comes from
 * the pinned commit (not the drifted working tree), files whose source roots no longer exist in
 * the checkout still resolve (virtually), coverage keys derive from the commit tree, and an
 * unresolvable commit falls back to plain working-tree discovery. Skipped when no usable git is
 * on the PATH.
 */
final class SourcePathGitTest {

    private static final String A_V1 = "package com.x;\nclass A { // v1\n}\n";
    private static final String A_V2 = "package com.x;\nclass A { // v2 DRIFTED\n}\n";
    private static final String B_V1 = "package org.y;\nclass B { // only at the commit\n}\n";

    /** Builds the repo: commit1 holds A(v1) + B; the working tree then drifts (A→v2, B's module deleted). */
    private static String buildRepo(final Path repo) throws IOException, InterruptedException {
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "t@t");
        git(repo, "config", "user.name", "t");
        Path a = repo.resolve("moduleA/src/main/java/com/x/A.java");
        Path b = repo.resolve("moduleB/src/main/java/org/y/B.java");
        Files.createDirectories(a.getParent());
        Files.createDirectories(b.getParent());
        Files.writeString(a, A_V1);
        Files.writeString(b, B_V1);
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "v1");
        String commit = gitOut(repo, "rev-parse", "HEAD").trim();
        // Drift the checkout: A changes on a "newer branch", B's whole module disappears.
        Files.writeString(a, A_V2);
        git(repo, "add", ".");
        git(repo, "rm", "-r", "-q", "moduleB");
        git(repo, "commit", "-q", "-m", "drift");
        return commit;
    }

    @Test
    void pinnedRepoReadsContentFromTheCommitNotTheWorkingTree(@TempDir final Path dir)
            throws IOException, InterruptedException {
        assumeGit();
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo);
        String commit = buildRepo(repo);

        SourcePath sp = SourcePath.of(List.of(), List.of(repo), commit);
        assertEquals(1, sp.gitSources().size());
        GitSource gs = sp.gitSources().get(0);
        assertFalse(gs.headMatches(), "the checkout moved on after the pinned commit");
        assertEquals(2, gs.fileCount());

        // A exists in the working tree with DRIFTED content; the pinned view returns v1.
        Optional<List<String>> a = sp.lines("com.x.A", "A.java");
        assertTrue(a.isPresent());
        assertEquals("class A { // v1", a.get().get(1));
        assertFalse(String.join("\n", a.get()).contains("DRIFTED"));

        // B's module is GONE from the checkout — the §26.3 view still resolves and reads it.
        Optional<List<String>> b = sp.lines("org.y.B", "B.java");
        assertTrue(b.isPresent(), "resolves although no source root exists in the checkout");
        assertEquals("class B { // only at the commit", b.get().get(1));

        // resolveClass yields the virtual path; linesOfFile routes it back through git.
        Optional<Path> bPath = sp.resolveClass("org.y.B", "B.java");
        assertTrue(bPath.isPresent());
        assertEquals(repo.toAbsolutePath().normalize()
                .resolve("moduleB/src/main/java/org/y/B.java"), bPath.get());
        assertFalse(Files.exists(bPath.get()), "precondition: the path is virtual");
        assertEquals(b.get(), sp.linesOfFile(bPath.get()).orElseThrow());

        // Coverage keys derive from the commit tree for virtual paths.
        assertEquals(Optional.of("org/y/B.java"), sp.coverageKey(bPath.get()));
        // And A's working-tree path ALSO reads pinned content via linesOfFile (git wins).
        Path aOnDisk = repo.resolve("moduleA/src/main/java/com/x/A.java");
        assertEquals("class A { // v1", sp.linesOfFile(aOnDisk).orElseThrow().get(1));
    }

    @Test
    void unresolvableCommitFallsBackToWorkingTreeDiscovery(@TempDir final Path dir)
            throws IOException, InterruptedException {
        assumeGit();
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo);
        buildRepo(repo);

        // A malformed hash and a well-formed-but-absent hash both fall back to FS discovery.
        for (String bad : new String[] {"not-a-hash", "abcdef0123456789abcdef0123456789abcdef01"}) {
            SourcePath sp = SourcePath.of(List.of(), List.of(repo), bad);
            assertTrue(sp.gitSources().isEmpty(), bad + " must not pin");
            Optional<List<String>> a = sp.lines("com.x.A", "A.java");
            assertTrue(a.isPresent(), "working-tree discovery still resolves A");
            assertTrue(String.join("\n", a.get()).contains("DRIFTED"), "working-tree content");
            assertTrue(sp.lines("org.y.B", "B.java").isEmpty(), "B is gone from the checkout");
        }
        // Empty commit (recordings before v2.1) behaves identically.
        SourcePath sp = SourcePath.of(List.of(), List.of(repo), "");
        assertTrue(sp.gitSources().isEmpty());
    }

    @Test
    void pinnedContentWinsOnEveryAccessPathEvenWithAnExplicitSourceRoot(@TempDir final Path dir)
            throws IOException, InterruptedException {
        assumeGit();
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo);
        String commit = buildRepo(repo);
        // §26.3 priority: an explicit --source root INSIDE the pinned repo must not split the
        // truth — lines(), resolveClass()+linesOfFile() and coverageKey() all serve the pin.
        SourcePath sp = SourcePath.of(List.of(repo.resolve("moduleA/src/main/java")), List.of(repo), commit);
        assertEquals("class A { // v1", sp.lines("com.x.A", "A.java").orElseThrow().get(1));
        Path aPath = sp.resolveClass("com.x.A", "A.java").orElseThrow();
        assertEquals("class A { // v1", sp.linesOfFile(aPath).orElseThrow().get(1));
        assertEquals(Optional.of("com/x/A.java"), sp.coverageKey(aPath));
    }

    @Test
    void fileAbsentFromThePinnedCommitFallsBackToTheWorkingTree(@TempDir final Path dir)
            throws IOException, InterruptedException {
        assumeGit();
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo);
        String commit = buildRepo(repo);
        // A class recorded from a dirty checkout: its file exists on disk but not at the commit.
        Path fresh = repo.resolve("moduleA/src/main/java/com/x/Fresh.java");
        Files.writeString(fresh, "package com.x;\nclass Fresh { // uncommitted\n}\n");
        SourcePath sp = SourcePath.of(List.of(), List.of(repo), commit);
        assertEquals("class Fresh { // uncommitted",
                sp.lines("com.x.Fresh", "Fresh.java").orElseThrow().get(1),
                "the working tree serves what the pinned commit does not cover");
    }

    @Test
    void gitSourceListsFilesWithCoverageKeysForTheVirtualTree(@TempDir final Path dir)
            throws IOException, InterruptedException {
        assumeGit();
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo);
        String commit = buildRepo(repo);
        GitSource gs = GitSource.open(repo, commit).orElseThrow();
        var entries = gs.files();
        assertEquals(2, entries.size());
        assertTrue(entries.stream().anyMatch(e ->
                e.getKey().equals("moduleB/src/main/java/org/y/B.java")
                        && e.getValue().equals("org/y/B.java")));
    }

    // ---- helpers ----

    private static void assumeGit() {
        boolean ok;
        try {
            ok = new ProcessBuilder("git", "--version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            ok = false;
        }
        assumeTrue(ok, "git is required for §26.3 tests");
    }

    private static void git(final Path repo, final String... args) throws IOException, InterruptedException {
        gitOut(repo, args);
    }

    private static String gitOut(final Path repo, final String... args)
            throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 3];
        cmd[0] = "git";
        cmd[1] = "-C";
        cmd[2] = repo.toString();
        System.arraycopy(args, 0, cmd, 3, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (" + code + "): " + out);
        }
        return out;
    }
}
