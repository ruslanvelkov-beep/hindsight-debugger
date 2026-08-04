// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests the pure screen-geometry math of VIEWER.md §5. */
final class LayoutTest {

    @Test
    void sourceWidthClampsRightColumn() {
        assertEquals(46, Layout.sourceWidth(80));   // 52 desired, clamped so right >= 34
        assertEquals(65, Layout.sourceWidth(100));   // 65 desired, right = 35 ok
        assertEquals(78, Layout.sourceWidth(120));
        assertEquals(130, Layout.sourceWidth(200));
    }

    @Test
    void sourceWidthNeverStarvesRightColumn() {
        for (int cols = 80; cols <= 400; cols++) {
            int sw = Layout.sourceWidth(cols);
            assertTrue(cols - sw >= Layout.MIN_RIGHT_WIDTH, "right column too narrow at cols=" + cols);
        }
    }

    @Test
    void rightHeightsSumToAvailableAndFavourLocals() {
        int[] h = Layout.rightHeights(22);
        assertEquals(22, h[0] + h[1] + h[2] + h[3]);
        assertEquals(7, h[2]); // 6 by weight + the rounding remainder went to Locals
        for (int v : h) {
            assertTrue(v >= 3, "each window >= 3 rows");
        }
    }

    @Test
    void rightHeightsScaleProportionally() {
        int[] h = Layout.rightHeights(42);
        assertEquals(42, h[0] + h[1] + h[2] + h[3]);
        assertEquals(10, h[0]);
        assertEquals(10, h[1]);
        assertEquals(12, h[2]);
        assertEquals(10, h[3]);
    }

    @Test
    void computeStacksWindowsWithoutOverlap() {
        Layout layout = Layout.compute(80, 24);
        assertEquals(new Layout.Rect(0, 0, 46, 22), layout.source());
        List<Layout.Rect> r = layout.right();
        assertEquals(4, r.size());
        int y = 0;
        for (Layout.Rect rect : r) {
            assertEquals(46, rect.col());
            assertEquals(34, rect.width());
            assertEquals(y, rect.row());
            y += rect.height();
        }
        assertEquals(22, y); // right column fills exactly the main area
        assertEquals(22, layout.statusRow());
        assertEquals(23, layout.helpRow());
    }
}
