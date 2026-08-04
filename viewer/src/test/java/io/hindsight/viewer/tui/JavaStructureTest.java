// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The §28.8 lexical outliner against a synthetic file: member classification (fields,
 * constructors incl. overloads, methods, inner types), enclosing-type selection by cursor
 * line (nested and multi-top-level files), and the comment/string blanking that keeps fake
 * declarations inside literals and javadoc out of the outline.
 */
final class JavaStructureTest {

    private static final List<String> FILE = List.of(
            "// file header mentions class Fake and int bogus = 1;",       // 1
            "package com.x;",                                              // 2
            "",                                                            // 3
            "/** Doc for Outer. class NotReal { } */",                     // 4
            "public class Outer {",                                        // 5
            "    private final long shardNum = 11;",                       // 6
            "    private String name;",                                    // 7
            "    static final String BRACE = \"{ class InString }\";",     // 8
            "",                                                            // 9
            "    Outer() {",                                               // 10
            "        this(0);",                                            // 11
            "    }",                                                       // 12
            "",                                                            // 13
            "    Outer(long seed) {",                                      // 14
            "        shard(seed);",                                        // 15
            "    }",                                                       // 16
            "",                                                            // 17
            "    public long shard(long v) {",                             // 18
            "        if (v > 0) {",                                        // 19
            "            return v;",                                       // 20
            "        }",                                                   // 21
            "        return shardNum;",                                    // 22
            "    }",                                                       // 23
            "",                                                            // 24
            "    void run() {",                                            // 25
            "        new Runnable() {",                                    // 26
            "            public void ignored() { }",                       // 27
            "        }.run();",                                            // 28
            "    }",                                                       // 29
            "",                                                            // 30
            "    static class Inner {",                                    // 31
            "        int innerField;",                                     // 32
            "        void innerMethod() { }",                              // 33
            "    }",                                                       // 34
            "}",                                                           // 35
            "",                                                            // 36
            "class Second {",                                              // 37
            "    int only;",                                               // 38
            "}");                                                          // 39

    @Test
    void outlinesTheEnclosingClassWithAllFourMemberKinds() {
        JavaStructure.ClassStructure c = JavaStructure.parse(FILE, 20); // inside shard()
        assertEquals("Outer", c.className());
        assertEquals(5, c.classLine());
        assertEquals(List.of("shardNum", "name", "BRACE"),
                c.fields().stream().map(JavaStructure.Member::name).toList());
        assertEquals(List.of(10, 14),
                c.constructors().stream().map(JavaStructure.Member::line).toList(),
                "both constructor overloads, by their own declaration lines");
        assertEquals(List.of("shard", "run"),
                c.methods().stream().map(JavaStructure.Member::name).toList(),
                "the anonymous class's method is NOT a member of Outer");
        assertEquals(List.of("Inner"),
                c.innerTypes().stream().map(JavaStructure.Member::name).toList());
    }

    @Test
    void cursorInsideNestedTypeOutlinesTheNestedType() {
        JavaStructure.ClassStructure c = JavaStructure.parse(FILE, 32);
        assertEquals("Inner", c.className());
        assertEquals(31, c.classLine());
        assertEquals(List.of("innerField"),
                c.fields().stream().map(JavaStructure.Member::name).toList());
        assertEquals(List.of("innerMethod"),
                c.methods().stream().map(JavaStructure.Member::name).toList());
        assertTrue(c.constructors().isEmpty());
        assertTrue(c.innerTypes().isEmpty());
    }

    @Test
    void cursorInASecondTopLevelClassOutlinesIt() {
        JavaStructure.ClassStructure c = JavaStructure.parse(FILE, 38);
        assertEquals("Second", c.className());
        assertEquals(List.of("only"),
                c.fields().stream().map(JavaStructure.Member::name).toList());
    }

    @Test
    void cursorBeforeAnyTypeFallsBackToTheFirstType() {
        JavaStructure.ClassStructure c = JavaStructure.parse(FILE, 2);
        assertEquals("Outer", c.className());
    }

    @Test
    void commentsAndLiteralsNeverContributeDeclarations() {
        JavaStructure.ClassStructure c = JavaStructure.parse(FILE, 20);
        assertTrue(c.innerTypes().stream().noneMatch(m -> m.name().equals("NotReal")));
        assertTrue(c.innerTypes().stream().noneMatch(m -> m.name().equals("InString")));
        assertTrue(c.fields().stream().noneMatch(m -> m.name().equals("bogus")));
        // The BRACE literal's '{' must not have desynced the depth tracking:
        assertEquals(5, c.classLine());
    }

    @Test
    void memberTextIsTheTrimmedDeclarationLine() {
        JavaStructure.ClassStructure c = JavaStructure.parse(FILE, 20);
        assertEquals("public long shard(long v) {", c.methods().get(0).text());
        assertEquals(18, c.methods().get(0).line());
    }

    @Test
    void fileWithoutTypesYieldsNull() {
        assertNull(JavaStructure.parse(List.of("package x;", "// nothing"), 1));
        assertNull(JavaStructure.parse(List.of(), 1));
    }

    // ---- §28.8 review regressions: no spurious members ----

    @Test
    void newCallInAFieldInitializerIsAFieldNotAConstructor() {
        List<String> f = List.of(
                "class Buffer {",                                       // 1
                "    private final Buffer other = new Buffer(10);",     // 2
                "    private final java.io.File g = new java.io.File(\"x\");", // 3
                "    Buffer(int n) { }",                                // 4
                "}");                                                   // 5
        JavaStructure.ClassStructure c = JavaStructure.parse(f, 2);
        assertEquals(List.of(4), c.constructors().stream().map(JavaStructure.Member::line).toList(),
                "only the real Buffer(int) is a constructor — not the two new Buffer()/new File() calls");
        assertEquals(List.of("other", "g"),
                c.fields().stream().map(JavaStructure.Member::name).toList());
        assertTrue(c.methods().isEmpty());
    }

    @Test
    void fieldInitializerContinuationLineIsNotAMethod() {
        List<String> f = List.of(
                "class C {",                        // 1
                "    private static final int N =", // 2
                "            compute();",           // 3
                "    void real() { }",              // 4
                "}");                               // 5
        JavaStructure.ClassStructure c = JavaStructure.parse(f, 4);
        assertEquals(List.of("real"), c.methods().stream().map(JavaStructure.Member::name).toList(),
                "compute() is the initializer continuation, not a member");
        assertEquals(List.of("N"), c.fields().stream().map(JavaStructure.Member::name).toList());
    }

    @Test
    void enumConstantsAreNotFields() {
        List<String> f = List.of(
                "enum Color {",   // 1
                "    RED, GREEN;", // 2
                "    int x;",      // 3
                "}");             // 4
        JavaStructure.ClassStructure c = JavaStructure.parse(f, 3);
        assertEquals("Color", c.className());
        assertEquals(List.of("x"), c.fields().stream().map(JavaStructure.Member::name).toList(),
                "the RED/GREEN constant list is not a field");
    }

    @Test
    void multiLineMethodSignatureStillClassifies() {
        List<String> f = List.of(
                "class M {",           // 1
                "    public long",     // 2
                "    shard(long v,",   // 3
                "          int w) {",  // 4
                "        return v;",   // 5
                "    }",               // 6
                "}");                  // 7
        JavaStructure.ClassStructure c = JavaStructure.parse(f, 5);
        assertEquals(List.of("shard"), c.methods().stream().map(JavaStructure.Member::name).toList(),
                "a signature split across lines is not defeated by the continuation guard");
    }
}
