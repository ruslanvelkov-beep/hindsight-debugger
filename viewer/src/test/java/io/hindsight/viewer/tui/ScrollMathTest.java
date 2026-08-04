// SPDX-License-Identifier: GPL-3.0-or-later
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

    // ---- §13.1 edge-only list scrolling ----

    @Test
    void adjustScrollLeavesOffsetWhileSelectionVisible() {
        // Selection anywhere inside [scroll, scroll+height) must not move the view.
        assertEquals(20, ScrollMath.adjustScroll(20, 20, 5, 100));
        assertEquals(20, ScrollMath.adjustScroll(20, 22, 5, 100));
        assertEquals(20, ScrollMath.adjustScroll(20, 24, 5, 100));
    }

    @Test
    void adjustScrollFollowsSelectionOffEitherEdge() {
        assertEquals(19, ScrollMath.adjustScroll(20, 19, 5, 100)); // up: scroll to the selection
        assertEquals(21, ScrollMath.adjustScroll(20, 25, 5, 100)); // down: selection on bottom row
    }

    @Test
    void adjustScrollIsIncrementalNotRecomputedFromZero() {
        // The v1.1 bug: recomputing from 0 each frame pinned a moving-up selection to the
        // bottom row. From a persisted offset the selection walks up through the window and
        // the view moves only when the selection reaches the top edge.
        int scroll = ScrollMath.adjustScroll(0, 24, 5, 100); // jump to row 24 → view 20..24
        assertEquals(20, scroll);
        for (int sel = 23; sel >= 20; sel--) {
            scroll = ScrollMath.adjustScroll(scroll, sel, 5, 100);
            assertEquals(20, scroll); // selection climbs, view stays
        }
        assertEquals(19, ScrollMath.adjustScroll(scroll, 19, 5, 100)); // now the edge scrolls
    }

    @Test
    void adjustScrollClampsToListBounds() {
        assertEquals(0, ScrollMath.adjustScroll(-3, 0, 5, 100));
        assertEquals(95, ScrollMath.adjustScroll(200, 99, 5, 100));
        assertEquals(0, ScrollMath.adjustScroll(4, 0, 5, 3)); // fewer rows than the viewport
        assertEquals(0, ScrollMath.adjustScroll(7, 2, 0, 10)); // degenerate height
        assertEquals(0, ScrollMath.adjustScroll(0, 0, 5, 0)); // empty list
    }
}
