// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.hindsight.viewer.engine.Value;
import java.util.function.LongFunction;
import org.junit.jupiter.api.Test;

/** Tests the pure §14.4 type-naming helper behind the focus-sensitive info line. */
final class TypeNameTest {

    private static final LongFunction<String> KNOWN =
            id -> id == 240 ? "com.hedera.pbj.runtime.io.buffer.Bytes" : null;

    @Test
    void refUsesRecordedClassBinaryPlusId() {
        assertEquals(
                "com.hedera.pbj.runtime.io.buffer.Bytes#240",
                TypeName.of(new Value.Ref(240, "Bytes#240"), null, KNOWN));
    }

    @Test
    void unknownRefRendersQuestionMark() {
        assertEquals("?#7", TypeName.of(new Value.Ref(7, "?#7"), null, KNOWN));
    }

    @Test
    void stringsAreJavaLangString() {
        assertEquals("java.lang.String", TypeName.of(new Value.Str("hi", false), null, KNOWN));
        assertEquals("java.lang.String", TypeName.of(new Value.Str("trunc", true), "I", KNOWN));
    }

    @Test
    void nullVoidAbsentAndMissing() {
        assertEquals("null", TypeName.of(new Value.Null(), null, KNOWN));
        assertEquals("void", TypeName.of(new Value.Primitive("void"), null, KNOWN));
        assertEquals("<absent>", TypeName.of(new Value.Absent(), null, KNOWN));
        assertNull(TypeName.of(null, "I", KNOWN));
    }

    @Test
    void unambiguousPrimitiveTexts() {
        assertEquals("boolean", TypeName.of(new Value.Primitive("true"), null, KNOWN));
        assertEquals("boolean", TypeName.of(new Value.Primitive("false"), null, KNOWN));
        assertEquals("char", TypeName.of(new Value.Primitive("'x'"), null, KNOWN));
        // The rendered text is authoritative even against a contradicting descriptor.
        assertEquals("boolean", TypeName.of(new Value.Primitive("true"), "Z", KNOWN));
    }

    @Test
    void descriptorDecidesNumericWidth() {
        assertEquals("long", TypeName.of(new Value.Primitive("5"), "J", KNOWN));
        assertEquals("short", TypeName.of(new Value.Primitive("-3"), "S", KNOWN));
        assertEquals("byte", TypeName.of(new Value.Primitive("7"), "B", KNOWN));
        assertEquals("int", TypeName.of(new Value.Primitive("7"), "I", KNOWN));
        assertEquals("float", TypeName.of(new Value.Primitive("1.5"), "F", KNOWN));
        assertEquals("double", TypeName.of(new Value.Primitive("1.5"), "D", KNOWN));
    }

    @Test
    void numbersWithoutDescriptorFallBackToIntAndDouble() {
        assertEquals("int", TypeName.of(new Value.Primitive("42"), null, KNOWN));
        assertEquals("int", TypeName.of(new Value.Primitive("-42"), "", KNOWN));
        // A boxed value's reference descriptor does not decide the width either.
        assertEquals("int", TypeName.of(new Value.Primitive("42"), "Ljava/lang/Integer;", KNOWN));
        assertEquals("double", TypeName.of(new Value.Primitive("1.0"), null, KNOWN));
        assertEquals("double", TypeName.of(new Value.Primitive("1.0E10"), null, KNOWN));
        assertEquals("double", TypeName.of(new Value.Primitive("-4.9E-324"), null, KNOWN));
        assertEquals("double", TypeName.of(new Value.Primitive("NaN"), null, KNOWN));
        assertEquals("double", TypeName.of(new Value.Primitive("-Infinity"), null, KNOWN));
    }

    @Test
    void unrecognisableTextIsReturnedAsIs() {
        assertEquals("<bad kind 9>", TypeName.of(new Value.Primitive("<bad kind 9>"), null, KNOWN));
    }

    @Test
    void tagCarriedTypeNameWinsOverEveryHeuristic() {
        // §27.4: an engine-produced primitive names its exact type even for nested fields whose
        // slot descriptor is unknown — hex/date-decorated text no longer defeats the type.
        assertEquals("long", TypeName.of(new Value.Primitive("11  0x000000000000000B", "long"), null, KNOWN));
        assertEquals("byte", TypeName.of(new Value.Primitive("72  0x48  'H'", "byte"), null, KNOWN));
        assertEquals("float", TypeName.of(new Value.Primitive("1.5", "float"), null, KNOWN));
        // The tag name also beats a (theoretically contradicting) descriptor — it is exact.
        assertEquals("long", TypeName.of(new Value.Primitive("5", "long"), "I", KNOWN));
        // Without a tag name, the old text/descriptor heuristics still apply (tests above).
        assertEquals("int", TypeName.of(new Value.Primitive("42"), null, KNOWN));
    }
}
