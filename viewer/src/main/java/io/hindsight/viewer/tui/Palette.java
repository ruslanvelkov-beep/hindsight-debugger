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
 * a terminal tool and must not assume truecolour. The two deliberate exceptions are
 * {@link #FRAME_LINE_BG} (VIEWER.md §12.3, xterm-256 indexed 236: no ANSI-16
 * background is both darker than every syntax foreground and still visible against
 * the default background) and {@link #EXEC_NOLINE_BG} (§18.3, xterm-256 indexed 22:
 * a dark green with the same legibility property, distinguishable from the blue
 * {@link #EXEC_LINE_BG} at a glance); 256-colour support is ubiquitous. The choices
 * favour readability of code over decoration: comments dimmed, string/number/keyword
 * each distinct, and the current-line backgrounds (exec vs. merely-selected frame)
 * chosen to keep foreground syntax legible on top.
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

    /**
     * Background of a selected outer frame's call-site line (also the §11.8 browse-mode marker) —
     * a dim highlight, dark enough that every syntax foreground stays readable on it (§12.3).
     * Indexed 236 = {@code #303030}, the palette's one step outside ANSI-16.
     */
    public static final TextColor FRAME_LINE_BG = new TextColor.Indexed(236);

    /**
     * Background of the exec (innermost) current line when the shown frame is recorded but
     * line-less — enter/exit-only, no {@code LINE} records (§18.3). Indexed 22 = dark green,
     * dark enough that every syntax foreground stays readable on it; the palette's second
     * deliberate 256-colour exception alongside {@link #FRAME_LINE_BG}.
     */
    public static final TextColor EXEC_NOLINE_BG = new TextColor.Indexed(22);

    /**
     * Background of the Source window's cursor line (the highlighted-number line moved with ↑/↓,
     * §22) when it is NOT the current record/frame line — the whole line is tinted so it reads as
     * "where I am looking" without competing with an actual record position. Indexed 235
     * ({@code #262626}, §24.4 — one step lighter than §23.3's 234 so the tint is easier to see)
     * is still deliberately DARKER than {@link #FRAME_LINE_BG} (236) so a merely-selected
     * outer-frame record line always outranks it; the exec/frame highlight wins outright when the
     * cursor sits on it. The palette's third 256-colour exception.
     */
    public static final TextColor CURSOR_LINE_BG = new TextColor.Indexed(235);

    /**
     * Foreground of Stack rows whose recorded frame is line-less and of Threads rows whose
     * thread carries no {@code LINE} records at all (§18.3) — the light-green "calls only"
     * marking; selection inversion still wins.
     */
    public static final TextColor CALLS_ONLY_FG = TextColor.ANSI.GREEN_BRIGHT;

    /** Background of the selected row in a list/tree window. */
    public static final TextColor SELECTION_BG = TextColor.ANSI.CYAN;

    /** Foreground of the selected row in a list/tree window. */
    public static final TextColor SELECTION_FG = TextColor.ANSI.BLACK;

    /**
     * Background of an in-file search match highlighted in the source body (§20.7) — every visible
     * occurrence of the Alt-F query gets its matched columns painted in this reverse-video style
     * (black on yellow), on top of the normal cell render, so matches stand out against any of the
     * current-line backgrounds.
     */
    public static final TextColor SEARCH_MATCH_BG = TextColor.ANSI.YELLOW;

    /** Foreground of an in-file search match highlighted in the source body (§20.7). */
    public static final TextColor SEARCH_MATCH_FG = TextColor.ANSI.BLACK;

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
