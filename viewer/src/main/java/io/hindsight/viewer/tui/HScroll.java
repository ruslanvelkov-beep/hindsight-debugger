// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

/**
 * Per-window horizontal-scroll clipping (VIEWER.md §11.4): every window keeps its
 * own column offset {@code hcol} and each rendered inner row is shown through the
 * half-open window {@code [hcol, hcol+width)} rather than being permanently
 * truncated at the right border.
 *
 * <p>Factored out of the renderer as a pure function so the (easy to fumble) offset
 * and padding arithmetic — dropping the hidden leading columns, then padding the
 * visible remainder to the full inner width so a selection background paints the
 * whole row — is unit-testable without a terminal.
 */
public final class HScroll {

    private HScroll() {}

    /**
     * The slice of {@code row} visible in a window whose horizontal offset is {@code hcol} and
     * whose inner width is {@code width}: the characters at columns {@code [hcol, hcol+width)},
     * right-padded with spaces to exactly {@code width} columns (so a shorter row still clears any
     * stale cells and a selection highlight covers the whole row).
     *
     * @param row the full logical row text ({@code null} treated as empty)
     * @param hcol columns hidden at the left (negative treated as 0)
     * @param width the window's inner width in columns
     * @return a string of exactly {@code max(0, width)} characters
     */
    public static String clip(final String row, final int hcol, final int width) {
        if (width <= 0) {
            return "";
        }
        String text = row == null ? "" : row;
        int start = Math.max(0, hcol);
        String sub = start >= text.length() ? "" : text.substring(start);
        if (sub.length() >= width) {
            return sub.substring(0, width);
        }
        StringBuilder b = new StringBuilder(width);
        b.append(sub);
        while (b.length() < width) {
            b.append(' ');
        }
        return b.toString();
    }
}
