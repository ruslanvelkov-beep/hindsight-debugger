// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

/**
 * Pure vertical-scroll arithmetic for the Source window (VIEWER.md §6.1).
 *
 * <p>The exact auto-follow / centering / clamping formulas are the kind of thing
 * that is easy to get subtly wrong (off-by-one at the top and bottom margins), so
 * they live here as side-effect-free functions that a test pins against the spec's
 * wording, rather than being buried in the render loop where only a live terminal
 * could exercise them.
 *
 * <p>All line numbers are 1-based (line 1 is the first file line); {@code top} is the
 * 1-based file line shown on the viewport's top row and {@code height} is the number
 * of visible text rows.
 */
public final class ScrollMath {

    /**
     * Rows of context kept above/below the highlighted line before the view scrolls (§6.1, raised
     * from 5 to 15 in §11.1). Because a Source window can be shorter than {@code 2*MARGIN+1},
     * {@link #autoFollow} never applies the full margin blindly — it caps it to an effective margin
     * so the top and bottom edge conditions can never cross and the view cannot oscillate.
     */
    public static final int MARGIN = 15;

    private ScrollMath() {}

    /**
     * The largest legal {@code top} for a file of {@code fileLines} lines in a {@code height}-row
     * viewport: {@code max(1, fileLines - height + 1)}, so the last file line pins to the bottom row
     * and line 1 only ever appears on the top row.
     */
    public static int maxTop(final int fileLines, final int height) {
        return Math.max(1, fileLines - height + 1);
    }

    /** Clamps {@code top} into {@code [1, maxTop(fileLines, height)]}. */
    public static int clampTop(final int top, final int fileLines, final int height) {
        int max = maxTop(fileLines, height);
        return Math.min(Math.max(top, 1), max);
    }

    /**
     * Auto-follow scroll on a cursor move (§6.1/§11.1): keeps the highlighted line {@code target}
     * at least an <em>effective</em> margin {@code m = min(MARGIN, max(0, (height-1)/2))} rows from
     * either edge, nudging {@code top} only as far as needed, then clamps. Capping the margin to
     * {@code (height-1)/2} guarantees the top condition ({@code target < top + m}) and the bottom
     * condition ({@code target > top + height - 1 - m}) can never both fire on a stable position,
     * so a window shorter than {@code 2*MARGIN+1} rows follows smoothly instead of oscillating.
     * Used when the displayed file is unchanged.
     *
     * @param top current top line (1-based)
     * @param target the highlighted line to keep in view (1-based)
     * @param height visible row count
     * @param fileLines total lines in the file
     * @return the new top line
     */
    public static int autoFollow(final int top, final int target, final int height, final int fileLines) {
        int m = Math.min(MARGIN, Math.max(0, (height - 1) / 2));
        int t = top;
        if (target < t + m) {
            t = target - m;
        }
        if (target > t + height - 1 - m) {
            t = target - (height - 1) + m;
        }
        return clampTop(t, fileLines, height);
    }

    /**
     * Centering scroll (§6.1): places {@code target} at the middle of the viewport, clamped. Used
     * when the displayed file changes (a frame or thread switch) so the reader is not left staring
     * at an unrelated top-of-file.
     *
     * @param target the highlighted line to centre (1-based)
     * @param height visible row count
     * @param fileLines total lines in the file
     * @return the new top line
     */
    public static int center(final int target, final int height, final int fileLines) {
        return clampTop(target - height / 2, fileLines, height);
    }
}
