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

/** Tests the regex filter + cross-class seq merge + cap of the instance browser (VIEWER.md §13.2). */
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
}
