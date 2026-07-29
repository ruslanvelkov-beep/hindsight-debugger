// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The pure model behind the redesigned open-file browser (VIEWER.md §17.7): a directory
 * tree rooted at the source roots, indexing every subdirectory and {@code .java} file.
 *
 * <p>The index is built ONCE ({@link #build}) by walking the roots — directories whose
 * subtree contains no {@code .java} file are pruned at build time and never appear — and
 * is immutable afterwards; only two pieces of state mutate:
 * <ul>
 *   <li><b>Expansion</b>, keyed by the directory's absolute path (never by visible row), so
 *       a directory that disappears under a filter and later reappears keeps its prior
 *       expand/collapse state, across filter changes and dialog close/reopen alike. Roots
 *       start expanded so a fresh dialog shows their content immediately.
 *   <li><b>The filter</b> ({@link #setFilter}): a case-insensitive regex matched
 *       ({@code find()}) against FILE NAMES only. While a filter is set, only matching
 *       files are visible and a directory whose filtered subtree is empty disappears; an
 *       invalid regex marks the filter invalid and {@link #rows()} lists nothing. Clearing
 *       the filter restores the full index.
 * </ul>
 *
 * <p>{@link #rows()} flattens the visible forest depth-first per the current expansion —
 * the exact row list the dialog renders and navigates. Terminal-free and unit-tested
 * against temp directories; all lanterna key handling stays in {@code ViewerApp}.
 */
public final class FileTree {

    /**
     * One visible row of the (filtered) tree.
     *
     * @param path the entry's absolute path (the open target for files, the expansion key
     *     for directories)
     * @param name the display text — the bare file/directory name, except roots, which show
     *     their full absolute path
     * @param directory whether this is a directory (expandable) rather than a {@code .java} leaf
     * @param depth indentation depth (0 for a root)
     * @param expanded whether a directory row is currently expanded (always {@code false} for files)
     */
    public record Row(Path path, String name, boolean directory, int depth, boolean expanded) {}

    /**
     * One row of the §21.3 FLAT filter list (non-empty filter): a matching {@code .java} file
     * with its absolute {@code path} (the open target and the info-line's full path) and
     * {@code rel} — the path relative to its owning root, the horizontally-scrollable text the
     * row shows so the file name is always visible while leading directories clip (see
     * {@link #suffixView}). Folder structure is ignored in this mode (no expand/collapse).
     */
    public record FlatRow(Path path, String rel) {}

    /** One immutable indexed entry: a directory (with children) or a {@code .java} file. */
    private record Node(Path path, String name, boolean dir, List<Node> children) {}

    private final List<Node> roots;
    private final Set<Path> expandedDirs = new HashSet<>();
    private Set<Path> matching; // visible paths under the current filter; null = no filter
    private boolean filterValid = true;

    private FileTree(final List<Node> roots) {
        this.roots = roots;
        for (Node r : roots) {
            expandedDirs.add(r.path()); // roots start expanded — content visible on first open
        }
    }

    /**
     * Walks {@code rootDirs} once and builds the index (§17.7): directories and
     * {@code .java} files, directories sorted before files and each group sorted by name
     * (case-insensitive). Duplicate roots are indexed once; unreadable directories
     * contribute what could be listed; directories without any {@code .java} beneath are
     * pruned. Symbolic links to directories are not followed (cycle safety).
     *
     * @param rootDirs the source roots (each a package root), in display order
     * @return the built tree, with every root initially expanded
     */
    public static FileTree build(final List<Path> rootDirs) {
        Set<Path> seen = new LinkedHashSet<>();
        for (Path r : rootDirs == null ? List.<Path>of() : rootDirs) {
            if (r != null) {
                seen.add(r.toAbsolutePath().normalize());
            }
        }
        List<Node> roots = new ArrayList<>();
        for (Path r : seen) {
            if (!Files.isDirectory(r)) {
                continue;
            }
            Node root = indexDir(r, r.toString());
            if (root != null) {
                roots.add(root);
            }
        }
        return new FileTree(List.copyOf(roots));
    }

    /**
     * Recursively indexes {@code dir}, returning {@code null} when its subtree holds no
     * {@code .java} file (the build-time prune — such directories never appear).
     */
    private static Node indexDir(final Path dir, final String name) {
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                entries.add(p);
            }
        } catch (IOException e) {
            return null; // unreadable directory: nothing to show beneath it
        }
        entries.sort(Comparator.comparing(
                (Path p) -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                .thenComparing(p -> nameOf(p).toLowerCase(Locale.ROOT))
                .thenComparing(FileTree::nameOf));
        List<Node> children = new ArrayList<>();
        for (Path p : entries) {
            String n = nameOf(p);
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                Node child = indexDir(p, n);
                if (child != null) {
                    children.add(child);
                }
            } else if (n.endsWith(".java") && Files.isRegularFile(p)) {
                children.add(new Node(p.toAbsolutePath().normalize(), n, false, List.of()));
            }
        }
        if (children.isEmpty()) {
            return null;
        }
        return new Node(dir.toAbsolutePath().normalize(), name, true, List.copyOf(children));
    }

    private static String nameOf(final Path p) {
        Path f = p.getFileName();
        return f != null ? f.toString() : p.toString();
    }

    /**
     * Applies the {@code file:} query (§17.7): empty/{@code null} clears the filter
     * (everything indexed is visible again); otherwise the text is compiled as a
     * case-insensitive regex and matched with {@code find()} against every file NAME —
     * matching files plus their ancestor directories stay visible, everything else
     * disappears. A syntactically invalid regex flips {@link #filterValid()} to
     * {@code false} and empties the visible list until the next edit. Expansion state is
     * untouched either way (remembered across filter changes).
     *
     * @param regex the raw query text
     */
    public void setFilter(final String regex) {
        if (regex == null || regex.isEmpty()) {
            matching = null;
            filterValid = true;
            return;
        }
        Pattern p;
        try {
            p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            matching = Set.of();
            filterValid = false;
            return;
        }
        Set<Path> acc = new HashSet<>();
        for (Node r : roots) {
            markMatches(r, p, acc);
        }
        matching = acc;
        filterValid = true;
    }

    /** Whether the last {@link #setFilter} text compiled; {@code false} means "invalid regex". */
    public boolean filterValid() {
        return filterValid;
    }

    private static boolean markMatches(final Node n, final Pattern p, final Set<Path> acc) {
        if (!n.dir()) {
            boolean m = p.matcher(n.name()).find();
            if (m) {
                acc.add(n.path());
            }
            return m;
        }
        boolean any = false;
        for (Node c : n.children()) {
            // no short-circuit: every matching descendant must be marked visible
            if (markMatches(c, p, acc)) {
                any = true;
            }
        }
        if (any) {
            acc.add(n.path());
        }
        return any;
    }

    /**
     * The visible rows: the filtered forest flattened depth-first per the current expansion
     * state. Empty when the filter is invalid or matches nothing (or no root holds any
     * {@code .java} file).
     */
    public List<Row> rows() {
        if (!filterValid) {
            return List.of();
        }
        List<Row> out = new ArrayList<>();
        for (Node r : roots) {
            if (visible(r)) {
                walk(r, 0, out);
            }
        }
        return out;
    }

    private boolean visible(final Node n) {
        return matching == null || matching.contains(n.path());
    }

    private void walk(final Node n, final int depth, final List<Row> out) {
        boolean expanded = n.dir() && expandedDirs.contains(n.path());
        out.add(new Row(n.path(), n.name(), n.dir(), depth, expanded));
        if (!expanded) {
            return;
        }
        for (Node c : n.children()) {
            if (visible(c)) {
                walk(c, depth + 1, out);
            }
        }
    }

    /** Expands the directory at {@code dir} (a no-op for unknown paths — keys are dir paths). */
    public void expand(final Path dir) {
        expandedDirs.add(dir);
    }

    /** Collapses the directory at {@code dir}. */
    public void collapse(final Path dir) {
        expandedDirs.remove(dir);
    }

    /** Whether the directory at {@code dir} is currently expanded. */
    public boolean isExpanded(final Path dir) {
        return expandedDirs.contains(dir);
    }

    /**
     * The §21.3 FLAT filter result: every indexed {@code .java} file whose name matches the
     * current {@link #setFilter} regex, as a flat list (folder structure ignored — no
     * expand/collapse), depth-first in the same directories-then-files order the tree walks,
     * each carrying its root-relative path text. Empty when NO filter is set (browse mode uses
     * {@link #rows()} instead), when the filter is invalid, or when nothing matches. Reuses the
     * same matching set {@link #setFilter} computed for {@link #rows()}, so the two views stay
     * consistent.
     *
     * @return the matching files as a flat list, or empty in browse/invalid/no-match cases
     */
    public List<FlatRow> flatRows() {
        if (!filterValid || matching == null) {
            return List.of();
        }
        List<FlatRow> out = new ArrayList<>();
        for (Node r : roots) {
            collectMatchingFiles(r, r.path(), out);
        }
        return List.copyOf(out);
    }

    private void collectMatchingFiles(final Node n, final Path root, final List<FlatRow> out) {
        if (!n.dir()) {
            if (matching.contains(n.path())) {
                out.add(new FlatRow(n.path(), relativeText(root, n.path())));
            }
            return;
        }
        for (Node c : n.children()) {
            collectMatchingFiles(c, root, out);
        }
    }

    /**
     * The {@code root}-relative path text a §21.3 flat-filter row shows, always forward-slash
     * separated (so the display is stable across platforms). {@code /a/b/root} + {@code
     * /a/b/root/com/foo/Bar.java} → {@code com/foo/Bar.java}.
     *
     * @param root the owning source root
     * @param file the matching file
     * @return the root-relative path, {@code /}-joined
     */
    static String relativeText(final Path root, final Path file) {
        Path rel = root.relativize(file);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rel.getNameCount(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(rel.getName(i));
        }
        return sb.toString();
    }

    /**
     * The §21.3 right-anchored, horizontally-scrollable view of a flat-filter row's path
     * {@code full} into {@code width} columns: at {@code hcol == 0} the TAIL — the last
     * {@code width} characters, so the file name is always visible and leading directories clip;
     * increasing {@code hcol} slides the window left one column at a time to reveal the prefix,
     * clamped so it never scrolls past the start. When the whole path already fits in
     * {@code width}, it is returned unchanged.
     *
     * @param full the row's full (root-relative) path text
     * @param width the visible column count
     * @param hcol the horizontal scroll offset (0 shows the suffix; larger reveals the prefix)
     * @return the visible substring, never longer than {@code width}
     */
    public static String suffixView(final String full, final int width, final int hcol) {
        if (full == null || width <= 0) {
            return "";
        }
        int len = full.length();
        if (len <= width) {
            return full;
        }
        int maxHcol = len - width;
        int h = Math.max(0, Math.min(hcol, maxHcol));
        int start = maxHcol - h;
        return full.substring(start, start + width);
    }
}
