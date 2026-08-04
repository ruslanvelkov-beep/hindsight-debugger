// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.viewer.engine.ClassSite;
import io.hindsight.viewer.engine.Instance;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;

/**
 * Tests the regex filter + cross-class seq merge + cap of the instance browser (VIEWER.md
 * §13.2), the {@code <regex>#<id>} / {@code #<id>} exact-id query forms (§14.1), and the
 * §15.1 simple-name-by-default match rule (a query without {@code .}/{@code $} is matched
 * against simple class names only; one with them against the full binary name).
 */
final class InstanceBrowseTest {

    private static final ClassSite ALPHA = new ClassSite("com.foo.Alpha", 2);
    private static final ClassSite INNER = new ClassSite("com.foo.Alpha$Inner", 1);
    private static final ClassSite GAMMA = new ClassSite("com.bar.Gamma", 1);
    private static final List<ClassSite> SITES = List.of(ALPHA, INNER, GAMMA);

    private static Function<String, List<Instance>> instances() {
        Map<String, List<Instance>> m = new HashMap<>();
        m.put("com.foo.Alpha", List.of(new Instance(11, 10, 1, 5), new Instance(12, 30, 1, 9)));
        m.put("com.foo.Alpha$Inner", List.of(new Instance(13, 20, 2, 3)));
        m.put("com.bar.Gamma", List.of(new Instance(14, 5, 1, 2)));
        return cls -> m.getOrDefault(cls, List.of());
    }

    @Test
    void matchStringAndDisplay() {
        assertEquals("com.foo.Alpha", InstanceBrowse.matchString(ALPHA));
        assertEquals("", InstanceBrowse.matchString(new ClassSite(null, 0)));
        assertEquals("Alpha#11", InstanceBrowse.display(ALPHA, new Instance(11, 10, 1, 5)));
        assertEquals("Inner#13", InstanceBrowse.display(INNER, new Instance(13, 20, 2, 3)));
        assertEquals("?#7", InstanceBrowse.display(new ClassSite(null, 0), new Instance(7, 1, 1, 1)));
    }

    @Test
    void flattensMatchingClassesAscendingBySeq() {
        // "com.foo" contains a dot → full-binary-name matching (§15.1), so it spans
        // com.foo.Alpha AND com.foo.Alpha$Inner and exercises the cross-class merge.
        InstanceBrowse.Result res = InstanceBrowse.filter(SITES, instances(), "com.foo", InstanceBrowse.CAP);
        assertEquals(3, res.rows().size());
        assertEquals(List.of(10L, 20L, 30L), res.rows().stream().map(InstanceBrowse.Row::seq).toList());
        assertEquals("Alpha#11", res.rows().get(0).display());
        assertEquals("Inner#13", res.rows().get(1).display());
        assertEquals("Alpha#12", res.rows().get(2).display());
        assertEquals(11, res.rows().get(0).objectId());
        assertEquals(3, res.totalMatches());
        assertFalse(res.truncated());
    }

    @Test
    void capTruncatesButTotalCountsAll() {
        InstanceBrowse.Result res = InstanceBrowse.filter(SITES, instances(), "com.foo", 2);
        assertEquals(2, res.rows().size());
        assertEquals(List.of(10L, 20L), res.rows().stream().map(InstanceBrowse.Row::seq).toList());
        assertEquals(3, res.totalMatches());
        assertTrue(res.truncated());
    }

    @Test
    void regexIsCaseInsensitiveAndUsesFind() {
        assertEquals(1, InstanceBrowse.filter(SITES, instances(), "GAMMA", InstanceBrowse.CAP).rows().size());
        // Anchored regexes still work via find().
        InstanceBrowse.Result bar = InstanceBrowse.filter(SITES, instances(), "^com\\.bar", InstanceBrowse.CAP);
        assertEquals(1, bar.rows().size());
        assertEquals("Gamma#14", bar.rows().get(0).display());
        // A $-anchored match hits only the outer class, not its $Inner sibling.
        InstanceBrowse.Result outer = InstanceBrowse.filter(SITES, instances(), "Alpha$", InstanceBrowse.CAP);
        assertEquals(2, outer.rows().size());
        assertEquals(2, outer.totalMatches());
    }

    @Test
    void noMatchesYieldsEmpty() {
        InstanceBrowse.Result res = InstanceBrowse.filter(SITES, instances(), "nope", InstanceBrowse.CAP);
        assertTrue(res.rows().isEmpty());
        assertEquals(0, res.totalMatches());
        assertFalse(res.truncated());
    }

    @Test
    void invalidRegexThrows() {
        assertThrows(PatternSyntaxException.class,
                () -> InstanceBrowse.filter(SITES, instances(), "(", InstanceBrowse.CAP));
    }

    // ---- §14.1 instance search by id ----

    @Test
    void parseQuerySplitsIdForms() {
        assertEquals(new InstanceBrowse.Query("Bytes", 240L), InstanceBrowse.parseQuery("Bytes#240"));
        assertEquals(new InstanceBrowse.Query("", 240L), InstanceBrowse.parseQuery("#240"));
        // A # not followed by digits-only text stays part of the regex.
        assertEquals(new InstanceBrowse.Query("foo#bar", null), InstanceBrowse.parseQuery("foo#bar"));
        assertEquals(new InstanceBrowse.Query("#", null), InstanceBrowse.parseQuery("#"));
        // Only the LAST # can carry the id; the earlier # stays in the regex.
        assertEquals(new InstanceBrowse.Query("foo#12", 34L), InstanceBrowse.parseQuery("foo#12#34"));
        // An id that overflows a long cannot match any instance — the whole text is regex.
        assertEquals(new InstanceBrowse.Query("Bytes#99999999999999999999", null),
                InstanceBrowse.parseQuery("Bytes#99999999999999999999"));
        assertEquals(new InstanceBrowse.Query("plain", null), InstanceBrowse.parseQuery("plain"));
    }

    @Test
    void regexWithIdListsOnlyThatInstance() {
        // "alpha" matches Alpha AND Alpha$Inner; #12 exists only in Alpha.
        InstanceBrowse.Result res = InstanceBrowse.filter(SITES, instances(), "alpha#12", InstanceBrowse.CAP);
        assertEquals(1, res.rows().size());
        assertEquals("Alpha#12", res.rows().get(0).display());
        assertEquals(12, res.rows().get(0).objectId());
        assertEquals("com.foo.Alpha", res.rows().get(0).classBinary());
        assertEquals(1, res.totalMatches());
        assertFalse(res.truncated());
    }

    @Test
    void bareIdMatchesAnyClass() {
        InstanceBrowse.Result res = InstanceBrowse.filter(SITES, instances(), "#13", InstanceBrowse.CAP);
        assertEquals(1, res.rows().size());
        assertEquals("Inner#13", res.rows().get(0).display());
        assertEquals("com.foo.Alpha$Inner", res.rows().get(0).classBinary());
        assertEquals(1, res.totalMatches());
    }

    @Test
    void hashNotFollowedByDigitsStaysRegex() {
        // "Alpha#?$" is a plain regex (optional literal # then end): it matches com.foo.Alpha
        // but not com.foo.Alpha$Inner, and no id restriction applies.
        InstanceBrowse.Result res = InstanceBrowse.filter(SITES, instances(), "Alpha#?$", InstanceBrowse.CAP);
        assertEquals(2, res.rows().size());
        assertEquals("Alpha#11", res.rows().get(0).display());
        assertEquals("Alpha#12", res.rows().get(1).display());
        assertEquals(2, res.totalMatches());
    }

    @Test
    void idQueryWithNoMatchYieldsEmpty() {
        InstanceBrowse.Result byClass = InstanceBrowse.filter(SITES, instances(), "gamma#12", InstanceBrowse.CAP);
        assertTrue(byClass.rows().isEmpty());
        assertEquals(0, byClass.totalMatches());
        assertFalse(byClass.truncated());
        InstanceBrowse.Result byId = InstanceBrowse.filter(SITES, instances(), "#999", InstanceBrowse.CAP);
        assertTrue(byId.rows().isEmpty());
        assertEquals(0, byId.totalMatches());
        assertFalse(byId.truncated());
    }

    // ---- §15.1 simple-name-by-default matching ----

    private static final ClassSite VMS =
            new ClassSite("com.swirlds.state.merkle.VirtualMapStateImpl", 1);
    private static final ClassSite VMS_INNER =
            new ClassSite("com.swirlds.state.merkle.VirtualMapStateImpl$MerkleReadableStates", 1);
    private static final ClassSite BYTES = new ClassSite("com.hedera.pbj.runtime.io.buffer.Bytes", 2);
    // Simple name "Thing": reachable only through its PACKAGE text "com.bytes", never via "Bytes".
    private static final ClassSite DECOY = new ClassSite("com.bytes.pool.Thing", 1);
    private static final List<ClassSite> V15_SITES = List.of(VMS, VMS_INNER, BYTES, DECOY);

    private static Function<String, List<Instance>> v15Instances() {
        Map<String, List<Instance>> m = new HashMap<>();
        m.put(VMS.classBinary(), List.of(new Instance(1, 1, 1, 1)));
        m.put(VMS_INNER.classBinary(), List.of(new Instance(2, 2, 1, 2)));
        m.put(BYTES.classBinary(), List.of(new Instance(240, 3, 1, 3), new Instance(241, 4, 1, 4)));
        m.put(DECOY.classBinary(), List.of(new Instance(5, 5, 1, 5)));
        return cls -> m.getOrDefault(cls, List.of());
    }

    @Test
    void simpleSegmentCutsAtLastDotAndLastDollar() {
        assertEquals("C", InstanceBrowse.simpleSegment("a.b.C"));
        assertEquals("D", InstanceBrowse.simpleSegment("a.b.C$D"));
        assertEquals("E", InstanceBrowse.simpleSegment("C$D$E"));
        assertEquals("C", InstanceBrowse.simpleSegment("C"));
        assertEquals("", InstanceBrowse.simpleSegment("a.b.C$"));
    }

    @Test
    void isQualifiedAndMatchTarget() {
        assertFalse(InstanceBrowse.isQualified("VirtualMapStateImpl"));
        assertFalse(InstanceBrowse.isQualified(""));
        assertTrue(InstanceBrowse.isQualified("com.swirlds"));
        assertTrue(InstanceBrowse.isQualified("VirtualMapStateImpl$"));
        assertEquals("MerkleReadableStates", InstanceBrowse.matchTarget(VMS_INNER, false));
        assertEquals(VMS_INNER.classBinary(), InstanceBrowse.matchTarget(VMS_INNER, true));
        assertEquals("", InstanceBrowse.matchTarget(new ClassSite(null, 0), false));
    }

    @Test
    void unqualifiedQueryMatchesSimpleNamesOnly() {
        // The headline §15.1 case: an outer class's name must NOT drag in its inner classes.
        InstanceBrowse.Result outer =
                InstanceBrowse.filter(V15_SITES, v15Instances(), "VirtualMapStateImpl", InstanceBrowse.CAP);
        assertEquals(1, outer.rows().size());
        assertEquals("VirtualMapStateImpl#1", outer.rows().get(0).display());
        assertEquals(VMS.classBinary(), outer.rows().get(0).classBinary());
        // ...while the inner class is reachable by its own simple name...
        InstanceBrowse.Result inner =
                InstanceBrowse.filter(V15_SITES, v15Instances(), "MerkleReadableStates", InstanceBrowse.CAP);
        assertEquals(1, inner.rows().size());
        assertEquals(VMS_INNER.classBinary(), inner.rows().get(0).classBinary());
        // ...and the case-insensitive find() semantics still apply to the simple name.
        InstanceBrowse.Result ci =
                InstanceBrowse.filter(V15_SITES, v15Instances(), "virtualmapstate", InstanceBrowse.CAP);
        assertEquals(1, ci.rows().size());
        assertEquals(VMS.classBinary(), ci.rows().get(0).classBinary());
        // Package text never matches an unqualified query ("bytes" is not in "Thing").
        InstanceBrowse.Result bytes = InstanceBrowse.filter(V15_SITES, v15Instances(), "Bytes", InstanceBrowse.CAP);
        assertEquals(2, bytes.rows().size());
        assertEquals(BYTES.classBinary(), bytes.rows().get(0).classBinary());
        assertEquals(BYTES.classBinary(), bytes.rows().get(1).classBinary());
    }

    @Test
    void dollarQueryIsQualifiedIntent() {
        // "VirtualMapStateImpl$" contains '$' → matched against FULL binary names, where the
        // regex '$' is the end anchor: the outer class matches, its inner class does not.
        InstanceBrowse.Result res =
                InstanceBrowse.filter(V15_SITES, v15Instances(), "VirtualMapStateImpl$", InstanceBrowse.CAP);
        assertEquals(1, res.rows().size());
        assertEquals(VMS.classBinary(), res.rows().get(0).classBinary());
        // An escaped literal '\$' reaches the inner class through its full name.
        InstanceBrowse.Result literal = InstanceBrowse.filter(
                V15_SITES, v15Instances(), "VirtualMapStateImpl\\$Merkle", InstanceBrowse.CAP);
        assertEquals(1, literal.rows().size());
        assertEquals(VMS_INNER.classBinary(), literal.rows().get(0).classBinary());
    }

    @Test
    void dottedQueryMatchesFullBinaryName() {
        InstanceBrowse.Result res =
                InstanceBrowse.filter(V15_SITES, v15Instances(), "com.swirlds", InstanceBrowse.CAP);
        assertEquals(2, res.rows().size());
        assertEquals(VMS.classBinary(), res.rows().get(0).classBinary());
        assertEquals(VMS_INNER.classBinary(), res.rows().get(1).classBinary());
        // A dotted needle reaches package text, so the decoy IS found via "com.bytes".
        InstanceBrowse.Result decoy =
                InstanceBrowse.filter(V15_SITES, v15Instances(), "com.bytes", InstanceBrowse.CAP);
        assertEquals(1, decoy.rows().size());
        assertEquals(DECOY.classBinary(), decoy.rows().get(0).classBinary());
    }

    // ---- §21.6 thread filter ----

    @Test
    void threadFilterKeepsOnlyThatThreadsInstances() {
        // "com.foo" spans Alpha (t1: #11@10, #12@30) and Alpha$Inner (t2: #13@20).
        InstanceBrowse.Result t1 = InstanceBrowse.filter(
                SITES, instances(), "com.foo", InstanceBrowse.CAP, OptionalInt.of(1));
        assertEquals(List.of("Alpha#11", "Alpha#12"),
                t1.rows().stream().map(InstanceBrowse.Row::display).toList());
        assertEquals(2, t1.totalMatches(), "count reflects the thread-filtered rows");
        assertFalse(t1.truncated());
        InstanceBrowse.Result t2 = InstanceBrowse.filter(
                SITES, instances(), "com.foo", InstanceBrowse.CAP, OptionalInt.of(2));
        assertEquals(List.of("Inner#13"), t2.rows().stream().map(InstanceBrowse.Row::display).toList());
        assertEquals(1, t2.totalMatches());
        // A thread with no matching instances → empty.
        assertTrue(InstanceBrowse.filter(SITES, instances(), "com.foo", InstanceBrowse.CAP, OptionalInt.of(99))
                .rows().isEmpty());
    }

    @Test
    void emptyThreadFilterMatchesTheFourArgOverload() {
        InstanceBrowse.Result a = InstanceBrowse.filter(SITES, instances(), "com.foo", InstanceBrowse.CAP);
        InstanceBrowse.Result b = InstanceBrowse.filter(
                SITES, instances(), "com.foo", InstanceBrowse.CAP, OptionalInt.empty());
        assertEquals(a.rows().size(), b.rows().size());
        assertEquals(a.totalMatches(), b.totalMatches());
        assertEquals(a.rows().stream().map(InstanceBrowse.Row::display).toList(),
                b.rows().stream().map(InstanceBrowse.Row::display).toList());
    }

    @Test
    void threadFilterComposesWithIdQuery() {
        // #12 lives in Alpha on t1; filtering to t1 keeps it, t2 drops it.
        assertEquals(1, InstanceBrowse.filter(
                SITES, instances(), "alpha#12", InstanceBrowse.CAP, OptionalInt.of(1)).rows().size());
        assertTrue(InstanceBrowse.filter(
                SITES, instances(), "alpha#12", InstanceBrowse.CAP, OptionalInt.of(2)).rows().isEmpty());
    }

    @Test
    void idQueryClassPartUsesSimpleNameRule() {
        // Bytes#240 (§14.1 ∘ §15.1): the class part "Bytes" is unqualified → simple-name rule.
        InstanceBrowse.Result res =
                InstanceBrowse.filter(V15_SITES, v15Instances(), "Bytes#240", InstanceBrowse.CAP);
        assertEquals(1, res.rows().size());
        assertEquals("Bytes#240", res.rows().get(0).display());
        assertEquals(240, res.rows().get(0).objectId());
        assertEquals(1, res.totalMatches());
        // A qualified class part composes with the id form the same way.
        InstanceBrowse.Result qualified =
                InstanceBrowse.filter(V15_SITES, v15Instances(), "com.hedera.*#241", InstanceBrowse.CAP);
        assertEquals(1, qualified.rows().size());
        assertEquals(241, qualified.rows().get(0).objectId());
    }
}
