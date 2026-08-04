// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure §16.4/§19.4 in-file search logic: the case-insensitive one-per-line occurrence
 * table, the "current occurrence on edit" selection (first at/after the cursor line, wrapping),
 * the §19.4 non-cyclic from-current-line motion ({@link InFileSearch#nextFrom}), the two-press
 * boundary-invert-then-wrap state machine ({@link InFileSearch#step}) and the fixed-width field's
 * tail scroll ({@link InFileSearch#visibleInput}).
 *
 * <p>The v1.8 cyclic {@code next}/{@code prev} and the right-padded {@code field} helper were
 * removed here: §19.4 supersedes §16.4's cyclic motion (with {@code nextFrom}+{@code step}) and its
 * stretched field rendering (with the fixed-width tail-scrolling field), so their tests are
 * replaced by the ones below.
 */
final class InFileSearchTest {

    private static final List<String> LINES = List.of(
            "int total = 0;", // 1
            "  Foo foo = new Foo();", // 2
            "  total += foo.value;", // 3
            "return TOTAL_MAX;"); // 4  (contains "total" case-insensitively)

    @Test
    void occurrencesAreCaseInsensitiveOnePerLineAtFirstColumn() {
        List<int[]> occ = InFileSearch.occurrences(LINES, "total");
        assertEquals(3, occ.size());
        assertArrayEquals(new int[] {1, 5}, occ.get(0)); // "int total" -> "total" at col 5 of line 1
        assertArrayEquals(new int[] {3, 3}, occ.get(1)); // "  total" -> col 3 of line 3
        assertArrayEquals(new int[] {4, 8}, occ.get(2)); // "return TOTAL_MAX" -> col 8, case-insensitive
    }

    @Test
    void emptyOrNullQueryYieldsNoOccurrences() {
        assertTrue(InFileSearch.occurrences(LINES, "").isEmpty());
        assertTrue(InFileSearch.occurrences(LINES, null).isEmpty());
        assertTrue(InFileSearch.occurrences(null, "x").isEmpty());
        assertTrue(InFileSearch.occurrences(LINES, "zzz").isEmpty());
    }

    @Test
    void currentIndexPicksFirstAtOrAfterCursorWrapping() {
        List<int[]> occ = InFileSearch.occurrences(LINES, "total"); // lines 1,3,4
        assertEquals(0, InFileSearch.currentIndex(occ, 1)); // cursor on line 1 -> occ 0
        assertEquals(1, InFileSearch.currentIndex(occ, 2)); // cursor on line 2 -> next is line 3 (occ 1)
        assertEquals(1, InFileSearch.currentIndex(occ, 3)); // exactly on line 3 -> occ 1
        assertEquals(2, InFileSearch.currentIndex(occ, 4)); // line 4 -> occ 2
        assertEquals(0, InFileSearch.currentIndex(occ, 5)); // past the last -> wrap to occ 0
        assertEquals(-1, InFileSearch.currentIndex(List.of(), 1)); // no occurrences
    }

    // ---- §19.4 non-cyclic motion ----

    @Test
    void nextFromMovesStrictlyPastTheReferenceLineWithoutWrapping() {
        List<int[]> occ = InFileSearch.occurrences(LINES, "total"); // lines 1,3,4
        // forward: the first occurrence with line strictly greater than refLine.
        assertEquals(1, InFileSearch.nextFrom(occ, 1, true)); // after line 1 -> line 3 (idx 1)
        assertEquals(1, InFileSearch.nextFrom(occ, 2, true)); // after line 2 -> line 3 (idx 1)
        assertEquals(2, InFileSearch.nextFrom(occ, 3, true)); // after line 3 -> line 4 (idx 2)
        assertEquals(InFileSearch.BOUNDARY, InFileSearch.nextFrom(occ, 4, true)); // none below -> boundary
        // reverse: the last occurrence with line strictly less than refLine.
        assertEquals(1, InFileSearch.nextFrom(occ, 4, false)); // before line 4 -> line 3 (idx 1)
        assertEquals(0, InFileSearch.nextFrom(occ, 3, false)); // before line 3 -> line 1 (idx 0)
        assertEquals(InFileSearch.BOUNDARY, InFileSearch.nextFrom(occ, 1, false)); // none above -> boundary
        // empty occurrences are always a boundary.
        assertEquals(InFileSearch.BOUNDARY, InFileSearch.nextFrom(List.of(), 2, true));
        assertEquals(InFileSearch.BOUNDARY, InFileSearch.nextFrom(null, 2, false));
    }

    // ---- §19.4 boundary-invert-then-wrap state machine ----

    @Test
    void stepMovesFromCurrentLineWhenAnOccurrenceExists() {
        List<int[]> occ = InFileSearch.occurrences(LINES, "total"); // lines 1,3,4
        // disarmed forward from line 1 -> move to idx 1, still disarmed.
        InFileSearch.Step s = InFileSearch.step(occ, 1, InFileSearch.Dir.FORWARD, null);
        assertEquals(1, s.index());
        assertNull(s.armed());
        // disarmed reverse from line 4 -> move to idx 1, still disarmed.
        s = InFileSearch.step(occ, 4, InFileSearch.Dir.REVERSE, null);
        assertEquals(1, s.index());
        assertNull(s.armed());
    }

    @Test
    void stepArmsTheBoundaryOnTheFirstPressThenWrapsOnTheSecond() {
        List<int[]> occ = InFileSearch.occurrences(LINES, "total"); // lines 1,3,4
        // forward at the last line: no occurrence below -> ARM forward, do NOT move.
        InFileSearch.Step first = InFileSearch.step(occ, 4, InFileSearch.Dir.FORWARD, null);
        assertEquals(InFileSearch.BOUNDARY, first.index(), "boundary press does not move");
        assertEquals(InFileSearch.Dir.FORWARD, first.armed(), "the invert is armed forward");
        // second forward press while armed forward: WRAP to the first occurrence, disarm.
        InFileSearch.Step second = InFileSearch.step(occ, 4, InFileSearch.Dir.FORWARD, InFileSearch.Dir.FORWARD);
        assertEquals(0, second.index(), "wraps to the first occurrence");
        assertNull(second.armed(), "the invert is cleared after the wrap");

        // reverse is symmetric against the top of file.
        InFileSearch.Step top = InFileSearch.step(occ, 1, InFileSearch.Dir.REVERSE, null);
        assertEquals(InFileSearch.BOUNDARY, top.index());
        assertEquals(InFileSearch.Dir.REVERSE, top.armed());
        InFileSearch.Step wrap = InFileSearch.step(occ, 1, InFileSearch.Dir.REVERSE, InFileSearch.Dir.REVERSE);
        assertEquals(2, wrap.index(), "reverse wraps to the last occurrence");
        assertNull(wrap.armed());
    }

    @Test
    void stepDirectionChangeClearsTheArmedFlag() {
        List<int[]> occ = InFileSearch.occurrences(LINES, "total"); // lines 1,3,4
        // armed forward (sitting on the last line 4), then a REVERSE press: clear + move up.
        InFileSearch.Step s = InFileSearch.step(occ, 4, InFileSearch.Dir.REVERSE, InFileSearch.Dir.FORWARD);
        assertEquals(1, s.index(), "direction change moves in the new direction");
        assertNull(s.armed(), "and clears the pending-boundary flag");
        // a direction change with no occurrence in the new direction clears without moving/re-arming.
        List<int[]> single = InFileSearch.occurrences(LINES, "new"); // line 2 only ("new Foo()")
        InFileSearch.Step none = InFileSearch.step(single, 2, InFileSearch.Dir.REVERSE, InFileSearch.Dir.FORWARD);
        assertEquals(InFileSearch.BOUNDARY, none.index(), "no earlier occurrence: no move");
        assertNull(none.armed(), "still clears the flag (colour restored), no re-arm");
    }

    @Test
    void stepOnAnEmptyOccurrenceTableNeverMovesOrArms() {
        InFileSearch.Step s = InFileSearch.step(List.of(), 3, InFileSearch.Dir.FORWARD, null);
        assertEquals(InFileSearch.BOUNDARY, s.index());
        assertNull(s.armed());
        // even if somehow armed, an empty table disarms without moving.
        InFileSearch.Step armed = InFileSearch.step(List.of(), 3, InFileSearch.Dir.REVERSE, InFileSearch.Dir.REVERSE);
        assertEquals(InFileSearch.BOUNDARY, armed.index());
        assertNull(armed.armed());
    }

    // ---- §20.7 visible-occurrence match spans ----

    @Test
    void matchColumnsMapsEveryOccurrenceToVisualColumnRanges() {
        // "int total = 0;" — one occurrence of "total" at raw index 4 (no tabs → visual col 4).
        List<int[]> m = InFileSearch.matchColumns("int total = 0;", "total", 4);
        assertEquals(1, m.size());
        assertArrayEquals(new int[] {4, 9}, m.get(0)); // [start, endExclusive)
    }

    @Test
    void matchColumnsIsCaseInsensitiveAndFindsAllHits() {
        // case-insensitive: "TOTAL" at raw index 7.
        assertArrayEquals(
                new int[] {7, 12}, InFileSearch.matchColumns("return TOTAL_MAX;", "total", 4).get(0));
        // multiple non-overlapping hits on one line: "abcabc" / "abc" -> {0,3} and {3,6}.
        List<int[]> two = InFileSearch.matchColumns("abcabc", "abc", 4);
        assertEquals(2, two.size());
        assertArrayEquals(new int[] {0, 3}, two.get(0));
        assertArrayEquals(new int[] {3, 6}, two.get(1));
        // non-overlapping advance: "aaa" / "aa" -> a single {0,2}.
        List<int[]> once = InFileSearch.matchColumns("aaa", "aa", 4);
        assertEquals(1, once.size());
        assertArrayEquals(new int[] {0, 2}, once.get(0));
    }

    @Test
    void regexMatchColumnsHighlightsRegexMatchesAndToleratesBadInput() {
        // §22.2: the F filter is a regex, so metacharacter queries highlight their matched span.
        // "Foo.*" over "FooBar.java" (case-insensitive) matches "FooBar.java" -> {0,11}.
        assertArrayEquals(
                new int[] {0, 11}, InFileSearch.regexMatchColumns("FooBar.java", "Foo.*", 4).get(0));
        // multiple matches: "a" over "banana" -> three single-column spans.
        assertEquals(3, InFileSearch.regexMatchColumns("banana", "a", 4).size());
        // invalid regex -> no spans, no throw; a zero-width match (empty alt) -> no spans, no loop.
        assertEquals(List.of(), InFileSearch.regexMatchColumns("x", "[", 4));
        assertEquals(List.of(), InFileSearch.regexMatchColumns("x", "y*", 4));
    }

    @Test
    void matchColumnsAccountsForTabExpansion() {
        // A leading tab expands to 4 visual columns, so "total" starts at visual col 4 (raw index 1).
        List<int[]> m = InFileSearch.matchColumns("\ttotal", "total", 4);
        assertEquals(1, m.size());
        assertArrayEquals(new int[] {4, 9}, m.get(0));
    }

    @Test
    void matchColumnsEmptyForNoMatchOrEmptyInputs() {
        assertTrue(InFileSearch.matchColumns("int total = 0;", "zzz", 4).isEmpty());
        assertTrue(InFileSearch.matchColumns("int total = 0;", "", 4).isEmpty());
        assertTrue(InFileSearch.matchColumns("int total = 0;", null, 4).isEmpty());
        assertTrue(InFileSearch.matchColumns(null, "x", 4).isEmpty());
    }

    // ---- §19.4 fixed-width field tail scroll ----

    @Test
    void visibleInputShowsTheWholeQueryOrItsTail() {
        assertEquals("abc", InFileSearch.visibleInput("abc", 28)); // fits: shown whole
        assertEquals("", InFileSearch.visibleInput("", 28));
        // exactly the width: whole query, no scroll.
        assertEquals("abcde", InFileSearch.visibleInput("abcde", 5));
        // longer than the width: the TAIL of length inputWidth (keeps the caret end in view).
        assertEquals("cde", InFileSearch.visibleInput("abcde", 3));
        assertEquals("e", InFileSearch.visibleInput("abcde", 1));
        // degenerate inputs.
        assertEquals("", InFileSearch.visibleInput("abc", 0));
        assertEquals("", InFileSearch.visibleInput(null, 5));
    }

    // ---- §21.2 search-direction arrow / field prefix ----

    @Test
    void directionArrowAndPrefixReflectTheDirection() {
        assertEquals('↓', InFileSearch.directionArrow(false), "forward is down");
        assertEquals('↑', InFileSearch.directionArrow(true), "reverse is up");
        assertEquals("[ Search ↓: ", InFileSearch.searchPrefix(false)); // §22.6: space before the arrow
        assertEquals("[ Search ↑: ", InFileSearch.searchPrefix(true));
        // ARROW_OFFSET indexes the arrow within the prefix, in either direction.
        assertEquals('↓', InFileSearch.searchPrefix(false).charAt(InFileSearch.ARROW_OFFSET));
        assertEquals('↑', InFileSearch.searchPrefix(true).charAt(InFileSearch.ARROW_OFFSET));
    }
}
