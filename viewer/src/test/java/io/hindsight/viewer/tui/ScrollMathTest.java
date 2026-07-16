// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests the Source-window scroll arithmetic of VIEWER.md §6.1 with the §11.1 margin of 15. */
final class ScrollMathTest {

    @Test
    void marginIsFifteen() {
        assertEquals(15, ScrollMath.MARGIN);
    }

    @Test
    void maxTopPinsLastLineToBottom() {
        assertEquals(81, ScrollMath.maxTop(100, 20));
        assertEquals(1, ScrollMath.maxTop(10, 20)); // file shorter than viewport
    }

    @Test
    void clampTopBounds() {
        assertEquals(1, ScrollMath.clampTop(0, 100, 20));
        assertEquals(1, ScrollMath.clampTop(-50, 100, 20));
        assertEquals(81, ScrollMath.clampTop(200, 100, 20));
        assertEquals(40, ScrollMath.clampTop(40, 100, 20));
    }

    @Test
    void autoFollowKeepsTopMarginTallWindow() {
        // Height 40 → effective margin = min(15, 19) = 15: full 15-row margin above the target.
        assertEquals(1, ScrollMath.autoFollow(1, 1, 40, 200));
        assertEquals(7, ScrollMath.autoFollow(20, 22, 40, 200));
    }

    @Test
    void autoFollowKeepsBottomMarginTallWindow() {
        // Target past the bottom margin scrolls to L-(H-1)+m with m=15.
        assertEquals(6, ScrollMath.autoFollow(1, 30, 40, 200));
    }

    @Test
    void autoFollowSmallWindowUsesEffectiveMargin() {
        // Height 10 → effective margin = min(15, (10-1)/2) = 4, so the edges never cross.
        assertEquals(1, ScrollMath.autoFollow(1, 1, 10, 100));
        assertEquals(8, ScrollMath.autoFollow(10, 12, 10, 100));
        // Idempotent once the target sits inside the effective margins (no oscillation).
        assertEquals(8, ScrollMath.autoFollow(8, 12, 10, 100));
    }

    @Test
    void autoFollowTinyWindowNeverOscillates() {
        // Height 3 → effective margin = 1: top and bottom conditions meet exactly, never cross.
        int first = ScrollMath.autoFollow(5, 5, 3, 100);
        int second = ScrollMath.autoFollow(first, 5, 3, 100);
        assertEquals(first, second);
        assertTrue(first >= 1);
    }

    @Test
    void centerPlacesTargetMidViewport() {
        assertEquals(40, ScrollMath.center(50, 20, 100));
        assertEquals(1, ScrollMath.center(1, 20, 100));
        assertEquals(81, ScrollMath.center(100, 20, 100));
    }
}
