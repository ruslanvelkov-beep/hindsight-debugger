// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for source-file resolution and repo auto-discovery (VIEWER.md §3). */
final class SourcePathTest {

    @Test
    void resolvesViaExplicitRoot(@TempDir final Path dir) throws IOException {
        Path root = dir.resolve("srcroot");
        Path file = root.resolve("com/ex/Foo.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "line1\nline2\nline3\n");

        SourcePath sp = SourcePath.of(List.of(root), List.of());
        Optional<List<String>> lines = sp.lines("com.ex.Foo", "Foo.java");
        assertTrue(lines.isPresent());
        assertEquals(List.of("line1", "line2", "line3"), lines.get());
    }

    @Test
    void missReturnsEmptyAndIsCached(@TempDir final Path dir) {
        SourcePath sp = SourcePath.of(List.of(dir), List.of());
        assertFalse(sp.lines("com.ex.Missing", "Missing.java").isPresent());
        // second call hits the cached miss (same result)
        assertFalse(sp.lines("com.ex.Missing", "Missing.java").isPresent());
    }

    @Test
    void defaultPackageResolvesAtRoot(@TempDir final Path dir) throws IOException {
        Path root = dir.resolve("srcroot");
        Files.createDirectories(root);
        Files.writeString(root.resolve("Top.java"), "x\n");

        SourcePath sp = SourcePath.of(List.of(root), List.of());
        assertTrue(sp.lines("Top", "Top.java").isPresent());
    }

    @Test
    void repoAutoDiscoversSrcRoots(@TempDir final Path dir) throws IOException {
        // repo/module/src/main/java/com/ex/Bar.java + a build/ dir that must be skipped
        Path mainRoot = dir.resolve("repo/module/src/main/java");
        Path bar = mainRoot.resolve("com/ex/Bar.java");
        Files.createDirectories(bar.getParent());
        Files.writeString(bar, "bar1\nbar2\n");

        Path testRoot = dir.resolve("repo/module/src/test/java");
        Path baz = testRoot.resolve("com/ex/BazTest.java");
        Files.createDirectories(baz.getParent());
        Files.writeString(baz, "baz\n");

        Path decoy = dir.resolve("repo/module/build/src/main/java/com/ex/Ghost.java");
        Files.createDirectories(decoy.getParent());
        Files.writeString(decoy, "ghost\n");

        SourcePath sp = SourcePath.of(List.of(), List.of(dir.resolve("repo")));
        assertEquals(List.of("bar1", "bar2"), sp.lines("com.ex.Bar", "Bar.java").orElseThrow());
        assertTrue(sp.lines("com.ex.BazTest", "BazTest.java").isPresent());
        // the build/ subtree is pruned, so the decoy is never discovered
        assertFalse(sp.lines("com.ex.Ghost", "Ghost.java").isPresent());
    }

    @Test
    void packagePathDerivation() {
        assertEquals("com/ex", SourcePath.packagePath("com.ex.Foo"));
        assertEquals("", SourcePath.packagePath("Top"));
        assertEquals("", SourcePath.packagePath(null));
    }

    @Test
    void moduleResolvesOwningProjectDir(@TempDir final Path dir) throws IOException {
        // repo/proj/src/main/java/a/b/C.java → the segment before src/main/java is "proj".
        Path srcRoot = dir.resolve("repo/proj/src/main/java");
        Path c = srcRoot.resolve("a/b/C.java");
        Files.createDirectories(c.getParent());
        Files.writeString(c, "package a.b;\nclass C {}\n");

        SourcePath sp = SourcePath.of(List.of(), List.of(dir.resolve("repo")));
        assertEquals(Optional.of("proj"), sp.module("a.b.C", "C.java"));
        // A class that does not resolve on any root → empty.
        assertEquals(Optional.empty(), sp.module("a.b.Missing", "Missing.java"));
    }

    @Test
    void resolveClassAndLinesOfFileRoundTrip(@TempDir final Path dir) throws IOException {
        Path srcRoot = dir.resolve("repo/proj/src/test/java");
        Path d = srcRoot.resolve("a/b/D.java");
        Files.createDirectories(d.getParent());
        Files.writeString(d, "one\ntwo\nthree\n");

        SourcePath sp = SourcePath.of(List.of(), List.of(dir.resolve("repo")));
        Optional<Path> resolved = sp.resolveClass("a.b.D", "D.java");
        assertTrue(resolved.isPresent());
        assertEquals(List.of("one", "two", "three"), sp.linesOfFile(resolved.get()).orElseThrow());
        // src/test/java also yields the owning project dir.
        assertEquals(Optional.of("proj"), sp.module("a.b.D", "D.java"));

        // Unresolved class / absent file → empty (and cached).
        assertFalse(sp.resolveClass("x.y.Z", "Z.java").isPresent());
        assertFalse(sp.linesOfFile(dir.resolve("nope/Nope.java")).isPresent());
    }

    @Test
    void rootsExposesExplicitThenDiscoveredRoots(@TempDir final Path dir) throws IOException {
        // §17.7: the open-file browser builds its tree from these roots.
        Path explicit = dir.resolve("explicit");
        Files.createDirectories(explicit);
        Path discovered = dir.resolve("repo/proj/src/main/java");
        Files.createDirectories(discovered);

        SourcePath sp = SourcePath.of(List.of(explicit), List.of(dir.resolve("repo")));
        assertEquals(List.of(explicit, discovered), sp.roots());
    }
}
