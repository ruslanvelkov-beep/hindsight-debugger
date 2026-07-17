// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure logic for the Source Code in-file search (VIEWER.md §16.4): case-insensitive
 * substring matching over the shown file's lines (one occurrence per matching line at
 * its first column), the cyclic next/previous index arithmetic, the "current occurrence
 * on edit" selection rule (first at/after the cursor line, wrapping to the top), and the
 * search-field string. Kept terminal-free so the occurrence table and the cyclic indices
 * — the easy-to-fumble parts — are unit-testable without lanterna.
 */
public final class InFileSearch {

    private InFileSearch() {}

    /**
     * Every line containing {@code query} (case-insensitive substring), as {@code {line, col}}
     * 1-based pairs — {@code col} is the first occurrence's column on that line. An empty or
     * {@code null} query (or {@code null} lines) yields an empty list, so an empty query clears
     * the match set.
     *
     * @param lines the shown file's lines (0-based)
     * @param query the search text
     * @return the occurrences, ascending by line, each {@code {line, col}} 1-based
     */
    public static List<int[]> occurrences(final List<String> lines, final String query) {
        if (lines == null || query == null || query.isEmpty()) {
            return List.of();
        }
        String needle = query.toLowerCase(Locale.ROOT);
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null) {
                continue;
            }
            int col = line.toLowerCase(Locale.ROOT).indexOf(needle);
            if (col >= 0) {
                out.add(new int[] {i + 1, col + 1});
            }
        }
        return List.copyOf(out);
    }

    /**
     * The §16.4 "current occurrence on edit" index: the first occurrence whose line is at/after
     * {@code cursorLine}, wrapping to the top (index 0) when none is at/after it. {@code -1} when
     * there are no occurrences.
     *
     * @param occurrences the {@link #occurrences} table (ascending by line)
     * @param cursorLine the current source cursor line (1-based)
     * @return the index into {@code occurrences}, or {@code -1} when empty
     */
    public static int currentIndex(final List<int[]> occurrences, final int cursorLine) {
        if (occurrences == null || occurrences.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < occurrences.size(); i++) {
            if (occurrences.get(i)[0] >= cursorLine) {
                return i;
            }
        }
        return 0; // wrap to the top
    }

    /**
     * The next occurrence index, cyclic (past the last wraps to the first). {@code -1} when
     * {@code size <= 0}; from an unset current ({@code cur < 0}) the first index.
     *
     * @param cur the current index (may be {@code -1})
     * @param size the occurrence count
     * @return the next index
     */
    public static int next(final int cur, final int size) {
        if (size <= 0) {
            return -1;
        }
        if (cur < 0) {
            return 0;
        }
        return (cur + 1) % size;
    }

    /**
     * The previous occurrence index, cyclic (before the first wraps to the last). {@code -1} when
     * {@code size <= 0}; from an unset current ({@code cur < 0}) the last index.
     *
     * @param cur the current index (may be {@code -1})
     * @param size the occurrence count
     * @return the previous index
     */
    public static int prev(final int cur, final int size) {
        if (size <= 0) {
            return -1;
        }
        if (cur < 0) {
            return size - 1;
        }
        return (cur - 1 + size) % size;
    }

    /**
     * The §16.4 search-field text: {@code [ Search: <query>]} right-padded with spaces to exactly
     * {@code width} columns (truncated when longer). {@code width <= 0} yields the empty string.
     *
     * @param query the current query ({@code null} treated as empty)
     * @param width the field width in columns
     * @return a string of exactly {@code max(0, width)} characters
     */
    public static String field(final String query, final int width) {
        if (width <= 0) {
            return "";
        }
        String text = "[ Search: " + (query == null ? "" : query) + "]";
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        StringBuilder b = new StringBuilder(width);
        b.append(text);
        while (b.length() < width) {
            b.append(' ');
        }
        return b.toString();
    }
}
