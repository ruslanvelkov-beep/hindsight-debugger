// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.viewer.engine.Call;
import io.hindsight.viewer.engine.MethodSite;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.IntFunction;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;

/** Tests the regex filter + cross-site seq merge + cap of the method browser (VIEWER.md §11.7). */
final class MethodBrowseTest {

    private static final List<MethodSite> SITES = List.of(
            new MethodSite(1, "com.foo.Alpha", "run", "()V", 2),
            new MethodSite(2, "com.foo.Beta", "run", "()V", 1),
            new MethodSite(3, "com.bar.Gamma", "compute", "()I", 1));

    private static IntFunction<List<Call>> calls() {
        Map<Integer, List<Call>> m = new HashMap<>();
        m.put(1, List.of(new Call(10, 1, 5), new Call(30, 1, 9)));
        m.put(2, List.of(new Call(20, 2, 3)));
        m.put(3, List.of(new Call(5, 1, 2)));
        return sid -> m.getOrDefault(sid, List.of());
    }

    @Test
    void matchStringAndDisplay() {
        assertEquals("com.foo.Alpha.run", MethodBrowse.matchString(SITES.get(0)));
        assertEquals("Alpha.run", MethodBrowse.display(SITES.get(0)));
        assertEquals(".", MethodBrowse.matchString(new MethodSite(9, null, null, "", 0)));
        assertEquals("?.?", MethodBrowse.display(new MethodSite(9, null, null, "", 0)));
    }

    @Test
    void flattensMatchingSitesAscendingBySeq() {
        MethodBrowse.Result res = MethodBrowse.filter(SITES, calls(), "run", MethodBrowse.CAP);
        assertEquals(3, res.rows().size());
        assertEquals(List.of(10L, 20L, 30L), res.rows().stream().map(MethodBrowse.Row::seq).toList());
        assertEquals("Alpha.run", res.rows().get(0).display());
        assertEquals("Beta.run", res.rows().get(1).display());
        assertEquals("Alpha.run", res.rows().get(2).display());
        assertEquals(3, res.totalMatches());
        assertFalse(res.truncated());
    }

    @Test
    void capTruncatesButTotalCountsAll() {
        MethodBrowse.Result res = MethodBrowse.filter(SITES, calls(), "run", 2);
        assertEquals(2, res.rows().size());
        assertEquals(List.of(10L, 20L), res.rows().stream().map(MethodBrowse.Row::seq).toList());
        assertEquals(3, res.totalMatches());
        assertTrue(res.truncated());
    }

    @Test
    void regexIsCaseInsensitiveAndUsesFind() {
        assertEquals(2, MethodBrowse.filter(SITES, calls(), "ALPHA", MethodBrowse.CAP).rows().size());
        // A regex anchored to the binary package still matches via find().
        MethodBrowse.Result bar = MethodBrowse.filter(SITES, calls(), "^com\\.bar", MethodBrowse.CAP);
        assertEquals(1, bar.rows().size());
        assertEquals("Gamma.compute", bar.rows().get(0).display());
    }

    @Test
    void noMatchesYieldsEmpty() {
        MethodBrowse.Result res = MethodBrowse.filter(SITES, calls(), "nope", MethodBrowse.CAP);
        assertTrue(res.rows().isEmpty());
        assertEquals(0, res.totalMatches());
        assertFalse(res.truncated());
    }

    @Test
    void invalidRegexThrows() {
        assertThrows(PatternSyntaxException.class,
                () -> MethodBrowse.filter(SITES, calls(), "(", MethodBrowse.CAP));
    }

    // ---- §21.6 thread filter ----

    @Test
    void threadFilterKeepsOnlyThatThreadsCalls() {
        // "run" matches Alpha.run (t1: seq10, seq30) and Beta.run (t2: seq20).
        MethodBrowse.Result t1 = MethodBrowse.filter(SITES, calls(), "run", MethodBrowse.CAP, OptionalInt.of(1));
        assertEquals(List.of(10L, 30L), t1.rows().stream().map(MethodBrowse.Row::seq).toList());
        assertEquals(2, t1.totalMatches(), "count reflects the thread-filtered rows");
        assertFalse(t1.truncated());
        MethodBrowse.Result t2 = MethodBrowse.filter(SITES, calls(), "run", MethodBrowse.CAP, OptionalInt.of(2));
        assertEquals(List.of(20L), t2.rows().stream().map(MethodBrowse.Row::seq).toList());
        assertEquals(1, t2.totalMatches());
        assertTrue(MethodBrowse.filter(SITES, calls(), "run", MethodBrowse.CAP, OptionalInt.of(99))
                .rows().isEmpty());
    }

    @Test
    void emptyThreadFilterMatchesTheFourArgOverload() {
        MethodBrowse.Result a = MethodBrowse.filter(SITES, calls(), "run", MethodBrowse.CAP);
        MethodBrowse.Result b = MethodBrowse.filter(SITES, calls(), "run", MethodBrowse.CAP, OptionalInt.empty());
        assertEquals(a.rows().size(), b.rows().size());
        assertEquals(a.totalMatches(), b.totalMatches());
        assertEquals(a.rows().stream().map(MethodBrowse.Row::seq).toList(),
                b.rows().stream().map(MethodBrowse.Row::seq).toList());
    }
}
