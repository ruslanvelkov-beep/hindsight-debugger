// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry of the viewer's fixed screen layout (VIEWER.md §5, §12.1/§12.2): a left
 * Source panel, a right column of four stacked bordered windows, and two reserved
 * bottom rows for the status and help lines.
 *
 * <p>The math is pulled out of the renderer into pure static functions
 * ({@link #sourceWidth(int)}, {@link #rightHeights(int)}) so the exact clamping and
 * weight-normalisation rules from §5 can be unit-tested without a terminal. All
 * coordinates are 0-based cells; a {@link Rect} is a half-open box a window paints
 * inside (its own single-line border included).
 */
public final class Layout {

    /** Number of stacked windows in the right column (Threads, Stack, Locals, Records — §12.1). */
    public static final int RIGHT_WINDOWS = 4;

    /** Minimum width, in columns, the right column is guaranteed (§5). */
    public static final int MIN_RIGHT_WIDTH = 34;

    /** Height weights of the four right windows before normalisation (§12.2). */
    private static final int[] WEIGHTS = {5, 5, 6, 5};

    private static final int WEIGHT_TOTAL = 5 + 5 + 6 + 5;

    /** Index of the Locals window, which absorbs the rounding remainder (§5). */
    private static final int LOCALS = 2;

    /** A rectangular region on the screen, in 0-based cell coordinates. */
    public record Rect(int col, int row, int width, int height) {}

    private final Rect source;
    private final List<Rect> right;
    private final int statusRow;
    private final int helpRow;

    private Layout(final Rect source, final List<Rect> right, final int statusRow, final int helpRow) {
        this.source = source;
        this.right = right;
        this.statusRow = statusRow;
        this.helpRow = helpRow;
    }

    /**
     * Computes the full layout for a {@code cols × rows} screen. Assumes the caller has already
     * gated on the {@code 80×24} minimum (§1); it still produces sane, non-overlapping rectangles
     * for smaller sizes so a mid-resize frame never crashes the renderer.
     *
     * @param cols terminal width in columns
     * @param rows terminal height in rows
     * @return the computed geometry
     */
    public static Layout compute(final int cols, final int rows) {
        int available = Math.max(1, rows - 2);
        int sw = sourceWidth(cols);
        int rw = Math.max(0, cols - sw);
        Rect src = new Rect(0, 0, sw, available);

        int[] hs = rightHeights(available);
        List<Rect> rights = new ArrayList<>(RIGHT_WINDOWS);
        int y = 0;
        for (int i = 0; i < RIGHT_WINDOWS; i++) {
            rights.add(new Rect(sw, y, rw, hs[i]));
            y += hs[i];
        }
        return new Layout(src, List.copyOf(rights), rows - 2, rows - 1);
    }

    /**
     * The Source panel width: {@code max(40, cols*13/20)} (≈65%), clamped down so the right
     * column keeps at least {@link #MIN_RIGHT_WIDTH} columns (§5).
     */
    public static int sourceWidth(final int cols) {
        int w = Math.max(40, cols * 13 / 20);
        if (cols - w < MIN_RIGHT_WIDTH) {
            w = cols - MIN_RIGHT_WIDTH;
        }
        return Math.min(Math.max(w, 1), Math.max(cols, 1));
    }

    /**
     * Distributes {@code available} rows across the four right windows using the §12.2 weights,
     * giving every window at least 3 rows (border included) and handing the rounding remainder
     * to Locals; if the flooring plus the 3-row minimum overshoots (only possible far below the
     * 24-row minimum), rows are trimmed from the tallest windows so the total always fits.
     *
     * @param available rows available to the right column (the screen height minus the 2 info rows)
     * @return the four window heights, top-to-bottom, summing to {@code available}
     */
    public static int[] rightHeights(final int available) {
        int[] h = new int[RIGHT_WINDOWS];
        int sum = 0;
        for (int i = 0; i < RIGHT_WINDOWS; i++) {
            h[i] = Math.max(3, available * WEIGHTS[i] / WEIGHT_TOTAL);
            sum += h[i];
        }
        int diff = available - sum;
        if (diff > 0) {
            h[LOCALS] += diff;
        } else {
            while (diff < 0) {
                int tallest = -1;
                for (int i = 0; i < RIGHT_WINDOWS; i++) {
                    if (h[i] > 3 && (tallest < 0 || h[i] > h[tallest])) {
                        tallest = i;
                    }
                }
                if (tallest < 0) {
                    break;
                }
                h[tallest]--;
                diff++;
            }
        }
        return h;
    }

    /** The Source panel rectangle (left of the right column, above the info rows). */
    public Rect source() {
        return source;
    }

    /** The four right-column window rectangles, top-to-bottom. */
    public List<Rect> right() {
        return right;
    }

    /** 0-based row index of the transient status line (§8). */
    public int statusRow() {
        return statusRow;
    }

    /** 0-based row index of the context-sensitive help line (§8). */
    public int helpRow() {
        return helpRow;
    }
}
