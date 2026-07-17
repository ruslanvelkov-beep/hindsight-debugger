// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

/**
 * Pure text formatting for the {@code /} dialog's result list (VIEWER.md §15.3/§15.4):
 * the {@code 1.}–{@code N.} row numbers (right-aligned so a three-digit list stays
 * columnar), the matching blank prefix that keeps unnumbered instance-tree child rows
 * indented under their numbered root, and the DIM result-count line rendered directly
 * under the query. Kept terminal-free so the exact strings are unit-testable.
 */
public final class DialogFormat {

    private DialogFormat() {}

    /** Width in cells of the {@link #number} prefix for a list of {@code total} rows. */
    public static int numberWidth(final int total) {
        return Integer.toString(Math.max(1, total)).length() + 2;
    }

    /**
     * The §15.4 row-number prefix for the 0-based {@code index} among {@code total} numbered
     * rows: {@code "1. "}, right-aligned to the widest number ({@code " 7. "} in a 10-row list).
     */
    public static String number(final int index, final int total) {
        int digits = Integer.toString(Math.max(1, total)).length();
        return String.format("%" + digits + "d. ", index + 1);
    }

    /**
     * A blank prefix of exactly {@link #numberWidth} cells: what an unnumbered child row of an
     * expanded instance tree carries instead of a number, keeping it indented under its root
     * (§15.4).
     */
    public static String childPad(final int total) {
        return " ".repeat(numberWidth(total));
    }

    /**
     * The §15.3 count line: empty (line absent) when nothing is shown, {@code showing first
     * <shown> of <total>} when the result list was capped, {@code <total> results} otherwise.
     */
    public static String countLine(final long total, final int shown) {
        if (shown <= 0) {
            return "";
        }
        if (total > shown) {
            return "showing first " + shown + " of " + total;
        }
        return total + " results";
    }
}
