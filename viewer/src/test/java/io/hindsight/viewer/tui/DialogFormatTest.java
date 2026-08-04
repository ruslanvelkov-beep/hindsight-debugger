// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the `/` dialog's result-list text formats: the §15.4 right-aligned row numbers
 * with the matching child padding, and the §15.3 count line (absent ↔ empty string when
 * nothing is shown, cap note when truncated).
 */
final class DialogFormatTest {

    @Test
    void numbersAreRightAlignedToTheWidestRow() {
        assertEquals("1. ", DialogFormat.number(0, 9));
        assertEquals("9. ", DialogFormat.number(8, 9));
        assertEquals(" 1. ", DialogFormat.number(0, 10));
        assertEquals("10. ", DialogFormat.number(9, 10));
        assertEquals("  1. ", DialogFormat.number(0, 500));
        assertEquals("500. ", DialogFormat.number(499, 500));
        // Degenerate totals still produce a stable single-digit prefix.
        assertEquals("1. ", DialogFormat.number(0, 0));
    }

    @Test
    void childPadMatchesNumberWidthExactly() {
        assertEquals(DialogFormat.number(0, 9).length(), DialogFormat.childPad(9).length());
        assertEquals("   ", DialogFormat.childPad(9));
        assertEquals("    ", DialogFormat.childPad(10));
        assertEquals("     ", DialogFormat.childPad(500));
        assertEquals(3, DialogFormat.numberWidth(1));
        assertEquals(5, DialogFormat.numberWidth(500));
    }

    @Test
    void countLineFollowsTheSpecText() {
        // §15.3: absent (empty) when there are no results…
        assertEquals("", DialogFormat.countLine(0, 0));
        assertEquals("", DialogFormat.countLine(42, 0));
        // …"<N> results" when everything is shown…
        assertEquals("1 results", DialogFormat.countLine(1, 1));
        assertEquals("42 results", DialogFormat.countLine(42, 42));
        // …and the cap note (replacing the old bottom note) when truncated.
        assertEquals("showing first 500 of 501", DialogFormat.countLine(501, 500));
        assertEquals("showing first 500 of 12345", DialogFormat.countLine(12345, 500));
    }
}
