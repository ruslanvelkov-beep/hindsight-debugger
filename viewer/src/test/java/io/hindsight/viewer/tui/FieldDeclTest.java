// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests the §13.5 field-declaration heuristic: what parses, what is rejected, and the declared names. */
final class FieldDeclTest {

    // ---- accepted declarations ----

    @Test
    void plainDeclarations() {
        assertEquals(List.of("x"), FieldDecl.parse("int x;"));
        assertEquals(List.of("engine"), FieldDecl.parse("private final Engine engine;"));
        assertEquals(List.of("name"), FieldDecl.parse("String name;"));
        assertEquals(List.of("running"), FieldDecl.parse("    private boolean running = true;"));
    }

    @Test
    void declarationsWithInitializers() {
        assertEquals(List.of("x"), FieldDecl.parse("int x = 5;"));
        assertEquals(List.of("total"), FieldDecl.parse("private long total = compute() + 1;")); // parens after '=' are fine
        assertEquals(List.of("s"), FieldDecl.parse("String s = \"a;b=c\";")); // ; and = inside the initializer
    }

    @Test
    void declarationsWithGenerics() {
        assertEquals(List.of("counts"),
                FieldDecl.parse("private static final Map<String, Integer> counts = new HashMap<>();"));
        assertEquals(List.of("byName"), FieldDecl.parse("private Map<String, List<Long>> byName;"));
        assertEquals(List.of("e"), FieldDecl.parse("Map.Entry<String, Long> e;")); // qualified type
    }

    @Test
    void declarationsWithAnnotationsAndFinal() {
        assertEquals(List.of("name"), FieldDecl.parse("@Nullable String name;"));
        assertEquals(List.of("cache"), FieldDecl.parse("@Deprecated private final Cache cache = null;"));
        assertEquals(List.of("mod"), FieldDecl.parse("protected static volatile transient int mod;"));
    }

    @Test
    void arrayDeclarations() {
        assertEquals(List.of("data"), FieldDecl.parse("final long[] data = new long[8];"));
        assertEquals(List.of("grid"), FieldDecl.parse("int[][] grid;"));
        assertEquals(List.of("lists"), FieldDecl.parse("List<int[]>[] lists;"));
    }

    @Test
    void multiDeclarators() {
        assertEquals(List.of("a", "b"), FieldDecl.parse("int a, b;"));
        assertEquals(List.of("a", "b", "c"), FieldDecl.parse("private int a, b, c;"));
        assertEquals(List.of("first", "second"), FieldDecl.parse("Map<String, Integer> first, second;"));
        assertEquals(List.of("a"), FieldDecl.parse("int a = 1, b = 2;")); // cut at the first '=' (spec-literal)
        assertEquals(List.of("a", "b"), FieldDecl.parse("int a[], b[];")); // C-style brackets
    }

    @Test
    void trailingCommentIsStripped() {
        assertEquals(List.of("x"), FieldDecl.parse("int x; // the counter"));
        assertEquals(List.of("y"), FieldDecl.parse("int y = 5; // y = 6 once"));
    }

    @Test
    void contextualKeywordsAreValidNames() {
        assertEquals(List.of("record"), FieldDecl.parse("int record = 5;"));
        assertEquals(List.of("yield"), FieldDecl.parse("private double yield;"));
    }

    // ---- rejections ----

    @Test
    void methodSignaturesRejected() {
        assertTrue(FieldDecl.parse("public void run() {").isEmpty()); // no '='/';' head
        assertTrue(FieldDecl.parse("public abstract int size();").isEmpty()); // parens in head
        assertTrue(FieldDecl.parse("private int compute(int a, int b) { return a; }").isEmpty());
        assertTrue(FieldDecl.parse("void set(int x) { this.x = x; }").isEmpty());
    }

    @Test
    void callsAndExpressionsRejected() {
        assertTrue(FieldDecl.parse("counts.put(key, value);").isEmpty());
        assertTrue(FieldDecl.parse("foo(\"a=b\");").isEmpty());
        assertTrue(FieldDecl.parse("i++;").isEmpty());
        assertTrue(FieldDecl.parse("a + b;").isEmpty()); // operator poisons the token list
    }

    @Test
    void plainAssignmentsRejected() {
        assertTrue(FieldDecl.parse("x = 5;").isEmpty());
        assertTrue(FieldDecl.parse("this.x = 5;").isEmpty());
        assertTrue(FieldDecl.parse("x += 5;").isEmpty());
        assertTrue(FieldDecl.parse("x == y;").isEmpty());
    }

    @Test
    void controlStatementsRejected() {
        assertTrue(FieldDecl.parse("if (a == b) {").isEmpty());
        assertTrue(FieldDecl.parse("for (int i = 0; i < n; i++) {").isEmpty());
        assertTrue(FieldDecl.parse("while (running) {").isEmpty());
        assertTrue(FieldDecl.parse("return count;").isEmpty());
        assertTrue(FieldDecl.parse("throw new IllegalStateException();").isEmpty());
        assertTrue(FieldDecl.parse("case FOO: x = 1;").isEmpty());
        assertTrue(FieldDecl.parse("assert x != null;").isEmpty());
        assertTrue(FieldDecl.parse("new StringBuilder();").isEmpty());
        assertTrue(FieldDecl.parse("import java.util.List;").isEmpty());
        assertTrue(FieldDecl.parse("package com.example;").isEmpty());
        assertTrue(FieldDecl.parse("else x = 1;").isEmpty());
    }

    @Test
    void commentOnlyAndBlankLinesRejected() {
        assertTrue(FieldDecl.parse("// int x = 5;").isEmpty());
        assertTrue(FieldDecl.parse("    // comment").isEmpty());
        assertTrue(FieldDecl.parse("/* int x; */").isEmpty()); // block comment poisons the tokens
        assertTrue(FieldDecl.parse(" * int x; (javadoc body)").isEmpty());
        assertTrue(FieldDecl.parse("").isEmpty());
        assertTrue(FieldDecl.parse("   ").isEmpty());
        assertTrue(FieldDecl.parse(null).isEmpty());
    }

    @Test
    void singleTokenAndKeywordTailsRejected() {
        assertTrue(FieldDecl.parse("x;").isEmpty()); // only one token — no type
        assertTrue(FieldDecl.parse("}").isEmpty()); // no '='/';'
        assertTrue(FieldDecl.parse("} finally {").isEmpty());
        assertTrue(FieldDecl.parse("int this;").isEmpty()); // reserved word cannot be a name
        assertTrue(FieldDecl.parse("a.b = 5;").isEmpty()); // dotted target is not a plain name
    }

    @Test
    void comparisonsWithAnglesRejected() {
        assertTrue(FieldDecl.parse("x < 5;").isEmpty());
        assertTrue(FieldDecl.parse("a >= b;").isEmpty());
        // ... but a declaration whose initializer compares still parses (head cut at the first '=').
        assertEquals(List.of("b"), FieldDecl.parse("boolean b = x <= y;"));
    }

    @Test
    void malformedContinuationSegmentsAreSkipped() {
        assertEquals(List.of("a"), FieldDecl.parse("int a, 5x;")); // bad declarator ignored
        assertEquals(List.of("a"), FieldDecl.parse("int a,;")); // empty declarator ignored
        assertTrue(FieldDecl.parse("FOO, BAR,").isEmpty()); // enum constants: head has one token, no ';'/'='
    }
}
