// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Source files of ONE repository read from git at the recording's exact {@code sourceCommit}
 * (VIEWER.md §26.3) instead of the working tree. A recording is only line-accurate against the
 * commit it was made at; the checked-out branch may have drifted (methods moved, files migrated
 * to other modules or packages) — so when the manifest's commit resolves in a {@code --repo},
 * every source lookup for that repo goes through the commit's tree:
 *
 * <ul>
 *   <li><b>Discovery</b>: {@code git ls-tree -r --name-only -z <commit>} lists every path at the
 *       commit; the {@code .java} ones under a {@code src/<set>/java} segment are indexed by their
 *       package-relative key ({@code com/foo/Bar.java}) — the same key {@link SourcePath}'s probing
 *       uses — so classes whose source root does not even exist in the current checkout still
 *       resolve.</li>
 *   <li><b>Content</b>: {@code git show <commit>:<relpath>}, cached per path (hit or miss).</li>
 *   <li><b>Virtual paths</b>: a resolved file is addressed as {@code <repo>/<relpath>} even when
 *       that path is absent from the working tree; {@link #relPathOf} recognises such paths so
 *       content and coverage keys route back through git.</li>
 * </ul>
 *
 * <p>Everything is best-effort: no git binary, a non-repo dir, or an unresolvable commit yields
 * {@link Optional#empty()} from {@link #open} and the caller falls back to the working tree.
 */
public final class GitSource {

    /** Only a plain abbreviated/full hex object name is ever passed to git (argument hygiene). */
    private static final Pattern COMMIT_HASH = Pattern.compile("[0-9a-fA-F]{7,40}");

    private static final Pattern SRC_ROOT = Pattern.compile("(.*/)?src/[^/]+/java/");

    private final Path repo;
    private final String commit;
    private final boolean headMatches;
    // Package-relative key ("com/foo/Bar.java") -> commit-tree relpath; first (ls-tree order) wins.
    private final Map<String, String> byKey;
    // Normalised absolute (possibly virtual) path -> commit-tree relpath.
    private final Map<Path, String> byAbsPath;
    // Commit-tree relpath -> its package-relative key (for coverage/gutter lookups).
    private final Map<String, String> keyByRelPath;
    private final Map<String, Optional<List<String>>> contentCache = new HashMap<>();

    private GitSource(final Path repo, final String commit, final boolean headMatches,
            final Map<String, String> byKey, final Map<Path, String> byAbsPath,
            final Map<String, String> keyByRelPath) {
        this.repo = repo;
        this.commit = commit;
        this.headMatches = headMatches;
        this.byKey = byKey;
        this.byAbsPath = byAbsPath;
        this.keyByRelPath = keyByRelPath;
    }

    /**
     * Opens {@code repo}'s tree at {@code commit}: verifies the commit resolves, lists its
     * {@code .java} files and indexes those under a {@code src/<set>/java} root. Empty when git
     * is unavailable, {@code repo} is not a git work tree, the hash is malformed, the commit is
     * absent (shallow clone, gc), or the listing fails — callers then use the working tree.
     *
     * @param repo the repository root (a {@code --repo} dir)
     * @param commit the manifest's {@code sourceCommit}
     * @return the commit-pinned source view, or empty for any failure
     */
    public static Optional<GitSource> open(final Path repo, final String commit) {
        if (repo == null || commit == null || !COMMIT_HASH.matcher(commit).matches()
                || !Files.isDirectory(repo)) {
            return Optional.empty();
        }
        Path abs = repo.toAbsolutePath().normalize();
        if (run(abs, "rev-parse", "--verify", "--quiet", commit + "^{commit}") == null) {
            return Optional.empty();
        }
        byte[] head = run(abs, "rev-parse", "HEAD");
        boolean headMatches = head != null
                && new String(head, StandardCharsets.UTF_8).trim().startsWith(commit.toLowerCase());
        byte[] listing = run(abs, "ls-tree", "-r", "--name-only", "-z", commit);
        if (listing == null) {
            return Optional.empty();
        }
        Map<String, String> byKey = new LinkedHashMap<>();
        Map<Path, String> byAbsPath = new LinkedHashMap<>(); // insertion order = ls-tree order
        Map<String, String> keyByRelPath = new HashMap<>();
        for (String rel : new String(listing, StandardCharsets.UTF_8).split("\0")) {
            if (!rel.endsWith(".java")) {
                continue;
            }
            var m = SRC_ROOT.matcher(rel);
            if (!m.lookingAt()) {
                continue; // not under a src/<set>/java root — never probed by SourcePath either
            }
            String key = rel.substring(m.end());
            byKey.putIfAbsent(key, rel);
            byAbsPath.put(abs.resolve(rel).normalize(), rel);
            keyByRelPath.put(rel, key);
        }
        return Optional.of(new GitSource(abs, commit, headMatches, byKey, byAbsPath, keyByRelPath));
    }

    /** The repository root this view reads from. */
    public Path repo() {
        return repo;
    }

    /** The pinned commit hash. */
    public String commit() {
        return commit;
    }

    /** Whether the repo's checked-out HEAD is the pinned commit (informational — reads go via git either way). */
    public boolean headMatches() {
        return headMatches;
    }

    /** How many {@code .java} files the commit's source roots hold. */
    public int fileCount() {
        return byAbsPath.size();
    }

    /**
     * The (possibly virtual) absolute path of the file with package-relative {@code key}
     * ({@code com/foo/Bar.java}), or empty when no source root at the commit holds it.
     */
    public Optional<Path> resolve(final String key) {
        String rel = byKey.get(key);
        return rel == null ? Optional.empty() : Optional.of(repo.resolve(rel));
    }

    /** The commit-tree relpath behind a (possibly virtual) absolute path, or empty if not ours. */
    public Optional<String> relPathOf(final Path file) {
        if (file == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byAbsPath.get(file.toAbsolutePath().normalize()));
    }

    /** The package-relative key of a (possibly virtual) absolute path, or empty if not ours. */
    public Optional<String> keyOf(final Path file) {
        return relPathOf(file).map(keyByRelPath::get);
    }

    /** The file content at the commit by package-relative {@code key}; cached, hit or miss. */
    public Optional<List<String>> lines(final String key) {
        String rel = byKey.get(key);
        return rel == null ? Optional.empty() : content(rel);
    }

    /** The file content at the commit by (possibly virtual) absolute path; cached, hit or miss. */
    public Optional<List<String>> linesOfFile(final Path file) {
        return relPathOf(file).flatMap(this::content);
    }

    /**
     * Every indexed file as {@code (repo-relative path, package-relative key)} in listing order —
     * the open-file browser's §26.3 tree source, so the F dialog lists the COMMIT's files (module
     * layout included) rather than the drifted working tree's.
     */
    public List<Map.Entry<String, String>> files() {
        List<Map.Entry<String, String>> out = new ArrayList<>(keyByRelPath.size());
        for (Map.Entry<Path, String> e : byAbsPath.entrySet()) {
            out.add(Map.entry(e.getValue(), keyByRelPath.get(e.getValue())));
        }
        return out;
    }

    private Optional<List<String>> content(final String rel) {
        Optional<List<String>> cached = contentCache.get(rel);
        if (cached != null) {
            return cached;
        }
        byte[] bytes = run(repo, "show", commit + ":" + rel);
        Optional<List<String>> result = bytes == null ? Optional.empty() : Optional.of(toLines(bytes));
        contentCache.put(rel, result);
        return result;
    }

    /** Splits blob bytes into lines with {@code Files.readAllLines} semantics (no trailing empty). */
    private static List<String> toLines(final byte[] bytes) {
        String[] raw = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        List<String> lines = new ArrayList<>(raw.length);
        for (String l : raw) {
            lines.add(l.endsWith("\r") ? l.substring(0, l.length() - 1) : l);
        }
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1); // a final newline does not open an extra line
        }
        return List.copyOf(lines);
    }

    /** Runs {@code git -C <dir> <args…>}; stdout bytes on exit 0, {@code null} on any failure. */
    private static byte[] run(final Path dir, final String... args) {
        List<String> cmd = new ArrayList<>(args.length + 3);
        cmd.add("git");
        cmd.add("-C");
        cmd.add(dir.toString());
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            byte[] out;
            try (InputStream in = p.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                in.transferTo(buf);
                out = buf.toByteArray();
            }
            // Bounded wait: a hung git (dead network mount) must not block startup forever —
            // the caller treats null as "fall back to the working tree".
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? out : null;
        } catch (IOException e) {
            return null; // no git binary / not executable — caller falls back to the working tree
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
