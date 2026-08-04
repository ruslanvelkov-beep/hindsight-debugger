// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.viewer.engine.ObjectNode;
import io.hindsight.viewer.engine.TestObjectNodes;
import io.hindsight.viewer.engine.Value;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests the tree flattening/navigation of VIEWER.md §6.4/§6.5 against a fake
 * {@link TreeView.ChildSource}, so the traversal, path keying and ancestor-id
 * threading — including the §17.3 map-entry rules (descend into ANY expandable
 * node; an entry contributes no ancestor id) — are verified without a terminal
 * or an engine.
 */
final class TreeViewTest {

    private static ObjectNode ref(final String label, final long id) {
        return ObjectNode.of(label, new Value.Ref(id, "#" + id));
    }

    private static ObjectNode leaf(final String label, final String rendered) {
        return ObjectNode.of(label, new Value.Primitive(rendered));
    }

    /** A child source returning a fixed object graph and recording the ancestor set per node id. */
    private static final class FakeSource implements TreeView.ChildSource {
        final Map<Long, List<ObjectNode>> byId = new HashMap<>();
        final Map<Long, Set<Long>> ancestorsSeen = new HashMap<>();

        @Override
        public List<ObjectNode> children(final ObjectNode node, final Set<Long> ancestorIds) {
            long id = ((Value.Ref) node.value()).objectId();
            ancestorsSeen.put(id, Set.copyOf(ancestorIds));
            return byId.getOrDefault(id, List.of());
        }
    }

    private FakeSource graph() {
        FakeSource cs = new FakeSource();
        cs.byId.put(100L, List.of(leaf("x = 1", "1"), ref("y = #200", 200)));
        cs.byId.put(200L, List.of(leaf("z = 2", "2")));
        return cs;
    }

    @Test
    void inlineWrapperNodeShowsItsValueAndIsNotOpenable() {
        // §20.1/§20.2: a scalar-wrapper reference comes back non-expandable with its value inlined in
        // the display; ObjectNode.of honours the flag, so the Locals tree shows the value WITHOUT an
        // openable node. A non-expandable Ref must never be flattened open or descended into.
        ObjectNode wrapper = ObjectNode.of(
                "counter = AtomicInteger#10  [ 42  0x0000002A ]",
                new Value.Ref(10, "AtomicInteger#10  [ 42  0x0000002A ]", false));
        assertFalse(wrapper.expandable());

        FakeSource cs = graph();
        List<TreeView.Row> flat = TreeView.flatten(List.of(wrapper), Set.of("0"), cs);
        assertEquals(1, flat.size(), "a non-expandable wrapper never expands, even if its key is 'open'");
        assertFalse(flat.get(0).expandable());
        assertTrue(flat.get(0).node().label().contains("[ 42  0x0000002A ]"), "value shown inline");

        TreeView tree = new TreeView(cs);
        tree.setRoots(List.of(wrapper));
        tree.expandOrDescend(); // Enter / → on the wrapper: a no-op, no children revealed
        assertEquals(1, tree.rows().size());
        assertFalse(tree.rows().get(0).expanded());
    }

    @Test
    void collapsedShowsOnlyRoots() {
        FakeSource cs = graph();
        List<ObjectNode> roots = List.of(ref("a = #100", 100), leaf("b = 42", "42"));
        List<TreeView.Row> rows = TreeView.flatten(roots, Set.of(), cs);
        assertEquals(2, rows.size());
        assertEquals("0", rows.get(0).key());
        assertEquals("1", rows.get(1).key());
        assertTrue(rows.get(0).expandable());
        assertFalse(rows.get(1).expandable());
    }

    @Test
    void expandedRevealsChildrenWithPathKeys() {
        FakeSource cs = graph();
        List<ObjectNode> roots = List.of(ref("a = #100", 100), leaf("b = 42", "42"));
        List<TreeView.Row> rows = TreeView.flatten(roots, Set.of("0"), cs);
        assertEquals(List.of("0", "0/0", "0/1", "1"), rows.stream().map(TreeView.Row::key).toList());
        assertEquals(1, rows.get(1).depth());
        assertEquals(1, rows.get(2).depth());
    }

    @Test
    void ancestorIdsThreadDownThePath() {
        FakeSource cs = graph();
        List<ObjectNode> roots = List.of(ref("a = #100", 100));
        TreeView.flatten(roots, Set.of("0", "0/1"), cs);
        // #100 is a root: no ancestors. #200 is under #100: its ancestor set is {100}.
        assertEquals(Set.of(), cs.ancestorsSeen.get(100L));
        assertEquals(Set.of(100L), cs.ancestorsSeen.get(200L));
    }

    @Test
    void instanceExpandDescendCollapseAndParent() {
        FakeSource cs = graph();
        TreeView tv = new TreeView(cs);
        tv.setRoots(List.of(ref("a = #100", 100), leaf("b = 42", "42")));

        assertEquals(2, tv.rows().size());
        tv.expandOrDescend();                       // expand root 0
        assertEquals(4, tv.rows().size());

        tv.moveDown(1);                             // select "0/0"
        assertEquals("0/0", tv.rows().get(tv.selection()).key());
        assertEquals(0, tv.selectedRootIndex());

        tv.moveDown(1);                             // select "0/1" (a ref)
        tv.expandOrDescend();                       // expand it
        assertEquals(5, tv.rows().size());
        assertEquals("0/1/0", tv.rows().get(3).key());

        tv.collapseOrParent();                      // collapse "0/1"
        assertEquals(4, tv.rows().size());
        tv.collapseOrParent();                      // now move to parent "0"
        assertEquals("0", tv.rows().get(tv.selection()).key());
    }

    @Test
    void clearExpansionResetsView() {
        FakeSource cs = graph();
        TreeView tv = new TreeView(cs);
        tv.setRoots(List.of(ref("a = #100", 100)));
        tv.expandOrDescend();
        assertEquals(3, tv.rows().size());
        tv.clearExpansion();
        assertEquals(1, tv.rows().size());
        assertEquals(0, tv.selection());
    }

    /** A child source keyed by node LABEL (map-entry nodes share values, not ids). */
    private static final class LabelSource implements TreeView.ChildSource {
        final Map<String, List<ObjectNode>> byLabel = new HashMap<>();
        final Map<String, Set<Long>> ancestorsSeen = new HashMap<>();

        @Override
        public List<ObjectNode> children(final ObjectNode node, final Set<Long> ancestorIds) {
            ancestorsSeen.put(node.label(), Set.copyOf(ancestorIds));
            return byLabel.getOrDefault(node.label(), List.of());
        }
    }

    @Test
    void descendsIntoExpandableNonRefNodes() {
        // §17.3: a map entry with PRIMITIVE key/value is expandable although its value is
        // no Ref — the walk must still fetch and show its children (the old walk did not).
        LabelSource cs = new LabelSource();
        ObjectNode entry = TestObjectNodes.mapEntry(
                "1 -> 2", new Value.Primitive("1"), new Value.Primitive("2"));
        cs.byLabel.put("1 -> 2", List.of(leaf("key = 1", "1"), leaf("value = 2", "2")));

        List<TreeView.Row> rows = TreeView.flatten(List.of(entry), Set.of("0"), cs);
        assertEquals(List.of("0", "0/0", "0/1"), rows.stream().map(TreeView.Row::key).toList());
        assertTrue(rows.get(0).expandable());
        assertTrue(rows.get(0).expanded());
        assertEquals(Set.of(), cs.ancestorsSeen.get("1 -> 2"));
    }

    @Test
    void mapEntryAddsNoAncestorIdButRefChildrenStillDo() {
        // Root #100 holds an entry whose value is #200; the entry expands to its key/value
        // pair. The ENTRY must add no ancestor id (its children resolve against {100} only,
        // NOT {100, 200} — else the "value = #200" child would look like a cycle), while the
        // Ref child "value = #200" adds its own id for ITS children as before.
        LabelSource cs = new LabelSource();
        ObjectNode entry = TestObjectNodes.mapEntry(
                "k -> #200", new Value.Primitive("k"), new Value.Ref(200, "#200"));
        cs.byLabel.put("a = #100", List.of(entry));
        cs.byLabel.put("k -> #200", List.of(
                leaf("key = k", "k"), ObjectNode.of("value = #200", new Value.Ref(200, "#200"))));
        cs.byLabel.put("value = #200", List.of(ref("w = #300", 300)));
        cs.byLabel.put("w = #300", List.of(leaf("z = 9", "9")));

        List<TreeView.Row> rows = TreeView.flatten(
                List.of(ref("a = #100", 100)), Set.of("0", "0/0", "0/0/1", "0/0/1/0"), cs);

        assertEquals(
                List.of("0", "0/0", "0/0/0", "0/0/1", "0/0/1/0", "0/0/1/0/0"),
                rows.stream().map(TreeView.Row::key).toList());
        assertEquals(Set.of(100L), cs.ancestorsSeen.get("k -> #200"), "entry sees the root's id");
        assertEquals(Set.of(100L), cs.ancestorsSeen.get("value = #200"),
                "the entry added NO ancestor id of its own");
        assertEquals(Set.of(100L, 200L), cs.ancestorsSeen.get("w = #300"),
                "a Ref node still adds its id for its children");
    }

    @Test
    void viewportScrollDragsTheSelectionAlongTheEdge() {
        // §27.1: the 2-arg scrollBy scrolls PAST the selection, dragging it along the nearest
        // viewport edge — so repeated Ctrl-U/Ctrl-N never stick (ensureVisible then has nothing
        // to undo). 6 leaf roots, viewport of 3 rows.
        TreeView tree = new TreeView((node, ancestors) -> List.of());
        tree.setRoots(List.of(leaf("a", "1"), leaf("b", "2"), leaf("c", "3"),
                leaf("d", "4"), leaf("e", "5"), leaf("f", "6")));
        assertEquals(0, tree.selection());

        tree.scrollBy(1, 3); // viewport now rows 1..3; selection 0 dragged to the top edge
        assertEquals(1, tree.scroll());
        assertEquals(1, tree.selection());
        tree.scrollBy(2, 3); // rows 3..5 — the max offset for 6 rows
        assertEquals(3, tree.scroll());
        assertEquals(3, tree.selection());
        tree.scrollBy(5, 3); // clamped at the bottom
        assertEquals(3, tree.scroll());
        tree.ensureVisible(3, 6);
        assertEquals(3, tree.scroll(), "ensureVisible has nothing to undo after a dragged scroll");
        tree.scrollBy(-10, 3); // and back to the top, selection dragged into the viewport
        assertEquals(0, tree.scroll());
        assertEquals(2, tree.selection(), "selection dragged to the bottom edge of the viewport");
    }
}
