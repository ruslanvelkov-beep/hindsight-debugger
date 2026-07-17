// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure §16.4 in-file search logic: the case-insensitive one-per-line occurrence
 * table, the "current occurrence on edit" selection (first at/after the cursor line, wrapping),
 * the cyclic next/previous indices, and the search-field string.
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

    @Test
    void nextAndPrevAreCyclic() {
        assertEquals(1, InFileSearch.next(0, 3));
        assertEquals(2, InFileSearch.next(1, 3));
        assertEquals(0, InFileSearch.next(2, 3)); // wrap forward
        assertEquals(2, InFileSearch.prev(0, 3)); // wrap backward
        assertEquals(0, InFileSearch.prev(1, 3));
        // from an unset current
        assertEquals(0, InFileSearch.next(-1, 3));
        assertEquals(2, InFileSearch.prev(-1, 3));
        // degenerate size
        assertEquals(-1, InFileSearch.next(0, 0));
        assertEquals(-1, InFileSearch.prev(0, 0));
    }

    @Test
    void fieldIsBracketedAndRightPaddedToWidth() {
        assertEquals("[ Search: abc]      ", InFileSearch.field("abc", 20));
        assertEquals("[ Search: ]", InFileSearch.field("", 11)); // exactly fits
        // longer than the field is truncated
        assertEquals("[ Search:", InFileSearch.field("abcdef", 9));
        // non-positive width is empty
        assertEquals("", InFileSearch.field("abc", 0));
        // width is exact
        assertEquals(20, InFileSearch.field("abc", 20).length());
    }
}
