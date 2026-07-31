// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import io.hindsight.viewer.engine.ObjectNode;
import io.hindsight.viewer.engine.Value;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The interactive object-tree model behind the Locals window (VIEWER.md §6.4): a
 * forest of lazily-expanded {@link ObjectNode} roots with per-node expand/collapse
 * state, a selection cursor, and a vertical scroll offset.
 *
 * <p>Children are never stored — they are re-fetched from a {@link ChildSource}
 * (the engine, in production) every time the visible list is (re)computed, so the
 * tree always reflects the current cursor's {@code seq} and a cyclic object graph
 * cannot be walked eagerly. The traversal descends into every open
 * {@link ObjectNode#expandable()} node — reference nodes and the §17.3 map-entry
 * nodes alike. The cycle guard is delegated to the engine: this class threads the
 * set of ancestor object ids down each path — a node's own id joins the path only
 * when its value is a reference and it is not a map entry (an entry carries no id
 * of its own) — and hands it to {@link ChildSource#children(ObjectNode, Set)},
 * which renders a back-reference as a non-expandable leaf.
 *
 * <p>Expansion is keyed by a structural path string ({@code "2/0/1"} = root 2, its
 * child 0, that child's child 1) so it survives the roots being rebuilt on every
 * cursor move: an expanded path re-expands if it still exists and is silently
 * ignored if the shape changed. The flattening itself is a pure static function
 * ({@link #flatten}) so the traversal/keying can be tested against a fake source.
 */
public final class TreeView {

    /** Supplies a node's children as-of the current position, given its ancestor object ids. */
    public interface ChildSource {
        /**
         * The children of {@code node}; {@code ancestorIds} are the object ids strictly above it
         * on the current path (the engine adds the node's own id and uses the union as its cycle
         * set).
         */
        List<ObjectNode> children(ObjectNode node, Set<Long> ancestorIds);
    }

    /**
     * One visible tree row.
     *
     * @param node the node to render
     * @param depth indentation depth (0 for a root)
     * @param expandable whether the node can be opened
     * @param expanded whether it is currently open
     * @param key the structural path key used to persist expansion state
     */
    public record Row(ObjectNode node, int depth, boolean expandable, boolean expanded, String key) {}

    private final ChildSource childSource;
    private List<ObjectNode> roots = List.of();
    private final Set<String> expanded = new HashSet<>();
    private int selection;
    private int scroll;

    /** Creates an empty tree backed by {@code childSource}. */
    public TreeView(final ChildSource childSource) {
        this.childSource = childSource;
    }

    /** Replaces the root nodes (called every render); expansion/selection are preserved. */
    public void setRoots(final List<ObjectNode> newRoots) {
        this.roots = List.copyOf(newRoots);
    }

    /** Drops all expansion state (for when the root list is replaced so paths cannot alias). */
    public void clearExpansion() {
        expanded.clear();
        selection = 0;
        scroll = 0;
    }

    /** The currently visible rows, with expansion applied and selection clamped into range. */
    public List<Row> rows() {
        List<Row> all = flatten(roots, expanded, childSource);
        if (selection >= all.size()) {
            selection = Math.max(0, all.size() - 1);
        }
        if (selection < 0) {
            selection = 0;
        }
        return all;
    }

    /** Current selection index (into {@link #rows()}). */
    public int selection() {
        return selection;
    }

    /** Current vertical scroll offset (first visible row index). */
    public int scroll() {
        return scroll;
    }

    /**
     * Nudges the vertical scroll offset by {@code delta} rows (floored at 0) without moving the
     * selection — the §20.13/§21.9 {@code Ctrl-U}/{@code Ctrl-N} viewport scroll for the Locals window.
     * {@link #ensureVisible} still runs at render, so the scroll is bounded by keeping the selection
     * on-screen (the offset only sticks while the selection stays within the viewport).
     */
    public void scrollBy(final int delta) {
        scroll = Math.max(0, scroll + delta);
    }

    /** Adjusts the scroll offset so the selection is visible within a {@code height}-row viewport. */
    public void ensureVisible(final int height, final int total) {
        if (height <= 0) {
            scroll = 0;
            return;
        }
        if (selection < scroll) {
            scroll = selection;
        } else if (selection >= scroll + height) {
            scroll = selection - height + 1;
        }
        int max = Math.max(0, total - height);
        scroll = Math.min(Math.max(scroll, 0), max);
    }

    /** Moves the selection down by {@code n} rows (clamped). */
    public void moveDown(final int n) {
        int total = rows().size();
        selection = Math.min(selection + n, Math.max(0, total - 1));
    }

    /** Moves the selection up by {@code n} rows (clamped). */
    public void moveUp(final int n) {
        selection = Math.max(selection - n, 0);
    }

    /**
     * §26.8: programmatic selection (the find-in-window jump) — clamped to the current row count
     * on the next {@code rows()} call, like every other mutation.
     *
     * @param index the row index to select (negative selects the first row)
     */
    public void select(final int index) {
        selection = Math.max(0, index);
    }

    /** Selects the first row. */
    public void toFirst() {
        selection = 0;
    }

    /** Selects the last row. */
    public void toLast() {
        selection = Math.max(0, rows().size() - 1);
    }

    /**
     * Right/Enter behaviour (§6.4): expands the selected node if it is a collapsed expandable, or
     * steps into its first child if it is already open.
     */
    public void expandOrDescend() {
        List<Row> all = rows();
        if (all.isEmpty()) {
            return;
        }
        Row r = all.get(selection);
        if (r.expandable() && !r.expanded()) {
            expanded.add(r.key());
        } else if (r.expandable() && r.expanded() && selection + 1 < all.size()) {
            selection++;
        }
    }

    /**
     * Left behaviour (§6.4): collapses the selected node if open, otherwise moves the selection to
     * its parent row.
     */
    public void collapseOrParent() {
        List<Row> all = rows();
        if (all.isEmpty()) {
            return;
        }
        Row r = all.get(selection);
        if (r.expandable() && r.expanded()) {
            expanded.remove(r.key());
            return;
        }
        for (int i = selection - 1; i >= 0; i--) {
            if (all.get(i).depth() < r.depth()) {
                selection = i;
                return;
            }
        }
    }

    /** The root index (top-level entry) that the current selection belongs to, or -1. */
    public int selectedRootIndex() {
        List<Row> all = rows();
        if (all.isEmpty()) {
            return -1;
        }
        String key = all.get(selection).key();
        int slash = key.indexOf('/');
        String head = slash < 0 ? key : key.substring(0, slash);
        try {
            return Integer.parseInt(head);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Flattens {@code roots} into the depth-first list of visible rows, expanding a node exactly
     * when its path key is in {@code expanded}. Pure: no mutation of the tree, so it is directly
     * testable against a fake {@link ChildSource}.
     */
    public static List<Row> flatten(
            final List<ObjectNode> roots, final Set<String> expanded, final ChildSource childSource) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < roots.size(); i++) {
            walk(roots.get(i), 0, Integer.toString(i), Set.of(), expanded, childSource, out);
        }
        return out;
    }

    private static void walk(
            final ObjectNode node,
            final int depth,
            final String key,
            final Set<Long> ancestors,
            final Set<String> expanded,
            final ChildSource childSource,
            final List<Row> out) {
        boolean expandable = node.expandable();
        boolean isOpen = expandable && expanded.contains(key);
        out.add(new Row(node, depth, expandable, isOpen, key));
        if (!isOpen) {
            return;
        }
        // §17.3: descend into ANY open expandable node. A map-entry node contributes no
        // ancestor id of its own — its fixed key/value children resolve against the SAME
        // path (its value would falsely flag the value child as a cycle) — while any other
        // expandable node is a reference whose object id joins the path.
        List<ObjectNode> kids = childSource.children(node, ancestors);
        Set<Long> childAncestors = ancestors;
        if (!node.isMapEntry() && node.value() instanceof Value.Ref ref) {
            Set<Long> withSelf = new HashSet<>(ancestors);
            withSelf.add(ref.objectId());
            childAncestors = withSelf;
        }
        for (int j = 0; j < kids.size(); j++) {
            walk(kids.get(j), depth + 1, key + "/" + j, childAncestors, expanded, childSource, out);
        }
    }
}
