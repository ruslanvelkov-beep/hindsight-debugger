// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import java.util.List;

/**
 * Pure lexical finder for a method's <em>declaration</em> line (VIEWER.md §18.3): when a
 * recorded frame is line-less (enter/exit-only — no {@code LINE} records), the Source
 * highlight sits on the method declaration instead of a body line that never executed.
 * Terminal-free so the scan is unit-testable.
 */
public final class DeclLine {

    /** How far above the body's first line the upward scan looks (§18.3: at most 30 lines). */
    static final int MAX_SCAN = 30;

    private DeclLine() {}

    /**
     * Finds the 1-based declaration line of {@code methodName} by scanning {@code lines}
     * upward from {@code min(bodyFirstLine, lines.size())} for at most {@value #MAX_SCAN}
     * lines (§18.3). A line matches when its text contains the method name — for a
     * constructor ({@code <init>}) the class's simple name — immediately followed by
     * optional whitespace and {@code '('}; lines whose first non-blank character is
     * {@code '@'} (annotations) and comment-only lines (first non-blank text starting
     * {@code //} or {@code *}) are skipped, which together with the {@code '('} requirement
     * guards against the name appearing in a nearby comment. When nothing matches (name
     * unknown, multi-line signature split before the {@code '('}, scan budget exhausted)
     * the fallback is {@code bodyFirstLine} itself.
     *
     * @param lines the shown file's lines, first line at index 0
     * @param methodName the frame's bare method name; {@code <init>} selects the constructor rule
     * @param simpleClassName the frame's simple class name, used for constructors
     * @param bodyFirstLine the 1-based line the frame reports (the site's first body line)
     * @return the 1-based declaration line, or {@code bodyFirstLine} when none is found
     */
    public static int find(
            final List<String> lines,
            final String methodName,
            final String simpleClassName,
            final int bodyFirstLine) {
        String name = "<init>".equals(methodName) ? simpleClassName : methodName;
        if (lines == null || name == null || name.isEmpty()) {
            return bodyFirstLine;
        }
        int start = Math.min(bodyFirstLine, lines.size());
        int stop = Math.max(1, start - (MAX_SCAN - 1));
        for (int l = start; l >= stop; l--) {
            String text = lines.get(l - 1);
            if (skip(text)) {
                continue;
            }
            if (containsNameBeforeParen(text, name)) {
                return l;
            }
        }
        return bodyFirstLine;
    }

    /** §18.3 skip rule: annotation lines ({@code @} first) and comment-only lines ({@code //} or {@code *}). */
    private static boolean skip(final String text) {
        String t = text.strip();
        return t.startsWith("@") || t.startsWith("//") || t.startsWith("*");
    }

    /** Whether {@code text} contains {@code name} immediately followed by optional whitespace and {@code '('}. */
    private static boolean containsNameBeforeParen(final String text, final String name) {
        int from = 0;
        int at;
        while ((at = text.indexOf(name, from)) >= 0) {
            int p = at + name.length();
            while (p < text.length() && Character.isWhitespace(text.charAt(p))) {
                p++;
            }
            if (p < text.length() && text.charAt(p) == '(') {
                return true;
            }
            from = at + 1;
        }
        return false;
    }
}
