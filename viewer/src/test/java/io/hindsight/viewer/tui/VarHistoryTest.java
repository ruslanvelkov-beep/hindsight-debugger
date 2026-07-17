// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the pure §16.1 variable-history helpers: the 75%-clamped window width, the three
 * title-scope forms, and the assembled title.
 */
final class VarHistoryTest {

    @Test
    void widthIs75PercentClampedIntoRange() {
        // 75% of the terminal width…
        assertEquals(60, VarHistory.width(80));
        assertEquals(90, VarHistory.width(120));
        assertEquals(150, VarHistory.width(200));
        // …but never wider than cols-2…
        assertTrue(VarHistory.width(80) <= 80 - 2);
        assertTrue(VarHistory.width(200) <= 200 - 2);
        // …and never narrower than 40.
        assertEquals(40, VarHistory.width(50)); // 50*3/4 = 37 -> clamped up to 40
    }

    @Test
    void scopeHasThreeForms() {
        // this-instance filter active with a real enclosing instance
        assertEquals("this Foo#42", VarHistory.scope(true, 42L, "Foo"));
        // filter active but unknown simple class renders "?"
        assertEquals("this ?#42", VarHistory.scope(true, 42L, null));
        // no enclosing this at all
        assertEquals("all instances (no enclosing this)", VarHistory.scope(false, 0L, "Foo"));
        assertEquals("all instances (no enclosing this)", VarHistory.scope(true, 0L, "Foo"));
        // an enclosing this exists but the filter is off
        assertEquals("all instances", VarHistory.scope(false, 42L, "Foo"));
    }

    @Test
    void titleCombinesCountAndScope() {
        assertEquals(
                "Variable history [v] — 7 entries · all instances",
                VarHistory.title(7, VarHistory.scope(false, 42L, "Foo")));
        assertEquals(
                "Variable history [v] — 0 entries · this Foo#42",
                VarHistory.title(0, VarHistory.scope(true, 42L, "Foo")));
    }
}
