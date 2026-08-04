// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure text helpers for showing the recording directory (VIEWER.md §17.6): the
 * middle-ellipsis abbreviation of the directory NAME drawn on the Threads window's top
 * border, and the hard wrapping of the full ABSOLUTE path shown by the {@code Ctrl-D}
 * popup. Terminal-free so the exact strings are unit-testable.
 */
public final class RecordingName {

    /** The §17.6 on-border budget: names longer than this render as first-10 {@code …} last-10. */
    public static final int MAX_BORDER_NAME = 21;

    private RecordingName() {}

    /**
     * Abbreviates {@code name} to at most {@code max} characters with a middle {@code …}:
     * an over-long name keeps its first {@code (max-1)/2} and last {@code max-1-(max-1)/2}
     * characters — for the spec's {@code max = 21} that is exactly first-10 + {@code …} +
     * last-10 (§17.6). A name that already fits is returned unchanged.
     *
     * @param name the directory name ({@code null} treated as empty)
     * @param max the character budget; {@code <= 0} yields an empty string
     * @return the (possibly abbreviated) name, never longer than {@code max}
     */
    public static String abbreviate(final String name, final int max) {
        String n = name == null ? "" : name;
        if (max <= 0) {
            return "";
        }
        if (n.length() <= max) {
            return n;
        }
        if (max == 1) {
            return "…";
        }
        int left = (max - 1) / 2;
        int right = max - 1 - left;
        return n.substring(0, left) + "…" + n.substring(n.length() - right);
    }

    /**
     * Hard-wraps {@code text} into lines of at most {@code width} characters (§17.6 popup:
     * the full absolute path, wrapped across lines as needed). No word awareness — a path
     * has no natural spaces to break on, so a plain character wrap is both predictable and
     * dense. Always returns at least one line (an empty text yields one empty line).
     *
     * @param text the text to wrap ({@code null} treated as empty)
     * @param width the maximum line width; values {@code < 1} are treated as 1
     * @return the wrapped lines, in order
     */
    public static List<String> wrap(final String text, final int width) {
        String t = text == null ? "" : text;
        int w = Math.max(1, width);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < t.length(); i += w) {
            out.add(t.substring(i, Math.min(t.length(), i + w)));
        }
        if (out.isEmpty()) {
            out.add("");
        }
        return out;
    }
}
