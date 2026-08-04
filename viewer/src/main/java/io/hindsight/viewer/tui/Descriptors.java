// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM method-descriptor prettifiers for the Stack window (VIEWER.md §6.3).
 *
 * <p>The engine hands frames their raw descriptor (e.g. {@code (Lcom/foo/Bar;I)V});
 * the Stack window wants the IntelliJ-style {@code Class.method(Bar, int)}. This
 * pure helper does that parameter simplification (and nothing semantic) so it can
 * be tested directly. It mirrors the descriptor grammar the engine already parses
 * for locals, kept independent here because the engine's copy is package-private.
 */
public final class Descriptors {

    private Descriptors() {}

    /**
     * Splits a method descriptor into its parameter field descriptors, in order; returns an empty
     * list for {@code null} or a descriptor without a valid parameter section.
     */
    public static List<String> paramDescriptors(final String desc) {
        List<String> out = new ArrayList<>();
        if (desc == null) {
            return out;
        }
        int open = desc.indexOf('(');
        int close = desc.indexOf(')');
        if (open < 0 || close < 0 || close < open) {
            return out;
        }
        int p = open + 1;
        while (p < close) {
            int start = p;
            char c = desc.charAt(p);
            while (c == '[' && p < close) {
                p++;
                c = desc.charAt(p);
            }
            if (c == 'L') {
                int semi = desc.indexOf(';', p);
                if (semi < 0 || semi > close) {
                    break;
                }
                p = semi + 1;
            } else {
                p++;
            }
            out.add(desc.substring(start, p));
        }
        return out;
    }

    /**
     * Renders one field descriptor as a simple Java type name: primitives spelt out
     * ({@code I}→{@code int}), arrays suffixed with {@code []}, reference types reduced to their
     * simple name ({@code Lcom/foo/Bar;}→{@code Bar}, inner classes cut at {@code $}).
     */
    public static String simpleType(final String d) {
        if (d == null || d.isEmpty()) {
            return "?";
        }
        int dims = 0;
        while (dims < d.length() && d.charAt(dims) == '[') {
            dims++;
        }
        String suffix = "[]".repeat(dims);
        if (dims >= d.length()) {
            return d;
        }
        char c = d.charAt(dims);
        String base = switch (c) {
            case 'I' -> "int";
            case 'J' -> "long";
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'V' -> "void";
            case 'L' -> {
                int semi = d.indexOf(';', dims);
                String internal = semi >= 0 ? d.substring(dims + 1, semi) : d.substring(dims + 1);
                int cut = Math.max(internal.lastIndexOf('/'), internal.lastIndexOf('$'));
                yield cut >= 0 && cut < internal.length() - 1 ? internal.substring(cut + 1) : internal;
            }
            default -> String.valueOf(c);
        };
        return base + suffix;
    }

    /**
     * Renders the parameter list of a method descriptor, e.g. {@code (Bar, int)} or {@code ()}.
     * The return type is intentionally omitted — the Stack row already shows the file/line.
     */
    public static String simplifyParams(final String desc) {
        List<String> params = paramDescriptors(desc);
        StringBuilder b = new StringBuilder("(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(simpleType(params.get(i)));
        }
        return b.append(')').toString();
    }
}
