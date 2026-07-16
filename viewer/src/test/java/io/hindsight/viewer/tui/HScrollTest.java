// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Tests the per-window horizontal-scroll row clipping of VIEWER.md §11.4. */
final class HScrollTest {

    @Test
    void zeroOffsetPadsToWidth() {
        assertEquals("abc  ", HScroll.clip("abc", 0, 5));
    }

    @Test
    void offsetDropsLeadingColumns() {
        assertEquals("cdefg", HScroll.clip("abcdefghij", 2, 5));
        assertEquals("cde", HScroll.clip("abcde", 2, 3));
    }

    @Test
    void offsetPastEndYieldsBlankOfWidth() {
        assertEquals("     ", HScroll.clip("abc", 10, 5));
    }

    @Test
    void truncatesToWidthWhenLonger() {
        assertEquals("abcd", HScroll.clip("abcdefgh", 0, 4));
    }

    @Test
    void negativeOffsetTreatedAsZero() {
        assertEquals("abc  ", HScroll.clip("abc", -4, 5));
    }

    @Test
    void nonPositiveWidthIsEmpty() {
        assertEquals("", HScroll.clip("abc", 0, 0));
        assertEquals("", HScroll.clip("abc", 1, -3));
    }

    @Test
    void nullRowBecomesBlank() {
        assertEquals("   ", HScroll.clip(null, 2, 3));
    }
}
