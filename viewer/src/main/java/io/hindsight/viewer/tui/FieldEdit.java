// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

/**
 * A single-line text field with a caret (VIEWER.md §22.4): the field's {@code text} plus a
 * {@code caret} index in {@code [0, text.length()]}. Immutable — every edit returns a new
 * {@code FieldEdit} — so the fiddly mid-string insert/delete and edge tests are unit-testable
 * without lanterna. Backs the {@code /} browser's regex and {@code thread:} fields (§22.4) and the
 * {@code V} window's name/{@code thread:} fields (§22.11); the owning window keeps one caret per
 * field and crosses between fields on {@link #atStart()}/{@link #atEnd()} edges.
 *
 * <p>The compact constructor normalises a {@code null} text to {@code ""} and clamps the caret into
 * range, so callers can build one straight from a persisted {@code StringBuilder} and a stored caret
 * that may momentarily be out of range (e.g. after the text shrank).
 */
public record FieldEdit(String text, int caret) {

    public FieldEdit {
        String t = text == null ? "" : text;
        text = t;
        caret = Math.max(0, Math.min(caret, t.length()));
    }

    /** Inserts {@code c} at the caret, advancing the caret past it. */
    public FieldEdit insert(final char c) {
        return new FieldEdit(text.substring(0, caret) + c + text.substring(caret), caret + 1);
    }

    /** Deletes the character before the caret (Backspace); a no-op at the start. */
    public FieldEdit backspace() {
        if (caret == 0) {
            return this;
        }
        return new FieldEdit(text.substring(0, caret - 1) + text.substring(caret), caret - 1);
    }

    /** Moves the caret one column left (clamped at the start). */
    public FieldEdit left() {
        return caret == 0 ? this : new FieldEdit(text, caret - 1);
    }

    /** Moves the caret one column right (clamped at the end). */
    public FieldEdit right() {
        return caret >= text.length() ? this : new FieldEdit(text, caret + 1);
    }

    /** Whether the caret is at the left-most position (used for §22.4 edge crossing back). */
    public boolean atStart() {
        return caret == 0;
    }

    /** Whether the caret is at the right-most position (used for §22.4 edge crossing forward). */
    public boolean atEnd() {
        return caret >= text.length();
    }
}
