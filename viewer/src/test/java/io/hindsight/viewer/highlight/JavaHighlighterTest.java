// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.viewer.highlight.JavaHighlighter.Span;
import io.hindsight.viewer.highlight.JavaHighlighter.Style;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Behavioural tests for the lexical {@link JavaHighlighter}. */
final class JavaHighlighterTest {

    private final JavaHighlighter h = new JavaHighlighter();

    // ---- invariant helpers --------------------------------------------------

    /** Asserts spans are ordered, non-overlapping, in-bounds, and tile the whole line. */
    private static void assertWellFormed(String line, List<Span> spans) {
        if (line.isEmpty()) {
            assertTrue(spans.isEmpty(), "empty line must yield no spans");
            return;
        }
        assertFalse(spans.isEmpty(), "non-empty line must yield spans: [" + line + "]");
        int prevEnd = 0;
        assertEquals(0, spans.get(0).start(), "first span must start at 0");
        for (Span s : spans) {
            assertTrue(s.start() < s.end(), "span must be non-empty: " + s);
            assertTrue(s.start() >= 0, "span start in bounds: " + s);
            assertTrue(s.end() <= line.length(), "span end in bounds: " + s);
            assertEquals(prevEnd, s.start(), "spans must be contiguous & ordered: " + s);
            prevEnd = s.end();
        }
        assertEquals(line.length(), prevEnd, "spans must cover the whole line");
    }

    /** Returns the style covering char offset {@code col}. */
    private static Style styleAt(List<Span> spans, int col) {
        for (Span s : spans) {
            if (col >= s.start() && col < s.end()) {
                return s.style();
            }
        }
        throw new AssertionError("no span covers column " + col);
    }

    private static boolean hasStyle(List<Span> spans, Style style) {
        return spans.stream().anyMatch(s -> s.style() == style);
    }

    // ---- keywords vs identifiers -------------------------------------------

    @Test
    void keywordsVersusIdentifiers() {
        String line = "public int foo = bar;";
        List<Span> spans = h.highlight(line);
        assertWellFormed(line, spans);
        assertEquals(Style.KEYWORD, styleAt(spans, line.indexOf("public")));
        assertEquals(Style.KEYWORD, styleAt(spans, line.indexOf("int")));
        assertEquals(Style.PLAIN, styleAt(spans, line.indexOf("foo")));
        assertEquals(Style.PLAIN, styleAt(spans, line.indexOf("bar")));
        assertEquals(Style.PUNCTUATION, styleAt(spans, line.indexOf('=')));
        assertEquals(Style.PUNCTUATION, styleAt(spans, line.indexOf(';')));
    }

    @Test
    void literalKeywords() {
        String line = "x = true; y = null; z = false;";
        List<Span> spans = h.highlight(line);
        assertWellFormed(line, spans);
        assertEquals(Style.KEYWORD, styleAt(spans, line.indexOf("true")));
        assertEquals(Style.KEYWORD, styleAt(spans, line.indexOf("null")));
        assertEquals(Style.KEYWORD, styleAt(spans, line.indexOf("false")));
    }

    @Test
    void contextualKeywordsArePlain() {
        // 'var' and 'record' are contextual, not reserved — lexically they are identifiers.
        String line = "var record = 1;";
        List<Span> spans = h.highlight(line);
        assertWellFormed(line, spans);
        assertEquals(Style.PLAIN, styleAt(spans, line.indexOf("var")));
        assertEquals(Style.PLAIN, styleAt(spans, line.indexOf("record")));
    }

    // ---- strings ------------------------------------------------------------

    @Test
    void stringWithEmbeddedSlashesAndEscapedQuote() {
        // Line content: String s = "a//b\"c";
        String line = "String s = \"a//b\\\"c\";";
        List<Span> spans = h.highlight(line);
        assertWellFormed(line, spans);
        // The // inside the string must NOT start a comment.
        assertFalse(hasStyle(spans, Style.COMMENT), "// inside a string is not a comment");
        int q = line.indexOf('"');
        assertEquals(Style.STRING, styleAt(spans, q), "opening quote is STRING");
        assertEquals(Style.STRING, styleAt(spans, line.indexOf("//")), "embedded // is STRING");
        // The escaped quote must not terminate the literal; the closing quote is the last one.
        int lastQuote = line.lastIndexOf('"');
        assertEquals(Style.STRING, styleAt(spans, lastQuote), "closing quote is STRING");
        assertEquals(Style.PUNCTUATION, styleAt(spans, line.length() - 1), "trailing ; is punctuation");
    }

    @Test
    void blockCommentOpenerInsideStringIsNotAComment() {
        String line = "s = \"/* not a comment */\";";
        List<Span> spans = h.highlight(line);
        assertWellFormed(line, spans);
        assertFalse(hasStyle(spans, Style.COMMENT), "/* inside a string is not a comment");
        assertEquals(Style.STRING, styleAt(spans, line.indexOf("/*")));
    }

    // ---- char literal -------------------------------------------------------

    @Test
    void charLiteralWithEscapedQuote() {
        // Line content: char c = '\'';
        String line = "char c = '\\'';";
        List<Span> spans = h.highlight(line);
        assertWellFormed(line, spans);
        int open = line.indexOf('\'');
        assertEquals(Style.CHAR, styleAt(spans, open));
        assertEquals(Style.KEYWORD, styleAt(spans, line.indexOf("char")));
    }

    // ---- numbers ------------------------------------------------------------

    @Test
    void numericLiterals() {
        String line = "a=0xFF; b=3.14f; c=1_000L; d=0b1010; e=1.2e-3;";
        List<Span> spans = h.highlight(line);
        assertWellFormed(line, spans);
        assertEquals(Style.NUMBER, styleAt(spans, line.indexOf("0xFF")));
        assertEquals(Style.NUMBER, styleAt(spans, line.indexOf("3.14f")));
        assertEquals(Style.NUMBER, styleAt(spans, line.indexOf("1_000L")));
        assertEquals(Style.NUMBER, styleAt(spans, line.indexOf("0b1010")));
        assertEquals(Style.NUMBER, styleAt(spans, line.indexOf("1.2e-3")));
        // The whole hex literal is one coalesced NUMBER span.
        int hx = line.indexOf("0xFF");
        for (int k = 0; k < 4; k++) {
            assertEquals(Style.NUMBER, styleAt(spans, hx + k));
        }
    }

    @Test
    void memberAccessDotIsNotANumber() {
        String line = "obj.field = 5;";
        List<Span> spans = h.highlight(line);
        assertWellFormed(line, spans);
        assertEquals(Style.PUNCTUATION, styleAt(spans, line.indexOf('.')));
        assertEquals(Style.NUMBER, styleAt(spans, line.indexOf('5')));
    }

    // ---- comments -----------------------------------------------------------

    @Test
    void lineComment() {
        String line = "x = 1; // trailing comment";
        List<Span> spans = h.highlight(line);
        assertWellFormed(line, spans);
        assertEquals(Style.COMMENT, styleAt(spans, line.indexOf("//")));
        assertEquals(Style.COMMENT, styleAt(spans, line.length() - 1));
        assertEquals(Style.NUMBER, styleAt(spans, line.indexOf('1')));
    }

    @Test
    void singleLineBlockComment() {
        String line = "/* hello */";
        List<Span> spans = h.highlight(line);
        assertWellFormed(line, spans);
        assertEquals(1, spans.size(), "a pure block comment coalesces into one span");
        assertEquals(Style.COMMENT, spans.get(0).style());
    }

    @Test
    void multiLineBlockCommentMiddleLineIsFullyComment() {
        List<String> lines = List.of(
                "int a = 1; /* start",
                "   still inside the comment",
                "end */ int b = 2;");
        List<List<Span>> all = h.highlight(lines);
        for (int i = 0; i < lines.size(); i++) {
            assertWellFormed(lines.get(i), all.get(i));
        }
        // Middle line is entirely one COMMENT span.
        List<Span> mid = all.get(1);
        assertEquals(1, mid.size(), "middle line is a single span");
        assertEquals(Style.COMMENT, mid.get(0).style());
        assertEquals(0, mid.get(0).start());
        assertEquals(lines.get(1).length(), mid.get(0).end());
        // First line: code then comment.
        assertEquals(Style.KEYWORD, styleAt(all.get(0), 0));
        assertEquals(Style.COMMENT, styleAt(all.get(0), lines.get(0).indexOf("/*")));
        // Last line: comment then code resumes.
        assertEquals(Style.COMMENT, styleAt(all.get(2), 0));
        assertEquals(Style.KEYWORD, styleAt(all.get(2), lines.get(2).indexOf("int")));
        assertEquals(Style.NUMBER, styleAt(all.get(2), lines.get(2).indexOf('2')));
    }

    @Test
    void singleLineHighlightDoesNotCarryBlockCommentState() {
        // Standalone highlighting treats each line independently: an unterminated /* is just a
        // comment to end of line, and the NEXT standalone call starts fresh in code state.
        String opener = "code(); /* open";
        String next = "int x = 1;";
        assertEquals(Style.COMMENT, styleAt(h.highlight(opener), opener.indexOf("/*")));
        // 'next' is treated as ordinary code, NOT as a comment continuation.
        assertEquals(Style.KEYWORD, styleAt(h.highlight(next), 0));
    }

    // ---- annotations --------------------------------------------------------

    @Test
    void annotation() {
        String line = "@Override public void run() {}";
        List<Span> spans = h.highlight(line);
        assertWellFormed(line, spans);
        assertEquals(Style.ANNOTATION, styleAt(spans, 0));
        assertEquals(Style.ANNOTATION, styleAt(spans, "@Override".length() - 1));
        assertEquals(Style.KEYWORD, styleAt(spans, line.indexOf("public")));
    }

    // ---- text blocks --------------------------------------------------------

    @Test
    void textBlockSpanningThreeLines() {
        List<String> lines = List.of(
                "return \"\"\"",
                "    hello world",
                "    \"\"\";");
        List<List<Span>> all = h.highlight(lines);
        for (int i = 0; i < lines.size(); i++) {
            assertWellFormed(lines.get(i), all.get(i));
        }
        // Opening line: the 'return' keyword, then the opening triple-quote is STRING.
        assertEquals(Style.KEYWORD, styleAt(all.get(0), 0));
        assertEquals(Style.STRING, styleAt(all.get(0), lines.get(0).indexOf("\"\"\"")));
        // Middle line is entirely STRING (inside the text block).
        List<Span> mid = all.get(1);
        assertEquals(1, mid.size(), "middle text-block line is a single STRING span");
        assertEquals(Style.STRING, mid.get(0).style());
        assertEquals(lines.get(1).length(), mid.get(0).end());
        // Closing line: STRING through the closing """, then the trailing ; is punctuation.
        assertEquals(Style.STRING, styleAt(all.get(2), lines.get(2).indexOf("\"\"\"")));
        assertEquals(Style.PUNCTUATION, styleAt(all.get(2), lines.get(2).length() - 1));
    }

    @Test
    void emptyLineYieldsNoSpans() {
        assertWellFormed("", h.highlight(""));
    }
}
