// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests the §18.3 declaration-line finder used for line-less recorded frames: the upward
 * scan from the body's first line, the annotation/comment skip rules, the {@code '('}
 * requirement, the constructor rule and the fallback — plus the §19.1 {@link
 * DeclLine#innermostLineless} gating that restricts that treatment to the innermost frame.
 */
final class DeclLineTest {

    // ---- §19.1 innermost-only gating ----

    @Test
    void innermostLinelessAppliesOnlyToTheSelectedInnermostRecordedLinelessFrame() {
        int stackSize = 3; // frames 0,1,2 with 2 the innermost (execution) frame
        // The innermost frame, recorded and line-less: the §18.3 decl-line + green treatment applies.
        assertTrue(DeclLine.innermostLineless(2, stackSize, true, false));
        // A NON-innermost line-less frame (the v1.8 bug): must NOT get the treatment (§19.1).
        assertFalse(DeclLine.innermostLineless(1, stackSize, true, false));
        assertFalse(DeclLine.innermostLineless(0, stackSize, true, false));
        // The innermost frame WITH its own lines: ordinary blue exec highlight, not green.
        assertFalse(DeclLine.innermostLineless(2, stackSize, true, true));
        // An innermost UNRECORDED frame: never the treatment (no method to find a declaration for).
        assertFalse(DeclLine.innermostLineless(2, stackSize, false, false));
        // Degenerate: no selection / empty stack.
        assertFalse(DeclLine.innermostLineless(-1, 0, true, false));
        // A single-frame stack: frame 0 IS the innermost, so the treatment applies.
        assertTrue(DeclLine.innermostLineless(0, 1, true, false));
    }

    @Test
    void findsThePlainDeclarationLineAboveTheBody() {
        List<String> lines = List.of(
                "package com.ex;",              // 1
                "",                             // 2
                "class Foo {",                  // 3
                "    int helper(int x) {",      // 4
                "        return x + 1;",        // 5
                "    }",                        // 6
                "}");                           // 7
        assertEquals(4, DeclLine.find(lines, "helper", "Foo", 5));
    }

    @Test
    void bodyFirstLineItselfMayBeTheDeclaration() {
        // The recorder often registers the METHOD site on the declaration line itself.
        List<String> lines = List.of(
                "class Foo {",                  // 1
                "    void run() { work(); }",   // 2
                "}");                           // 3
        assertEquals(2, DeclLine.find(lines, "run", "Foo", 2));
    }

    @Test
    void scanCrossesAnnotationLinesInsideAMultiLineSignature() {
        List<String> lines = List.of(
                "class Foo {",                    // 1
                "    void helper(",               // 2  ← the name + '(' line
                "            @Nullable Integer a) {", // 3  ← '@'-first parameter-annotation line
                "        work();",                // 4
                "    }",                          // 5
                "}");                             // 6
        // Scanning up from 4 passes through the annotation line 3 and lands on 2.
        assertEquals(2, DeclLine.find(lines, "helper", "Foo", 4));
    }

    @Test
    void annotationLineNeverMatchesEvenWhenItContainsTheName() {
        List<String> lines = List.of(
                "class Foo {",                  // 1
                "    int helper(int x) {",      // 2
                "        return 0;",            // 3
                "    }",                        // 4
                "    @Uses(helper (1))",        // 5  ← name + '(' on an annotation line
                "    int other() {",            // 6
                "        return 1;",            // 7
                "    }",                        // 8
                "}");                           // 9
        // Scanning up from 7 for `helper`: line 5 is '@'-first → skipped; line 2 matches.
        assertEquals(2, DeclLine.find(lines, "helper", "Foo", 7));
    }

    @Test
    void multiLineSignatureResolvesToTheNameLine() {
        List<String> lines = List.of(
                "class Foo {",                  // 1
                "    static long compute(",     // 2  ← the name + '(' line
                "            int a,",           // 3
                "            int b)",           // 4
                "            throws Exception {", // 5
                "        return a + b;",        // 6
                "    }",                        // 7
                "}");                           // 8
        assertEquals(2, DeclLine.find(lines, "compute", "Foo", 6));
    }

    @Test
    void constructorUsesTheSimpleClassName() {
        List<String> lines = List.of(
                "class Foo {",                  // 1
                "    private final int n;",     // 2
                "    Foo(int n) {",             // 3
                "        this.n = n;",          // 4
                "    }",                        // 5
                "}");                           // 6
        assertEquals(3, DeclLine.find(lines, "<init>", "Foo", 4));
    }

    @Test
    void nameNotFoundFallsBackToTheBodyLine() {
        List<String> lines = List.of(
                "class Foo {",                  // 1
                "    int x = 1;",               // 2
                "}");                           // 3
        assertEquals(2, DeclLine.find(lines, "missing", "Foo", 2));
    }

    @Test
    void nameInACommentAboveIsNotMatched() {
        List<String> lines = List.of(
                "class Foo {",                          // 1
                "    int helper(int x) {",              // 2  ← the true declaration
                "        // fast path: helper (again)", // 3  ← //-comment WITH name + '(' — skipped
                "        * helper (y) legacy note",     // 4  ← '*'-prefixed WITH name + '(' — skipped
                "        return x;",                    // 5
                "    }",                                // 6
                "}");                                   // 7
        // Scanning up from 5: lines 4 and 3 contain `helper (` and would match were they not
        // comment-skipped; the finder must land on the real declaration at 2.
        assertEquals(2, DeclLine.find(lines, "helper", "Foo", 5));
    }

    @Test
    void nameWithoutFollowingParenDoesNotMatch() {
        List<String> lines = List.of(
                "class Foo {",                  // 1
                "    int helper = 3;",          // 2  ← name, no '('
                "    int helper2() {",          // 3  ← name followed by '2', not '('
                "        return helper;",       // 4
                "    }",                        // 5
                "}");                           // 6
        assertEquals(4, DeclLine.find(lines, "helper", "Foo", 4));
    }

    @Test
    void whitespaceBetweenNameAndParenIsAccepted() {
        List<String> lines = List.of(
                "class Foo {",                  // 1
                "    void helper (int x) {",    // 2
                "        work();",              // 3
                "    }",                        // 4
                "}");                           // 5
        assertEquals(2, DeclLine.find(lines, "helper", "Foo", 3));
    }

    @Test
    void scanStopsAfterThirtyLines() {
        List<String> lines = new ArrayList<>();
        lines.add("    void helper(int x) {");  // 1 — exactly 31 lines above the body line
        for (int i = 2; i <= 31; i++) {
            lines.add("        stmt" + i + "();");
        }
        lines.add("        last();");           // 32 = bodyFirstLine
        // Lines 32..3 are scanned (30 lines); line 1 is one beyond the budget → fallback.
        assertEquals(32, DeclLine.find(lines, "helper", "Foo", 32));
        // One line closer (declaration on line 2) is within the budget and found.
        List<String> closer = new ArrayList<>(lines);
        closer.set(2 - 1, "    void helper(int x) {");
        assertEquals(2, DeclLine.find(closer, "helper", "Foo", 31));
    }

    @Test
    void outOfRangeBodyLineClampsToTheFileEnd() {
        List<String> lines = List.of(
                "class Foo {",                  // 1
                "    void helper() {}",         // 2
                "}");                           // 3
        // bodyFirstLine beyond the file: the scan starts at the last line and still finds 2.
        assertEquals(2, DeclLine.find(lines, "helper", "Foo", 99));
    }

    @Test
    void degenerateInputsFallBack() {
        List<String> lines = List.of("class Foo {", "}");
        assertEquals(7, DeclLine.find(lines, null, "Foo", 7), "null method name");
        assertEquals(7, DeclLine.find(lines, "", "Foo", 7), "empty method name");
        assertEquals(7, DeclLine.find(lines, "<init>", null, 7), "constructor with unknown class");
        assertEquals(5, DeclLine.find(List.of(), "helper", "Foo", 5), "empty file");
        assertEquals(0, DeclLine.find(lines, "helper", "Foo", 0), "non-positive body line");
    }

    // ---- §28.2 review regressions: call sites and string literals never win ----

    @Test
    void methodCallSiteDoesNotOutvoteTheDeclaration() {
        List<String> f = List.of(
                "class V {",                    // 1
                "    void visit(Node n) {",     // 2
                "        prepare();",           // 3
                "        child.visit(n.left);", // 4 — a CALL, must not win
                "        int x = 1;",           // 5
                "    }",                        // 6
                "}");                           // 7
        assertEquals(2, DeclLine.findOrZero(f, "visit", "V", 5, Integer.MAX_VALUE),
                "the child.visit(...) call on line 4 is not the declaration");
    }

    @Test
    void typeMentionInAStringLiteralIsIgnored() {
        List<String> f = List.of(
                "class Buffer {",                             // 1 — the real declaration
                "    void log() {",                           // 2
                "        LOG.info(\"in class Buffer now\");",  // 3 — a string, must not win
                "        int x = 1;",                         // 4
                "    }",                                      // 5
                "}");                                         // 6
        assertEquals(1, DeclLine.findTypeOrZero(f, "Buffer", 4),
                "the 'class Buffer' inside the string on line 3 is not a declaration");
    }

    @Test
    void nameInsideALongerIdentifierIsNotAWholeWordMatch() {
        List<String> f = List.of(
                "class X {",                 // 1
                "    void previsit() { }",   // 2 — contains 'visit' but is not it
                "    void visit() { }",      // 3
                "}");                        // 4
        assertEquals(3, DeclLine.findOrZero(f, "visit", "X", 3, Integer.MAX_VALUE));
        assertEquals("say", DeclLine.stripLiterals("say \"hi\" // and bye").strip(),
                "the string and line comment blank to whitespace; code words stay");
    }
}
