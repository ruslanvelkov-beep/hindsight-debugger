// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.highlight;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A purely lexical Java syntax highlighter for the viewer's Source window (VIEWER.md &sect;4).
 *
 * <p>This class performs <em>no</em> semantic analysis: it does not resolve types, imports, or
 * symbols. It scans raw source text one character at a time and classifies each region into one
 * of the coarse {@link Style} buckets the TUI maps to terminal colours. The point is speed and
 * robustness — the viewer renders arbitrary, possibly-truncated source lines on every redraw, so
 * the highlighter must never throw and must always produce a clean, gap-free tiling of the line.
 *
 * <p><b>Coverage contract.</b> Every non-empty input line is tiled by an ordered, non-overlapping
 * list of {@link Span}s using half-open {@code [start,end)} char offsets. This implementation
 * emits <em>explicit</em> {@link Style#PLAIN} spans for identifiers, whitespace, and any character
 * a rule does not otherwise claim, so the returned list fully covers {@code [0,line.length())}
 * with no gaps. (The TUI treats any uncovered range as PLAIN anyway, so a consumer may ignore the
 * distinction; we choose explicit coverage because it makes the invariant trivially testable.)
 * Adjacent regions of the same style are coalesced into a single span. Empty spans are never
 * emitted, so a blank line — including a blank line in the middle of a block comment or text
 * block — yields an empty list while still advancing the carried multi-line state.
 *
 * <p><b>Multi-line state.</b> {@link #highlight(String)} treats its argument standalone (starting
 * from ordinary code state), which is what the TUI wants when it only needs to recolour one line.
 * {@link #highlight(List)} threads lexer state across lines so that block comments {@code /* *}{@code /}
 * and text blocks ({@code """}) that open on one line and close on a later one are coloured
 * correctly on every line in between.
 *
 * <p><b>Keyword policy.</b> {@link #KEYWORDS} is the set of JLS reserved keywords plus the three
 * reserved literals {@code true}/{@code false}/{@code null}. Contextual keywords ({@code var},
 * {@code record}, {@code sealed}, {@code yield}, &hellip;) are intentionally <em>not</em> included:
 * they are valid identifiers, and a lexical scanner cannot tell the two uses apart, so they render
 * as {@link Style#PLAIN} identifiers.
 */
public final class JavaHighlighter {

    /**
     * The coarse lexical categories the highlighter distinguishes. The TUI (VIEWER.md &sect;6) maps
     * each to a lanterna colour; this package assigns no colours itself.
     */
    public enum Style {
        /** Identifiers, whitespace, and anything not claimed by another rule. */
        PLAIN,
        /** A reserved Java keyword or one of {@code true}/{@code false}/{@code null}. */
        KEYWORD,
        /** A double-quoted string literal or a {@code """} text block (or a fragment of one). */
        STRING,
        /** A single-quoted char literal. */
        CHAR,
        /** A numeric literal (decimal, hex, binary, underscored, float/long suffixed). */
        NUMBER,
        /** A {@code //} line comment or a {@code /* *}{@code /} block comment (or a fragment). */
        COMMENT,
        /** An annotation use, i.e. {@code @} immediately followed by an identifier name. */
        ANNOTATION,
        /** Separators and operators. */
        PUNCTUATION
    }

    /**
     * A coloured region of a single line, using half-open char offsets.
     *
     * @param start inclusive start offset (0-based) into the line
     * @param end   exclusive end offset; always {@code > start}
     * @param style the lexical category of the region
     */
    public record Span(int start, int end, Style style) {}

    /** JLS reserved keywords plus the reserved literals {@code true}/{@code false}/{@code null}. */
    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null");

    /** Characters treated as Java separators/operators (each classified {@link Style#PUNCTUATION}). */
    private static final String PUNCTUATION_CHARS = "(){}[];,.@:?+-*/%=<>!&|^~";

    /** Carried lexer state between lines when highlighting a block of source. */
    private enum LexState {
        /** Ordinary code. */
        NORMAL,
        /** Inside an unterminated {@code /* *}{@code /} block comment. */
        BLOCK_COMMENT,
        /** Inside an unterminated {@code """} text block. */
        TEXT_BLOCK
    }

    /** Result of lexing one line: the produced spans plus the state to carry to the next line. */
    private record LineResult(List<Span> spans, LexState next) {}

    /** Stateless; a single instance may be shared freely. */
    public JavaHighlighter() {}

    /**
     * Highlights a single line in isolation, starting from ordinary-code state. Any block comment
     * or text block left open at end-of-line is simply not carried anywhere; the line is treated
     * standalone. Use {@link #highlight(List)} when multi-line constructs must be tracked.
     *
     * @param line the source line (without a trailing newline); must not be {@code null}
     * @return an ordered, non-overlapping list of spans covering {@code [0,line.length())}
     */
    public List<Span> highlight(String line) {
        return lexLine(line, LexState.NORMAL).spans();
    }

    /**
     * Highlights a block of lines, carrying lexer state across line boundaries so multi-line block
     * comments and text blocks are coloured on every line they span.
     *
     * @param lines the source lines (each without a trailing newline); must not be {@code null}
     * @return one span list per input line, index-aligned with {@code lines}
     */
    public List<List<Span>> highlight(List<String> lines) {
        List<List<Span>> out = new ArrayList<>(lines.size());
        LexState state = LexState.NORMAL;
        for (String line : lines) {
            LineResult r = lexLine(line, state);
            out.add(r.spans());
            state = r.next();
        }
        return out;
    }

    /**
     * Lexes one line given the incoming carried state, producing a gap-free, coalesced span tiling
     * and the state to carry forward.
     */
    private LineResult lexLine(String line, LexState incoming) {
        List<Span> spans = new ArrayList<>();
        int n = line.length();
        int i = 0;
        LexState state = incoming;

        // Resume a multi-line construct opened on a previous line.
        if (state == LexState.BLOCK_COMMENT) {
            int end = findBlockCommentEnd(line, 0);
            if (end < 0) {
                emit(spans, 0, n, Style.COMMENT);
                return new LineResult(spans, LexState.BLOCK_COMMENT);
            }
            emit(spans, 0, end, Style.COMMENT);
            i = end;
            state = LexState.NORMAL;
        } else if (state == LexState.TEXT_BLOCK) {
            int end = findTextBlockEnd(line, 0);
            if (end < 0) {
                emit(spans, 0, n, Style.STRING);
                return new LineResult(spans, LexState.TEXT_BLOCK);
            }
            emit(spans, 0, end, Style.STRING);
            i = end;
            state = LexState.NORMAL;
        }

        while (i < n) {
            char c = line.charAt(i);

            // Line comment: // ... to end of line.
            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
                emit(spans, i, n, Style.COMMENT);
                i = n;
                break;
            }

            // Block comment: /* ... possibly spilling past end of line.
            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
                int end = findBlockCommentEnd(line, i + 2);
                if (end < 0) {
                    emit(spans, i, n, Style.COMMENT);
                    return new LineResult(spans, LexState.BLOCK_COMMENT);
                }
                emit(spans, i, end, Style.COMMENT);
                i = end;
                continue;
            }

            // Text block: """ ... possibly spilling past end of line. Checked before "..." so the
            // opening triple quote is not mistaken for an empty string followed by a quote.
            if (c == '"' && i + 2 < n && line.charAt(i + 1) == '"' && line.charAt(i + 2) == '"') {
                int end = findTextBlockEnd(line, i + 3);
                if (end < 0) {
                    emit(spans, i, n, Style.STRING);
                    return new LineResult(spans, LexState.TEXT_BLOCK);
                }
                emit(spans, i, end, Style.STRING);
                i = end;
                continue;
            }

            // String literal: "..." with escape handling; unterminated ends at line end.
            if (c == '"') {
                int end = scanQuoted(line, i, '"');
                emit(spans, i, end, Style.STRING);
                i = end;
                continue;
            }

            // Char literal: '...' with escape handling; unterminated ends at line end.
            if (c == '\'') {
                int end = scanQuoted(line, i, '\'');
                emit(spans, i, end, Style.CHAR);
                i = end;
                continue;
            }

            // Annotation: @ immediately followed by an identifier name.
            if (c == '@' && i + 1 < n && Character.isJavaIdentifierStart(line.charAt(i + 1))) {
                int j = i + 2;
                while (j < n && Character.isJavaIdentifierPart(line.charAt(j))) {
                    j++;
                }
                emit(spans, i, j, Style.ANNOTATION);
                i = j;
                continue;
            }

            // Numeric literal: a leading digit, or a '.' immediately followed by a digit.
            if (isDigit(c) || (c == '.' && i + 1 < n && isDigit(line.charAt(i + 1)))) {
                int end = scanNumber(line, i);
                emit(spans, i, end, Style.NUMBER);
                i = end;
                continue;
            }

            // Identifier or keyword.
            if (Character.isJavaIdentifierStart(c)) {
                int j = i + 1;
                while (j < n && Character.isJavaIdentifierPart(line.charAt(j))) {
                    j++;
                }
                String word = line.substring(i, j);
                emit(spans, i, j, KEYWORDS.contains(word) ? Style.KEYWORD : Style.PLAIN);
                i = j;
                continue;
            }

            // Separators / operators.
            if (PUNCTUATION_CHARS.indexOf(c) >= 0) {
                emit(spans, i, i + 1, Style.PUNCTUATION);
                i++;
                continue;
            }

            // Whitespace and anything else: PLAIN.
            emit(spans, i, i + 1, Style.PLAIN);
            i++;
        }

        return new LineResult(spans, state);
    }

    /**
     * Appends a span, dropping empties and coalescing with the previous span when it has the same
     * style (spans are produced contiguously, so equal-style neighbours always abut).
     */
    private static void emit(List<Span> spans, int start, int end, Style style) {
        if (start >= end) {
            return;
        }
        if (!spans.isEmpty()) {
            Span last = spans.get(spans.size() - 1);
            if (last.style() == style && last.end() == start) {
                spans.set(spans.size() - 1, new Span(last.start(), end, style));
                return;
            }
        }
        spans.add(new Span(start, end, style));
    }

    /**
     * Scans a quoted literal (string or char) starting at the opening quote at {@code start},
     * honouring backslash escapes so an escaped quote does not close the literal. Returns the index
     * just past the closing quote, or the line length if the literal is unterminated on this line.
     */
    private static int scanQuoted(String line, int start, char quote) {
        int n = line.length();
        int j = start + 1;
        while (j < n) {
            char c = line.charAt(j);
            if (c == '\\') {
                j += 2; // skip the escaped character (clamped by the loop bound / return below)
                continue;
            }
            if (c == quote) {
                return j + 1;
            }
            j++;
        }
        return n;
    }

    /**
     * Finds the end of a block comment, i.e. the index just past the {@code *}{@code /} that closes
     * it, searching from {@code from}. Returns {@code -1} if the comment does not close on this line.
     */
    private static int findBlockCommentEnd(String line, int from) {
        int idx = line.indexOf("*/", from);
        return idx < 0 ? -1 : idx + 2;
    }

    /**
     * Finds the end of a text block, i.e. the index just past the closing {@code """}, searching
     * from {@code from} and honouring backslash escapes. Returns {@code -1} if it does not close on
     * this line.
     */
    private static int findTextBlockEnd(String line, int from) {
        int n = line.length();
        int j = from;
        while (j < n) {
            char c = line.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c == '"' && j + 2 < n && line.charAt(j + 1) == '"' && line.charAt(j + 2) == '"') {
                return j + 3;
            }
            j++;
        }
        return -1;
    }

    /**
     * Scans a numeric literal beginning at {@code start} (a digit, or a {@code .} known to be
     * followed by a digit). Accepts hex ({@code 0x}), binary ({@code 0b}), underscored digit
     * groups, a fractional part, decimal/hex exponents, and a trailing {@code f/F/d/D/l/L} suffix.
     * This is deliberately loose — it never rejects — because the goal is colouring, not validation.
     */
    private static int scanNumber(String line, int start) {
        int n = line.length();
        int j = start;
        char c0 = line.charAt(j);

        if (c0 == '0' && j + 1 < n && (line.charAt(j + 1) == 'x' || line.charAt(j + 1) == 'X')) {
            j += 2;
            j = consumeWhile(line, j, JavaHighlighter::isHexOrUnderscore);
            if (j < n && line.charAt(j) == '.') {
                j = consumeWhile(line, j + 1, JavaHighlighter::isHexOrUnderscore);
            }
            if (j < n && (line.charAt(j) == 'p' || line.charAt(j) == 'P')) {
                j = consumeExponent(line, j);
            }
        } else if (c0 == '0' && j + 1 < n && (line.charAt(j + 1) == 'b' || line.charAt(j + 1) == 'B')) {
            j += 2;
            j = consumeWhile(line, j, JavaHighlighter::isBinaryOrUnderscore);
        } else {
            j = consumeWhile(line, j, JavaHighlighter::isDigitOrUnderscore);
            if (j < n && line.charAt(j) == '.') {
                j = consumeWhile(line, j + 1, JavaHighlighter::isDigitOrUnderscore);
            }
            if (j < n && (line.charAt(j) == 'e' || line.charAt(j) == 'E')) {
                j = consumeExponent(line, j);
            }
        }

        if (j < n) {
            char s = line.charAt(j);
            if (s == 'f' || s == 'F' || s == 'd' || s == 'D' || s == 'l' || s == 'L') {
                j++;
            }
        }
        // Guarantee progress: a bare leading '.' with no following digits would not happen (the
        // caller only enters on a digit or '.'+digit), but clamp defensively.
        return Math.max(j, start + 1);
    }

    /** Consumes an exponent marker (e/E/p/P) with an optional sign and digits; keeps only if valid. */
    private static int consumeExponent(String line, int at) {
        int n = line.length();
        int j = at + 1; // skip the exponent letter
        if (j < n && (line.charAt(j) == '+' || line.charAt(j) == '-')) {
            j++;
        }
        int digitsStart = j;
        j = consumeWhile(line, j, JavaHighlighter::isDigitOrUnderscore);
        // No digits after the exponent letter → not really an exponent; leave the letter to be
        // rescanned as an identifier by returning the original position.
        return j > digitsStart ? j : at;
    }

    private interface CharPredicate {
        boolean test(char c);
    }

    private static int consumeWhile(String line, int from, CharPredicate p) {
        int j = from;
        int n = line.length();
        while (j < n && p.test(line.charAt(j))) {
            j++;
        }
        return j;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isDigitOrUnderscore(char c) {
        return isDigit(c) || c == '_';
    }

    private static boolean isBinaryOrUnderscore(char c) {
        return c == '0' || c == '1' || c == '_';
    }

    private static boolean isHexOrUnderscore(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '_';
    }
}
