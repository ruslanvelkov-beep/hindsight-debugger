// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure logic for the Source Code in-file search (VIEWER.md §16.4, motion overhauled by §19.4):
 * case-insensitive substring matching over the shown file's lines (one occurrence per matching
 * line at its first column), the "current occurrence on edit" selection rule (first at/after the
 * cursor line, wrapping to the top), the §19.4 <em>non-cyclic</em> from-current-line motion
 * ({@link #nextFrom}), the two-press boundary-invert-then-wrap state machine ({@link #step}) and
 * the fixed-width field's tail scroll ({@link #visibleInput}). Kept terminal-free so these
 * easy-to-fumble parts are unit-testable without lanterna.
 */
public final class InFileSearch {

    /** {@link #nextFrom} sentinel: no occurrence exists in the requested direction (§19.4). */
    public static final int BOUNDARY = -1;

    /** The 0-based offset of the direction arrow within {@link #searchPrefix} (after {@code "[ Search "}). */
    public static final int ARROW_OFFSET = "[ Search ".length();

    /** Search motion direction (§19.4): forward (down the file) or reverse (up). */
    public enum Dir {
        FORWARD,
        REVERSE
    }

    /**
     * The §21.2 search-direction arrow drawn inside the field label: {@code '↓'} when the field
     * searches FORWARD (down the file, the default) or {@code '↑'} when REVERSE (up). {@code Tab}
     * flips the direction and hence this arrow; the field owns the direction (the old ↑/↓ and
     * Alt/Shift/Ctrl-Enter motion keys are gone).
     *
     * @param reverse whether the field is in the reverse (up) direction
     * @return the arrow glyph for the current direction
     */
    public static char directionArrow(final boolean reverse) {
        return reverse ? '↑' : '↓';
    }

    /**
     * The §21.2/§22.6 in-file search field prefix — everything up to (and including) the space
     * before the query input area: {@code "[ Search ↓: "} forward, {@code "[ Search ↑: "} reverse.
     * §22.6 inserts one space between {@code Search} and the direction arrow; the arrow sits at
     * {@link #ARROW_OFFSET}.
     *
     * @param reverse whether the field is in the reverse (up) direction
     * @return the field prefix ending in {@code ": "}
     */
    public static String searchPrefix(final boolean reverse) {
        return "[ Search " + directionArrow(reverse) + ": ";
    }

    /**
     * Outcome of one §19.4 motion press ({@link #step}): {@code index >= 0} means move the current
     * occurrence to that index and disarm the boundary; {@code index < 0} means do NOT move.
     * {@code armed} is the new pending-boundary direction — {@code null} disarms (normal colour),
     * a non-{@code null} value arms the whole-field colour inversion in that direction.
     *
     * @param index the occurrence index to move to, or {@code < 0} to stay put
     * @param armed the new pending-boundary direction, or {@code null} when disarmed
     */
    public record Step(int index, Dir armed) {}

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
     * The §19.4 non-cyclic motion index: the FIRST occurrence strictly after {@code refLine}
     * ({@code forward}) or the LAST occurrence strictly before it (reverse), or {@link #BOUNDARY}
     * when there is none in that direction (the caller then arms the §19.4 boundary invert).
     * Unlike the superseded cyclic {@code next}/{@code prev}, this never wraps.
     *
     * @param occurrences the {@link #occurrences} table (ascending by line)
     * @param refLine the reference line — the current occurrence's line, or {@code srcCursor} when
     *     no occurrence is current (1-based)
     * @param forward {@code true} for the next occurrence below, {@code false} for the one above
     * @return the occurrence index, or {@link #BOUNDARY} when none lies in that direction
     */
    public static int nextFrom(final List<int[]> occurrences, final int refLine, final boolean forward) {
        if (occurrences == null || occurrences.isEmpty()) {
            return BOUNDARY;
        }
        if (forward) {
            for (int i = 0; i < occurrences.size(); i++) {
                if (occurrences.get(i)[0] > refLine) {
                    return i;
                }
            }
            return BOUNDARY;
        }
        for (int i = occurrences.size() - 1; i >= 0; i--) {
            if (occurrences.get(i)[0] < refLine) {
                return i;
            }
        }
        return BOUNDARY;
    }

    /**
     * The §19.4 two-press "boundary invert, then wrap" state machine — a pure decision over the
     * occurrence table, the reference line, the requested direction {@code dir} and the currently
     * pending-boundary direction {@code armed} ({@code null} = disarmed). Rules:
     * <ul>
     *   <li>a SECOND same-direction press while armed in {@code dir} WRAPS — moves to the FIRST
     *       (forward) / LAST (reverse) occurrence and disarms;</li>
     *   <li>a DIRECTION CHANGE while armed clears the flag (restores colour) and does an ordinary
     *       move in the new direction when one exists, otherwise stays put without re-arming;</li>
     *   <li>an ordinary press while disarmed moves to {@link #nextFrom} when it exists, else ARMS
     *       the boundary invert in {@code dir} without moving;</li>
     *   <li>an empty occurrence table never moves and never arms.</li>
     * </ul>
     * The caller clears {@code armed} itself on any query edit (§19.4).
     *
     * @param occurrences the {@link #occurrences} table (ascending by line)
     * @param refLine the reference line (current occurrence's line, or the cursor when none)
     * @param dir the pressed direction
     * @param armed the pending-boundary direction before this press, or {@code null}
     * @return the {@link Step} to apply (move index and the new pending-boundary state)
     */
    public static Step step(final List<int[]> occurrences, final int refLine, final Dir dir, final Dir armed) {
        int size = occurrences == null ? 0 : occurrences.size();
        if (size == 0) {
            return new Step(BOUNDARY, null);
        }
        if (armed == dir) {
            return new Step(dir == Dir.FORWARD ? 0 : size - 1, null); // second press: wrap, disarm
        }
        int idx = nextFrom(occurrences, refLine, dir == Dir.FORWARD);
        if (armed != null) {
            return new Step(idx, null); // direction change: clear flag; move only if one exists
        }
        return idx != BOUNDARY ? new Step(idx, null) : new Step(BOUNDARY, dir); // arm on boundary
    }

    /**
     * The §20.7 visible-occurrence spans on one source line: every non-overlapping occurrence of
     * {@code query} (case-insensitive substring) mapped to VISUAL column ranges
     * {@code {startCol, endColExclusive}} — 0-based columns in the tab-expanded line, so the caller
     * can paint the matched cells regardless of tabs and horizontal scroll. An empty/{@code null}
     * query (or {@code null} line) yields an empty list. Unlike {@link #occurrences} (one hit per
     * line, for motion), this returns ALL hits on the line, for the in-body match highlight.
     *
     * @param line the raw source line (no trailing newline)
     * @param query the search text
     * @param tabWidth the tab stop width in columns (4 per §6.1), matching the render's expansion
     * @return the match spans, ascending, each {@code {startVisualCol, endVisualColExclusive}} 0-based
     */
    public static List<int[]> matchColumns(final String line, final String query, final int tabWidth) {
        if (line == null || query == null || query.isEmpty()) {
            return List.of();
        }
        int n = line.length();
        // The visual column at which each raw offset starts (visAt[n] = the line's total width).
        int[] visAt = new int[n + 1];
        int col = 0;
        for (int i = 0; i < n; i++) {
            visAt[i] = col;
            col = line.charAt(i) == '\t' && tabWidth > 0 ? ((col / tabWidth) + 1) * tabWidth : col + 1;
        }
        visAt[n] = col;
        String hay = line.toLowerCase(Locale.ROOT);
        String needle = query.toLowerCase(Locale.ROOT);
        List<int[]> out = new ArrayList<>();
        int from = 0;
        int idx;
        while ((idx = hay.indexOf(needle, from)) >= 0) {
            int end = idx + needle.length();
            out.add(new int[] {visAt[idx], visAt[end]});
            from = end; // non-overlapping: continue past this match
        }
        return List.copyOf(out);
    }

    /**
     * Like {@link #matchColumns} but matching {@code regex} (case-insensitive) — the §22.2 F-dialog
     * highlight, whose filter is itself a regex, so a metacharacter query (e.g. {@code Foo.*}) marks
     * its matched span rather than nothing. An invalid regex or a zero-width match yields no spans
     * (never throws, never loops). Empty matches are skipped so the scan always advances.
     */
    public static List<int[]> regexMatchColumns(final String line, final String regex, final int tabWidth) {
        if (line == null || regex == null || regex.isEmpty()) {
            return List.of();
        }
        final java.util.regex.Pattern pattern;
        try {
            pattern = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE);
        } catch (java.util.regex.PatternSyntaxException e) {
            return List.of();
        }
        int n = line.length();
        int[] visAt = new int[n + 1];
        int col = 0;
        for (int i = 0; i < n; i++) {
            visAt[i] = col;
            col = line.charAt(i) == '\t' && tabWidth > 0 ? ((col / tabWidth) + 1) * tabWidth : col + 1;
        }
        visAt[n] = col;
        List<int[]> out = new ArrayList<>();
        java.util.regex.Matcher m = pattern.matcher(line);
        int from = 0;
        while (from <= n && m.find(from)) {
            int s = m.start();
            int e = m.end();
            if (e > s) {
                out.add(new int[] {visAt[s], visAt[e]});
                from = e;
            } else {
                from = s + 1; // zero-width match: advance to avoid an infinite loop
            }
        }
        return List.copyOf(out);
    }

    /**
     * The §19.4 fixed-width field's visible input: the query itself when it fits, else its TAIL —
     * the last {@code inputWidth} characters — so a query longer than the input area scrolls to
     * keep its end (and the caret) in view. {@code null} query or {@code inputWidth <= 0} yields
     * the empty string.
     *
     * @param query the current query
     * @param inputWidth the fixed input-area width in columns
     * @return the substring to render in the input area (never longer than {@code inputWidth})
     */
    public static String visibleInput(final String query, final int inputWidth) {
        if (query == null || inputWidth <= 0) {
            return "";
        }
        return query.length() <= inputWidth ? query : query.substring(query.length() - inputWidth);
    }
}
