// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import io.hindsight.viewer.highlight.JavaHighlighter;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a raw source line plus its syntax spans into a flat sequence of screen
 * cells (VIEWER.md §6.1): tabs expanded to 4-column stops and every visual column
 * carrying the highlight {@link JavaHighlighter.Style} of the character that
 * produced it.
 *
 * <p>Doing tab expansion here — after highlighting, which runs on the raw line —
 * is what lets the renderer scroll horizontally and colour correctly at the same
 * time: a tab that lands inside a string keeps the string's style for its filler
 * spaces, and horizontal scroll is a simple sublist of the produced cells. It is a
 * pure function (no terminal, no lanterna) so the column math is unit-testable.
 */
public final class SourceLayout {

    /** A single visual column: the glyph to draw and the highlight style to draw it in. */
    public record Cell(char ch, JavaHighlighter.Style style) {}

    private SourceLayout() {}

    /**
     * Expands the visual cells of {@code line}, with tabs advanced to the next multiple of
     * {@code tabWidth} and each cell tagged with the style of the span covering its source offset.
     * A cell's style defaults to {@link JavaHighlighter.Style#PLAIN} for any offset no span claims
     * (the highlighter tiles the whole line, so this only guards against a short/empty span list).
     *
     * @param line the raw source line (no trailing newline)
     * @param spans the line's highlight spans (ordered, non-overlapping half-open ranges)
     * @param tabWidth the tab stop width in columns (4 per §6.1)
     * @return the visual cells left-to-right
     */
    public static List<Cell> cells(
            final String line, final List<JavaHighlighter.Span> spans, final int tabWidth) {
        int n = line.length();
        JavaHighlighter.Style[] perChar = new JavaHighlighter.Style[n];
        for (JavaHighlighter.Span s : spans) {
            int start = Math.max(0, s.start());
            int end = Math.min(n, s.end());
            for (int i = start; i < end; i++) {
                perChar[i] = s.style();
            }
        }
        List<Cell> out = new ArrayList<>(n);
        int col = 0;
        for (int i = 0; i < n; i++) {
            char c = line.charAt(i);
            JavaHighlighter.Style style = perChar[i] != null ? perChar[i] : JavaHighlighter.Style.PLAIN;
            if (c == '\t') {
                int next = tabWidth <= 0 ? col + 1 : ((col / tabWidth) + 1) * tabWidth;
                while (col < next) {
                    out.add(new Cell(' ', style));
                    col++;
                }
            } else {
                out.add(new Cell(c, style));
                col++;
            }
        }
        return out;
    }

    /**
     * Convenience: the plain expanded-tab text of a line, no styles — handy for width math and
     * tests. Equivalent to concatenating {@link #cells}' characters.
     */
    public static String expandTabs(final String line, final int tabWidth) {
        StringBuilder b = new StringBuilder(line.length());
        int col = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\t') {
                int next = tabWidth <= 0 ? col + 1 : ((col / tabWidth) + 1) * tabWidth;
                while (col < next) {
                    b.append(' ');
                    col++;
                }
            } else {
                b.append(c);
                col++;
            }
        }
        return b.toString();
    }
}
