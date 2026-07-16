// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.viewer.engine.ObjectNode;
import io.hindsight.viewer.engine.Value;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests the tree flattening/navigation of VIEWER.md §6.4/§6.5 against a fake
 * {@link TreeView.ChildSource}, so the traversal, path keying and ancestor-id
 * threading are verified without a terminal or an engine.
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
}
