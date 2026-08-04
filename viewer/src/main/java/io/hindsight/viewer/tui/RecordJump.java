// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import java.util.OptionalLong;

/**
 * State + pure input logic for the Records "jump to a record number" modal (VIEWER.md §16.3):
 * an accumulated decimal-digit buffer (the global {@code seq} the user is typing) and a
 * transient error line. Terminal-free — the {@code ViewerApp} owns rendering and the
 * {@code findSeq}/seek side effects — so the digit editing and the overflow-safe parse are
 * unit-testable without lanterna.
 *
 * <p>Only decimal digits are accepted (non-digits are dropped by {@link #appendDigit}); both
 * appending a digit and {@link #backspace} clear the error, so any further typing lets the user
 * retry after a "no record with seq N" miss.
 */
public final class RecordJump {

    private final StringBuilder digits = new StringBuilder();
    private String error = "";

    /** Appends {@code c} to the buffer when it is a decimal digit (else a no-op); clears the error. */
    public void appendDigit(final char c) {
        if (c >= '0' && c <= '9') {
            digits.append(c);
            error = "";
        }
    }

    /**
     * Replaces the buffer with {@code value}'s decimal digits and clears the error — the §22.13
     * {@code l} shortcut that fills in the last record's seq (a negative value fills {@code 0}).
     */
    public void setDigits(final long value) {
        digits.setLength(0);
        digits.append(Long.toString(Math.max(0L, value)));
        error = "";
    }

    /** Deletes the last digit (when any) and clears the error. */
    public void backspace() {
        if (digits.length() > 0) {
            digits.deleteCharAt(digits.length() - 1);
        }
        error = "";
    }

    /** Whether the buffer is empty. */
    public boolean isEmpty() {
        return digits.length() == 0;
    }

    /** The current digit buffer as text. */
    public String text() {
        return digits.toString();
    }

    /** The current error line (empty when none). */
    public String error() {
        return error;
    }

    /** Sets the error line (e.g. {@code no record with seq N}); {@code null} clears it. */
    public void setError(final String message) {
        this.error = message == null ? "" : message;
    }

    /**
     * The buffer parsed as a {@code long} seq: empty when nothing was typed, or when the digits
     * overflow a {@code long} (so a pathological input can never throw out of the input loop).
     *
     * @return the parsed seq, or empty
     */
    public OptionalLong parse() {
        if (digits.length() == 0) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(digits.toString()));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
