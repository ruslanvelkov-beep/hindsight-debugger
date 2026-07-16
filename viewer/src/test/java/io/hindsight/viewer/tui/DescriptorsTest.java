// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests JVM method-descriptor prettifying for the Stack window (VIEWER.md §6.3). */
final class DescriptorsTest {

    @Test
    void splitsParameters() {
        assertEquals(List.of("Lcom/foo/Bar;", "I"), Descriptors.paramDescriptors("(Lcom/foo/Bar;I)V"));
        assertEquals(List.of(), Descriptors.paramDescriptors("()V"));
        assertEquals(List.of("J", "D"), Descriptors.paramDescriptors("(JD)Ljava/lang/String;"));
        assertEquals(List.of("[I", "[[Ljava/lang/String;"),
                Descriptors.paramDescriptors("([I[[Ljava/lang/String;)V"));
    }

    @Test
    void simplifiesSingleTypes() {
        assertEquals("int", Descriptors.simpleType("I"));
        assertEquals("long", Descriptors.simpleType("J"));
        assertEquals("boolean", Descriptors.simpleType("Z"));
        assertEquals("Bar", Descriptors.simpleType("Lcom/foo/Bar;"));
        assertEquals("Inner", Descriptors.simpleType("Lcom/foo/Outer$Inner;"));
        assertEquals("int[]", Descriptors.simpleType("[I"));
        assertEquals("String[][]", Descriptors.simpleType("[[Ljava/lang/String;"));
    }

    @Test
    void rendersParameterList() {
        assertEquals("(Bar, int)", Descriptors.simplifyParams("(Lcom/foo/Bar;I)V"));
        assertEquals("()", Descriptors.simplifyParams("()V"));
        assertEquals("(String, long)", Descriptors.simplifyParams("(Ljava/lang/String;J)Z"));
    }
}
