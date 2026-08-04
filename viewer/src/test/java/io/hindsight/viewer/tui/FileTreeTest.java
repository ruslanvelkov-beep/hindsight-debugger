// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the pure model of the §17.7 open-file browser against temp directories: the
 * build-time index (directories + {@code .java} files only, {@code .java}-less directories
 * pruned), the flattening under expansion, the per-keystroke regex filter with directory
 * pruning, invalid-regex handling, and the expansion memory that survives filter changes.
 */
final class FileTreeTest {

    /**
     * Builds the reference tree used by most tests:
     * <pre>
     * root/
     *   com/
     *     foo/ Bar.java Baz.java
     *     empty/            (no .java — pruned)
     *   util/ Helper.java
     *   README.md           (not .java — ignored)
     *   Top.java
     * </pre>
     */
    private static Path sampleRoot(final Path dir) throws IOException {
        Path root = dir.resolve("root");
        Files.createDirectories(root.resolve("com/foo"));
        Files.createDirectories(root.resolve("com/empty"));
        Files.createDirectories(root.resolve("util"));
        Files.writeString(root.resolve("com/foo/Bar.java"), "class Bar {}\n");
        Files.writeString(root.resolve("com/foo/Baz.java"), "class Baz {}\n");
        Files.writeString(root.resolve("util/Helper.java"), "class Helper {}\n");
        Files.writeString(root.resolve("README.md"), "readme\n");
        Files.writeString(root.resolve("Top.java"), "class Top {}\n");
        return root;
    }

    private static List<String> names(final FileTree tree) {
        return tree.rows().stream().map(FileTree.Row::name).toList();
    }

    /** Expands every directory currently visible, repeatedly, until the row set is stable. */
    private static void expandAll(final FileTree tree) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (FileTree.Row r : tree.rows()) {
                if (r.directory() && !r.expanded()) {
                    tree.expand(r.path());
                    changed = true;
                }
            }
        }
    }

    @Test
    void indexesOnlyDirsAndJavaFilesAndPrunesJavaLessDirs(@TempDir final Path dir) throws IOException {
        Path root = sampleRoot(dir);
        FileTree tree = FileTree.build(List.of(root));
        expandAll(tree);
        List<String> names = names(tree);
        assertEquals(
                List.of(root.toString(), "com", "foo", "Bar.java", "Baz.java", "util",
                        "Helper.java", "Top.java"),
                names);
        assertFalse(names.contains("empty"), "a directory without .java beneath is pruned");
        assertFalse(names.contains("README.md"), "non-.java files are not indexed");
    }

    @Test
    void rootRowShowsAbsolutePathAndStartsExpanded(@TempDir final Path dir) throws IOException {
        Path root = sampleRoot(dir);
        FileTree tree = FileTree.build(List.of(root));
        List<FileTree.Row> rows = tree.rows();
        assertEquals(root.toAbsolutePath().normalize().toString(), rows.get(0).name());
        assertTrue(rows.get(0).directory());
        assertTrue(rows.get(0).expanded(), "roots start expanded");
        assertEquals(0, rows.get(0).depth());
        // Root expanded, subdirectories collapsed: top-level children only, dirs before files.
        assertEquals(List.of(root.toString(), "com", "util", "Top.java"), names(tree));
        assertEquals(1, rows.get(1).depth());
    }

    @Test
    void expansionRevealsAndCollapseHidesChildren(@TempDir final Path dir) throws IOException {
        Path root = sampleRoot(dir);
        FileTree tree = FileTree.build(List.of(root));
        Path com = root.resolve("com");
        tree.expand(com);
        assertEquals(List.of(root.toString(), "com", "foo", "util", "Top.java"), names(tree));
        tree.expand(root.resolve("com/foo"));
        assertEquals(
                List.of(root.toString(), "com", "foo", "Bar.java", "Baz.java", "util", "Top.java"),
                names(tree));
        assertTrue(tree.isExpanded(com));
        tree.collapse(com);
        assertEquals(List.of(root.toString(), "com", "util", "Top.java"), names(tree));
        assertFalse(tree.isExpanded(com));
    }

    @Test
    void filterMatchesFileNamesCaseInsensitivelyAndPrunesDirs(@TempDir final Path dir) throws IOException {
        Path root = sampleRoot(dir);
        FileTree tree = FileTree.build(List.of(root));
        expandAll(tree);
        tree.setFilter("BAR");
        assertTrue(tree.filterValid());
        // Only Bar.java matches; util (Helper.java) and Top.java disappear, empty dirs pruned.
        assertEquals(List.of(root.toString(), "com", "foo", "Bar.java"), names(tree));
    }

    @Test
    void filterIsARegexOverFileNames(@TempDir final Path dir) throws IOException {
        Path root = sampleRoot(dir);
        FileTree tree = FileTree.build(List.of(root));
        expandAll(tree);
        tree.setFilter("b.z|help");
        assertEquals(List.of(root.toString(), "com", "foo", "Baz.java", "util", "Helper.java"),
                names(tree));
        // Directory names never match by themselves: "util" as a regex hits no FILE name.
        tree.setFilter("^util$");
        assertEquals(List.of(), names(tree));
    }

    @Test
    void invalidRegexListsNothingUntilCorrected(@TempDir final Path dir) throws IOException {
        Path root = sampleRoot(dir);
        FileTree tree = FileTree.build(List.of(root));
        tree.setFilter("[");
        assertFalse(tree.filterValid());
        assertEquals(List.of(), tree.rows());
        tree.setFilter("bar");
        assertTrue(tree.filterValid());
        assertFalse(tree.rows().isEmpty());
    }

    @Test
    void clearingTheFilterRestoresEverything(@TempDir final Path dir) throws IOException {
        Path root = sampleRoot(dir);
        FileTree tree = FileTree.build(List.of(root));
        expandAll(tree);
        List<String> all = names(tree);
        tree.setFilter("helper");
        assertEquals(List.of(root.toString(), "util", "Helper.java"), names(tree));
        tree.setFilter("");
        assertTrue(tree.filterValid());
        assertEquals(all, names(tree));
    }

    @Test
    void expansionIsRememberedAcrossFilterChanges(@TempDir final Path dir) throws IOException {
        Path root = sampleRoot(dir);
        FileTree tree = FileTree.build(List.of(root));
        Path com = root.resolve("com");
        Path foo = root.resolve("com/foo");
        tree.expand(com);
        tree.expand(foo);
        // A filter under which com/foo DISAPPEAR entirely. util stays (its subtree matches)
        // but remains COLLAPSED — filtering never auto-expands (§17.7: expansion is user state).
        tree.setFilter("helper");
        assertEquals(List.of(root.toString(), "util"), names(tree));
        tree.expand(root.resolve("util"));
        assertEquals(List.of(root.toString(), "util", "Helper.java"), names(tree));
        // ...must not forget their expansion: clearing the filter shows Bar/Baz again
        // without re-expanding anything (state is keyed by dir path, not visible row).
        tree.setFilter("");
        assertTrue(tree.isExpanded(com));
        assertTrue(tree.isExpanded(foo));
        assertTrue(names(tree).containsAll(List.of("Bar.java", "Baz.java")));
    }

    @Test
    void rootWithNoJavaFilesYieldsNoRows(@TempDir final Path dir) throws IOException {
        Path bare = dir.resolve("bare");
        Files.createDirectories(bare.resolve("sub"));
        Files.writeString(bare.resolve("sub/notes.txt"), "x\n");
        FileTree tree = FileTree.build(List.of(bare));
        assertEquals(List.of(), tree.rows());
    }

    @Test
    void multipleRootsAreIndexedAndDuplicatesCollapse(@TempDir final Path dir) throws IOException {
        Path root = sampleRoot(dir);
        Path other = dir.resolve("other");
        Files.createDirectories(other);
        Files.writeString(other.resolve("Solo.java"), "class Solo {}\n");
        FileTree tree = FileTree.build(List.of(root, other, root));
        List<FileTree.Row> roots = tree.rows().stream().filter(r -> r.depth() == 0).toList();
        assertEquals(2, roots.size(), "the duplicate root is indexed once");
        assertEquals(root.toString(), roots.get(0).name());
        assertEquals(other.toString(), roots.get(1).name());
        assertTrue(names(tree).contains("Solo.java"));
    }

    @Test
    void missingRootIsSkipped(@TempDir final Path dir) throws IOException {
        Path root = sampleRoot(dir);
        FileTree tree = FileTree.build(List.of(dir.resolve("does-not-exist"), root));
        assertEquals(root.toString(), tree.rows().get(0).name());
    }

    @Test
    void depthsFollowTheDirectoryNesting(@TempDir final Path dir) throws IOException {
        Path root = sampleRoot(dir);
        FileTree tree = FileTree.build(List.of(root));
        expandAll(tree);
        for (FileTree.Row r : tree.rows()) {
            int expected = switch (r.name()) {
                case "com", "util", "Top.java" -> 1;
                case "foo" -> 2;
                case "Bar.java", "Baz.java" -> 3;
                case "Helper.java" -> 2;
                default -> 0; // the root row
            };
            assertEquals(expected, r.depth(), r.name());
        }
    }

    // ---- §21.3 FLAT filter mode + suffix view ----

    private static List<String> flatRels(final FileTree tree) {
        return tree.flatRows().stream().map(FileTree.FlatRow::rel).toList();
    }

    @Test
    void flatRowsAreEmptyInBrowseMode(@TempDir final Path dir) throws IOException {
        Path root = sampleRoot(dir);
        FileTree tree = FileTree.build(List.of(root));
        // Empty filter → BROWSE mode: rows() drives the tree; flatRows() is empty.
        assertTrue(tree.flatRows().isEmpty());
        assertFalse(tree.rows().isEmpty());
    }

    @Test
    void flatRowsListMatchingFilesWithRootRelativePaths(@TempDir final Path dir) throws IOException {
        Path root = sampleRoot(dir);
        FileTree tree = FileTree.build(List.of(root));
        tree.setFilter("ba"); // matches Bar.java and Baz.java (case-insensitive, file name)
        assertEquals(List.of("com/foo/Bar.java", "com/foo/Baz.java"), flatRels(tree));
        // The row carries the absolute file path (the open target); no directory rows appear.
        assertEquals(root.resolve("com/foo/Bar.java").toAbsolutePath().normalize(),
                tree.flatRows().get(0).path());
    }

    @Test
    void flatRowsIgnoreExpansionAndSpanTheWholeTree(@TempDir final Path dir) throws IOException {
        Path root = sampleRoot(dir);
        FileTree tree = FileTree.build(List.of(root));
        tree.collapse(root); // flat mode does not care about expand/collapse state
        tree.setFilter("java");
        assertEquals(List.of("com/foo/Bar.java", "com/foo/Baz.java", "util/Helper.java", "Top.java"),
                flatRels(tree));
    }

    @Test
    void flatRowsEmptyForInvalidRegexOrNoMatch(@TempDir final Path dir) throws IOException {
        Path root = sampleRoot(dir);
        FileTree tree = FileTree.build(List.of(root));
        tree.setFilter("[");
        assertTrue(tree.flatRows().isEmpty(), "invalid regex → no flat rows");
        tree.setFilter("zzz");
        assertTrue(tree.flatRows().isEmpty(), "no match → no flat rows");
    }

    @Test
    void relativeTextJoinsNameElementsWithForwardSlashes(@TempDir final Path dir) throws IOException {
        Path root = dir.resolve("root");
        Files.createDirectories(root.resolve("a/b"));
        assertEquals("a/b/C.java", FileTree.relativeText(root, root.resolve("a/b/C.java")));
        assertEquals("Top.java", FileTree.relativeText(root, root.resolve("Top.java")));
    }

    @Test
    void suffixViewShowsTheFileNameThenRevealsThePrefix() {
        String full = "com/foo/bar/Baz.java"; // length 20
        // Fits within the width: returned whole.
        assertEquals(full, FileTree.suffixView(full, 40, 0));
        assertEquals(full, FileTree.suffixView(full, 20, 0));
        // Narrower: hcol 0 shows the TAIL, so the file name stays visible (leading dirs clip).
        assertEquals("bar/Baz.java", FileTree.suffixView(full, 12, 0));
        // Scrolling right reveals the prefix one column at a time.
        assertEquals("foo/bar/Baz.", FileTree.suffixView(full, 12, 4));
        // Clamp: a huge hcol stops at the start (the full prefix).
        assertEquals("com/foo/bar/", FileTree.suffixView(full, 12, 999));
        // Degenerate inputs.
        assertEquals("", FileTree.suffixView(null, 12, 0));
        assertEquals("", FileTree.suffixView(full, 0, 0));
    }

    @Test
    void rightAlignFlushesTheFileNameRight() {
        String full = "com/foo/bar/Baz.java"; // length 20
        // Fits: left-padded to the width so the name is flush-right (whole path visible).
        assertEquals("        " + full, FileTree.rightAlign(full, 28, 0)); // 8 pad + 20
        assertEquals(full, FileTree.rightAlign(full, 20, 0)); // exact fit, no padding
        // Narrower: the tail already fills the width (same as suffixView), still flush-right.
        assertEquals("bar/Baz.java", FileTree.rightAlign(full, 12, 0));
        assertEquals("foo/bar/Baz.", FileTree.rightAlign(full, 12, 4));
        // Every result is exactly `width` columns wide.
        assertEquals(28, FileTree.rightAlign(full, 28, 0).length());
        assertEquals(12, FileTree.rightAlign(full, 12, 0).length());
        // Degenerate inputs.
        assertEquals("", FileTree.rightAlign(null, 0, 0));
        assertEquals("     ", FileTree.rightAlign(null, 5, 0)); // null path -> all padding
    }

    // ---- §24.6 project-root bases + coverage ----

    /**
     * A repo-shaped fixture: {@code repo/moduleA/src/main/java/com/foo/{Bar,Baz}.java} and
     * {@code repo/moduleB/src/main/java/util/Helper.java}, whose two source roots share the
     * {@code repo} base.
     */
    private static Path repoRoot(final Path dir) throws IOException {
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo.resolve("moduleA/src/main/java/com/foo"));
        Files.createDirectories(repo.resolve("moduleB/src/main/java/util"));
        Files.writeString(repo.resolve("moduleA/src/main/java/com/foo/Bar.java"), "class Bar {}\n");
        Files.writeString(repo.resolve("moduleA/src/main/java/com/foo/Baz.java"), "class Baz {}\n");
        Files.writeString(repo.resolve("moduleB/src/main/java/util/Helper.java"), "class Helper {}\n");
        return repo;
    }

    @Test
    void basesShowEveryDirectoryFromTheProjectRootAndMergeSharedPrefixes(@TempDir final Path dir)
            throws IOException {
        Path repo = repoRoot(dir);
        List<Path> roots = List.of(
                repo.resolve("moduleA/src/main/java"), repo.resolve("moduleB/src/main/java"));
        FileTree tree = FileTree.build(roots, List.of(repo), java.util.Set.of());
        expandAll(tree);
        // ONE top-level tree named by the base's absolute path, then every directory level down
        // to the package roots — moduleA and moduleB merged under the shared repo base.
        assertEquals(List.of(
                        repo.toAbsolutePath().normalize().toString(),
                        "moduleA", "src", "main", "java", "com", "foo", "Bar.java", "Baz.java",
                        "moduleB", "src", "main", "java", "util", "Helper.java"),
                names(tree));
        // Flat rows are base-relative, so the whole path from the project root is visible.
        tree.setFilter("helper");
        assertEquals(List.of("moduleB/src/main/java/util/Helper.java"), flatRels(tree));
    }

    @Test
    void rootUnderNoBaseKeepsItsOwnTopLevelTree(@TempDir final Path dir) throws IOException {
        Path root = sampleRoot(dir);
        FileTree tree = FileTree.build(List.of(root), List.of(dir.resolve("elsewhere")), java.util.Set.of());
        assertEquals(root.toAbsolutePath().normalize().toString(), tree.rows().get(0).name());
    }

    @Test
    void coverageMarksFilesBySourceRootRelativeKeyAndPropagatesToDirectories(@TempDir final Path dir)
            throws IOException {
        Path repo = repoRoot(dir);
        List<Path> roots = List.of(
                repo.resolve("moduleA/src/main/java"), repo.resolve("moduleB/src/main/java"));
        // Coverage keys are SOURCE-ROOT-relative (§12.1), not base-relative.
        FileTree tree = FileTree.build(roots, List.of(repo), java.util.Set.of("com/foo/Bar.java"));
        expandAll(tree);
        Path bar = repo.resolve("moduleA/src/main/java/com/foo/Bar.java").toAbsolutePath().normalize();
        for (FileTree.Row r : tree.rows()) {
            // Covered = exactly the covered file and every ancestor directory on its path
            // (moduleA's whole chain up to the base root); moduleB's chain and Baz.java are not.
            assertEquals(bar.startsWith(r.path()), r.covered(), r.name());
        }
        tree.setFilter("ba");
        assertTrue(tree.flatRows().stream()
                .anyMatch(f -> f.rel().endsWith("Bar.java") && f.covered()));
        assertTrue(tree.flatRows().stream()
                .anyMatch(f -> f.rel().endsWith("Baz.java") && !f.covered()));
    }

    @Test
    void baseEqualToTheSourceRootAttachesChildrenDirectly(@TempDir final Path dir) throws IOException {
        Path root = sampleRoot(dir);
        FileTree tree = FileTree.build(List.of(root), List.of(root), java.util.Set.of());
        expandAll(tree);
        // The base row is the root itself; children appear right beneath it (no empty chain level).
        assertEquals(root.toAbsolutePath().normalize().toString(), tree.rows().get(0).name());
        assertTrue(names(tree).containsAll(List.of("com", "util", "Top.java")));
    }
}
