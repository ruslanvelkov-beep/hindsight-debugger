// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import com.googlecode.lanterna.TextColor;
import io.hindsight.viewer.highlight.JavaHighlighter;

/**
 * The viewer's colour scheme (VIEWER.md §6): the single place that maps syntax
 * {@link JavaHighlighter.Style}s and UI roles to lanterna {@link TextColor}s.
 *
 * <p>Only the standard 16 ANSI colours are used (no RGB), so the scheme renders
 * identically on a plain xterm, an SSH session and a rich emulator — the viewer is
 * a terminal tool and must not assume truecolour. The choices favour readability of
 * code over decoration: comments dimmed, string/number/keyword each distinct, and
 * the two current-line backgrounds (exec vs. merely-selected frame) chosen to keep
 * foreground syntax legible on top.
 */
public final class Palette {

    /** Default foreground for ordinary code and UI text. */
    public static final TextColor FG = TextColor.ANSI.WHITE;

    /** Default background (terminal default). */
    public static final TextColor BG = TextColor.ANSI.DEFAULT;

    /** Dim colour for the line-number gutter and de-emphasised chrome. */
    public static final TextColor DIM = TextColor.ANSI.BLACK_BRIGHT;

    /** Border/title colour of the focused window (paired with bold). */
    public static final TextColor FOCUS = TextColor.ANSI.YELLOW_BRIGHT;

    /** Border/title colour of unfocused windows. */
    public static final TextColor UNFOCUS = TextColor.ANSI.WHITE;

    /** Background of the exec (innermost) current line — the brightest highlight (§6.1). */
    public static final TextColor EXEC_LINE_BG = TextColor.ANSI.BLUE;

    /** Background of a selected outer frame's call-site line — a dimmer highlight (§6.1). */
    public static final TextColor FRAME_LINE_BG = TextColor.ANSI.BLACK_BRIGHT;

    /** Background of the selected row in a list/tree window. */
    public static final TextColor SELECTION_BG = TextColor.ANSI.CYAN;

    /** Foreground of the selected row in a list/tree window. */
    public static final TextColor SELECTION_FG = TextColor.ANSI.BLACK;

    /** Background of a modal dialog. */
    public static final TextColor DIALOG_BG = TextColor.ANSI.BLACK;

    /** Foreground for error/placeholder text. */
    public static final TextColor ERROR = TextColor.ANSI.RED_BRIGHT;

    private Palette() {}

    /** Maps a syntax style to its foreground colour (§6.1). */
    public static TextColor syntax(final JavaHighlighter.Style style) {
        return switch (style) {
            case KEYWORD -> TextColor.ANSI.MAGENTA;
            case STRING, CHAR -> TextColor.ANSI.GREEN;
            case NUMBER -> TextColor.ANSI.CYAN_BRIGHT;
            case COMMENT -> TextColor.ANSI.BLACK_BRIGHT;
            case ANNOTATION -> TextColor.ANSI.YELLOW;
            case PUNCTUATION -> TextColor.ANSI.WHITE;
            case PLAIN -> FG;
        };
    }
}
