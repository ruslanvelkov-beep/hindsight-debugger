// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import io.hindsight.viewer.engine.Value;
import java.util.function.LongFunction;
import java.util.regex.Pattern;

/**
 * The pure §14.4 type-naming helper behind the focus-sensitive info line: maps a
 * reconstructed {@link Value} to the FULL type text the status row shows when the
 * Locals window is focused.
 *
 * <p>References resolve through the caller-supplied id → class-binary lookup (the
 * recording's first-seen class), rendering as {@code com.foo.Bar#id} or
 * {@code ?#id} when unknown; strings are always {@code java.lang.String};
 * {@code null}/{@code void}/{@code <absent>} render as themselves. Inline
 * primitives render as their tag name ({@code int}, {@code boolean}, ...). A
 * {@link Value.Primitive} carries only its rendered text, so the tag is recovered
 * from that text where it is unambiguous ({@code true}/{@code false},
 * {@code 'c'}, {@code void}) and from the slot's JVM descriptor when the caller
 * knows one (top-level locals rows); a bare integral otherwise names {@code int}
 * and a bare floating-point text {@code double} — the width variants are not
 * distinguishable from the rendering alone.
 *
 * <p>Kept terminal-free and engine-free (a {@link LongFunction} stands in for the
 * recording) so every mapping is unit-testable.
 */
public final class TypeName {

    private static final Pattern INTEGRAL = Pattern.compile("-?\\d+");
    private static final Pattern FLOATING = Pattern.compile("-?(?:\\d+\\.\\d+(?:E-?\\d+)?|Infinity)|NaN");

    private TypeName() {}

    /**
     * The full type text for {@code value}, or {@code null} when there is nothing to name (a
     * {@code null} value — the caller falls back to the default record info).
     *
     * @param value the selected row's value
     * @param descriptor the JVM descriptor of the value's slot when known (top-level locals
     *     rows), else {@code null} or empty; only a primitive descriptor is consulted
     * @param classNameOf resolves an object id to its runtime class binary name ({@code null}
     *     when the recording does not know the id)
     */
    public static String of(final Value value, final String descriptor, final LongFunction<String> classNameOf) {
        return switch (value) {
            case null -> null;
            case Value.Ref ref -> {
                String cls = classNameOf.apply(ref.objectId());
                yield (cls != null ? cls : "?") + "#" + ref.objectId();
            }
            case Value.Str ignored -> "java.lang.String";
            case Value.Null ignored -> "null";
            case Value.Absent ignored -> "<absent>";
            // §27.4: the tag-carried type is exact — nested fields name their type even without
            // a slot descriptor; the text/descriptor heuristics remain for hand-built values.
            case Value.Primitive p -> p.typeName() != null ? p.typeName() : primitiveName(p.rendered(), descriptor);
        };
    }

    /**
     * Names an inline primitive from its rendered text plus (optionally) its slot descriptor:
     * text decides the unambiguous tags, the descriptor decides integral/floating width, and a
     * plain number without a descriptor falls back to {@code int}/{@code double}. Unrecognisable
     * text (the engine's {@code <bad ...>} sentinels) is returned as-is.
     */
    static String primitiveName(final String rendered, final String descriptor) {
        if ("true".equals(rendered) || "false".equals(rendered)) {
            return "boolean";
        }
        if (!rendered.isEmpty() && rendered.charAt(0) == '\'') {
            return "char";
        }
        if ("void".equals(rendered)) {
            return "void";
        }
        if ("null".equals(rendered)) {
            return "null";
        }
        String fromDescriptor = primitiveDescriptorName(descriptor);
        if (fromDescriptor != null) {
            return fromDescriptor;
        }
        if (INTEGRAL.matcher(rendered).matches()) {
            return "int";
        }
        if (FLOATING.matcher(rendered).matches()) {
            return "double";
        }
        return rendered;
    }

    /** The primitive type a single-character JVM descriptor names, or {@code null}. */
    private static String primitiveDescriptorName(final String descriptor) {
        if (descriptor == null || descriptor.length() != 1) {
            return null;
        }
        return switch (descriptor.charAt(0)) {
            case 'I' -> "int";
            case 'J' -> "long";
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'V' -> "void";
            default -> null;
        };
    }
}
