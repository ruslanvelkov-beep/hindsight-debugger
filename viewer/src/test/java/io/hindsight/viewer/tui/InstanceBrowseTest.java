// SPDX-License-Identifier: Apache-2.0
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
import java.util.function.Function;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;

/**
 * Tests the regex filter + cross-class seq merge + cap of the instance browser (VIEWER.md
 * §13.2) and the {@code <regex>#<id>} / {@code #<id>} exact-id query forms (§14.1).
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
        InstanceBrowse.Result res = InstanceBrowse.filter(SITES, instances(), "alpha", InstanceBrowse.CAP);
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
        InstanceBrowse.Result res = InstanceBrowse.filter(SITES, instances(), "alpha", 2);
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
}
