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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The pure model behind the redesigned open-file browser (VIEWER.md §17.7): a directory
 * tree rooted at the source roots, indexing every subdirectory and {@code .java} file.
 *
 * <p>§24.6: when project roots ({@code --repo} dirs) are supplied, every source root under
 * one is shown from THAT root — the tree includes each directory between the project root
 * and the package roots ({@code hedera-node/…/src/main/java/com/…}), source roots sharing a
 * prefix merged into one subtree; a source root under no base keeps its own top-level tree.
 * Each entry also carries a {@code covered} flag — whether the recording touched the file
 * (FORMAT.md §12.1 keys, resolved against each file's path relative to its owning SOURCE
 * root) or, for a directory, any file beneath it — which the dialog renders white vs. dim.
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
     * @param covered §24.6 — the recording touched this file (or, for a directory, at least
     *     one file beneath it); drives the white-vs-dim row colour
     */
    public record Row(Path path, String name, boolean directory, int depth, boolean expanded, boolean covered) {}

    /**
     * One row of the §21.3 FLAT filter list (non-empty filter): a matching {@code .java} file
     * with its absolute {@code path} (the open target and the info-line's full path) and
     * {@code rel} — the path relative to its owning top-level tree (the §24.6 project root when
     * grouped under one), the horizontally-scrollable text the row shows so the file name is
     * always visible while leading directories clip (see {@link #suffixView}). Folder structure
     * is ignored in this mode (no expand/collapse). {@code covered} as in {@link Row}.
     */
    public record FlatRow(Path path, String rel, boolean covered) {}

    /** One immutable indexed entry: a directory (with children) or a {@code .java} file. */
    private record Node(Path path, String name, boolean dir, List<Node> children, boolean covered) {}

    /** Sibling order everywhere in the tree: directories before files, then name (case-insensitive). */
    private static final Comparator<Node> NODE_ORDER = Comparator
            .comparing((Node n) -> !n.dir())
            .thenComparing(n -> n.name().toLowerCase(Locale.ROOT))
            .thenComparing(Node::name);

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

    /** Backwards-compatible build: no project roots, no coverage (§17.7 shape). */
    public static FileTree build(final List<Path> rootDirs) {
        return build(rootDirs, List.of(), Set.of());
    }

    /**
     * One §26.3 commit-pinned repo tree to graft alongside the filesystem trees: {@code base} is
     * the repo root (the top-level tree, named by its absolute path) and each file is its
     * repo-relative path plus its FORMAT.md §12.1 coverage key. File paths become
     * {@code base.resolve(relPath)} — possibly VIRTUAL (absent from the working tree); opening
     * them routes through the git-aware source path.
     */
    public record VirtualTree(Path base, List<VirtualFile> files) {}

    /** One §26.3 virtual file: repo-relative {@code /}-joined path + §12.1 coverage key. */
    public record VirtualFile(String relPath, String coverageKey) {}

    /**
     * §26.3: builds the filesystem trees per {@link #build(List, List, Set)} and then one tree
     * per {@code virtualTrees} entry from its LISTED paths (no filesystem walk) — the open-file
     * browser shows the pinned commit's module/package layout, coverage colours included.
     */
    public static FileTree build(final List<Path> rootDirs, final List<Path> baseDirs,
            final Set<String> coveredKeys, final List<VirtualTree> virtualTrees) {
        FileTree fs = build(rootDirs, baseDirs, coveredKeys);
        if (virtualTrees == null || virtualTrees.isEmpty()) {
            return fs;
        }
        Set<String> covered = coveredKeys == null ? Set.of() : coveredKeys;
        List<Node> tops = new ArrayList<>(fs.roots);
        for (VirtualTree vt : virtualTrees) {
            Node top = virtualNode(vt.base().toAbsolutePath().normalize(), vt.files(), covered);
            if (top != null) {
                tops.add(top);
            }
        }
        return new FileTree(List.copyOf(tops));
    }

    /** Builds one virtual top-level tree from listed rel paths (dirs implied by the segments). */
    private static Node virtualNode(final Path base, final List<VirtualFile> files, final Set<String> covered) {
        ChainDir root = new ChainDir(base);
        for (VirtualFile f : files) {
            if (f == null || f.relPath() == null || f.relPath().isEmpty()) {
                continue;
            }
            String[] segments = f.relPath().split("/");
            ChainDir dir = root;
            for (int i = 0; i < segments.length - 1; i++) {
                dir = dir.dir(segments[i]);
            }
            String name = segments[segments.length - 1];
            dir.attachFile(new Node(base.resolve(f.relPath()), name, false, List.of(),
                    covered.contains(f.coverageKey())));
        }
        Node top = root.toNode(base.toString());
        return top.children().isEmpty() ? null : top;
    }

    /**
     * Walks {@code rootDirs} once and builds the index (§17.7/§24.6): directories and
     * {@code .java} files, directories sorted before files and each group sorted by name
     * (case-insensitive). Duplicate roots are indexed once; unreadable directories
     * contribute what could be listed; directories without any {@code .java} beneath are
     * pruned. Symbolic links to directories are not followed (cycle safety).
     *
     * <p>§24.6: a source root lying under one of {@code baseDirs} (deepest containing base
     * wins) is shown from that base — the base becomes the top-level tree (named by its
     * absolute path, like any root) and every directory between it and the source root
     * appears as an ordinary expandable level, source roots sharing a prefix merged.
     * {@code coveredKeys} are the recording's FORMAT.md §12.1 file keys; each indexed file
     * is matched by its path relative to its owning SOURCE root (not the base).
     *
     * @param rootDirs the source roots (each a package root), in display order
     * @param baseDirs the §24.6 project roots ({@code --repo} dirs); empty for the old shape
     * @param coveredKeys the recording's visited-file keys ({@code com/foo/Bar.java}), or empty
     * @return the built tree, with every top-level root initially expanded
     */
    public static FileTree build(final List<Path> rootDirs, final List<Path> baseDirs, final Set<String> coveredKeys) {
        Set<Path> seen = new LinkedHashSet<>();
        for (Path r : rootDirs == null ? List.<Path>of() : rootDirs) {
            if (r != null) {
                seen.add(r.toAbsolutePath().normalize());
            }
        }
        List<Path> bases = new ArrayList<>();
        for (Path b : baseDirs == null ? List.<Path>of() : baseDirs) {
            if (b != null) {
                Path norm = b.toAbsolutePath().normalize();
                if (!bases.contains(norm)) {
                    bases.add(norm);
                }
            }
        }
        Set<String> covered = coveredKeys == null ? Set.of() : coveredKeys;
        List<Node> tops = new ArrayList<>();
        Map<Path, ChainDir> byBase = new LinkedHashMap<>();
        for (Path r : seen) {
            if (!Files.isDirectory(r)) {
                continue;
            }
            Node root = indexDir(r, r.toString(), "", covered);
            if (root == null) {
                continue;
            }
            Path base = deepestBaseOf(r, bases);
            if (base == null) {
                tops.add(root); // §17.7 shape: its own top-level tree
            } else {
                byBase.computeIfAbsent(base, ChainDir::new).attach(base.relativize(r), root);
            }
        }
        for (Map.Entry<Path, ChainDir> e : byBase.entrySet()) {
            tops.add(e.getValue().toNode(e.getKey().toString()));
        }
        return new FileTree(List.copyOf(tops));
    }

    /** The deepest base dir containing {@code root} (equality counts), or {@code null}. */
    private static Path deepestBaseOf(final Path root, final List<Path> bases) {
        Path best = null;
        for (Path b : bases) {
            if (root.startsWith(b) && (best == null || b.getNameCount() > best.getNameCount())) {
                best = b;
            }
        }
        return best;
    }

    /**
     * §24.6 merge helper: the in-progress directory chain between a base and its source
     * roots. Each level holds the chain subdirectories built so far plus the children of any
     * source root that ends exactly here; {@link #toNode} freezes the level into an ordinary
     * {@link Node} (chain dirs and attached entries merged in {@link #NODE_ORDER}, coverage
     * = any child covered).
     */
    private static final class ChainDir {
        private final Path path;
        private final Map<String, ChainDir> dirs = new LinkedHashMap<>();
        private final List<Node> attached = new ArrayList<>();

        ChainDir(final Path path) {
            this.path = path;
        }

        void attach(final Path rel, final Node sourceRoot) {
            if (rel == null || rel.toString().isEmpty()) {
                attached.addAll(sourceRoot.children()); // base == source root: children sit here
                return;
            }
            String head = rel.getName(0).toString();
            ChainDir child = dirs.computeIfAbsent(head, h -> new ChainDir(path.resolve(h)));
            child.attach(rel.getNameCount() > 1 ? rel.subpath(1, rel.getNameCount()) : null, sourceRoot);
        }

        /** The (created-on-demand) chain subdirectory named {@code name} (§26.3 virtual builds). */
        ChainDir dir(final String name) {
            return dirs.computeIfAbsent(name, h -> new ChainDir(path.resolve(h)));
        }

        /** Attaches one leaf file node at this level (§26.3 virtual builds). */
        void attachFile(final Node file) {
            attached.add(file);
        }

        Node toNode(final String name) {
            List<Node> children = new ArrayList<>(attached);
            for (Map.Entry<String, ChainDir> e : dirs.entrySet()) {
                children.add(e.getValue().toNode(e.getKey()));
            }
            children.sort(NODE_ORDER);
            boolean covered = children.stream().anyMatch(Node::covered);
            return new Node(path, name, true, List.copyOf(children), covered);
        }
    }

    /**
     * Recursively indexes {@code dir}, returning {@code null} when its subtree holds no
     * {@code .java} file (the build-time prune — such directories never appear).
     * {@code rel} is the source-root-relative path of {@code dir} ({@code ""} at the root),
     * whose extension by each file name forms the §12.1 coverage key.
     */
    private static Node indexDir(final Path dir, final String name, final String rel, final Set<String> covered) {
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
            String childRel = rel.isEmpty() ? n : rel + "/" + n;
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                Node child = indexDir(p, n, childRel, covered);
                if (child != null) {
                    children.add(child);
                }
            } else if (n.endsWith(".java") && Files.isRegularFile(p)) {
                children.add(new Node(p.toAbsolutePath().normalize(), n, false, List.of(),
                        covered.contains(childRel)));
            }
        }
        if (children.isEmpty()) {
            return null;
        }
        boolean cov = children.stream().anyMatch(Node::covered);
        return new Node(dir.toAbsolutePath().normalize(), name, true, List.copyOf(children), cov);
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
        out.add(new Row(n.path(), n.name(), n.dir(), depth, expanded, n.covered()));
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

    /**
     * §28.6: expands {@code dir} and then recursively every DESCENDANT that is a directory's
     * only entry — so a long single-directory chain (deep package paths) opens in one keystroke,
     * stopping at the first directory with more than one entry or at a leaf.
     */
    public void expandChain(final Path dir) {
        Node n = findNode(dir);
        while (n != null && n.dir()) {
            expandedDirs.add(n.path());
            n = n.children().size() == 1 && n.children().get(0).dir() ? n.children().get(0) : null;
        }
    }

    /** Collapses the directory at {@code dir}. */
    public void collapse(final Path dir) {
        expandedDirs.remove(dir);
    }

    /**
     * §28.6: collapses {@code dir} and then every ANCESTOR that holds it as its only entry —
     * undoing an {@link #expandChain} in one keystroke — stopping below a root or at the first
     * ancestor with more than one entry.
     *
     * @return the topmost collapsed directory (the row the selection should land on)
     */
    public Path collapseChain(final Path dir) {
        expandedDirs.remove(dir);
        Path top = dir;
        Node parent = parentOf(top);
        while (parent != null && parent.children().size() == 1 && !isRoot(parent.path())) {
            expandedDirs.remove(parent.path());
            top = parent.path();
            parent = parentOf(top);
        }
        return top;
    }

    /** The indexed node at {@code path}, or {@code null}. */
    private Node findNode(final Path path) {
        for (Node r : roots) {
            Node hit = findNode(r, path);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static Node findNode(final Node n, final Path path) {
        if (n.path().equals(path)) {
            return n;
        }
        if (!n.dir() || !path.startsWith(n.path())) {
            return null;
        }
        for (Node c : n.children()) {
            Node hit = findNode(c, path);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** The parent NODE of the entry at {@code path}, or {@code null} for roots/unknown paths. */
    private Node parentOf(final Path path) {
        for (Node r : roots) {
            Node hit = parentOf(r, path);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static Node parentOf(final Node n, final Path path) {
        if (!n.dir() || !path.startsWith(n.path()) || n.path().equals(path)) {
            return null;
        }
        for (Node c : n.children()) {
            if (c.path().equals(path)) {
                return n;
            }
            Node hit = parentOf(c, path);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** Whether {@code path} is one of the top-level tree roots. */
    private boolean isRoot(final Path path) {
        for (Node r : roots) {
            if (r.path().equals(path)) {
                return true;
            }
        }
        return false;
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
                out.add(new FlatRow(n.path(), relativeText(root, n.path()), n.covered()));
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

    /**
     * The §22.2 flat-filter row view: {@link #suffixView} widened to exactly {@code width} columns
     * and RIGHT-ALIGNED, so the file name is always flush against the right edge and the leading
     * directories clip/scroll on the left. When the whole path fits it is left-padded with spaces;
     * when it does not, the tail (the last {@code width} columns, shifted by {@code hcol}) already
     * fills the width. The result is always exactly {@code width} columns (empty for a non-positive
     * {@code width}), ready to paint directly without further padding.
     *
     * @param full the row's full (root-relative) path text
     * @param width the visible column count
     * @param hcol the horizontal scroll offset (0 shows the suffix; larger reveals the prefix)
     * @return the row text, exactly {@code width} columns, the file name flush-right
     */
    public static String rightAlign(final String full, final int width, final int hcol) {
        if (width <= 0) {
            return "";
        }
        String view = suffixView(full, width, hcol);
        return view.length() < width ? " ".repeat(width - view.length()) + view : view;
    }
}
