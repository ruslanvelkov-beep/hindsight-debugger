// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The pure lexical heuristic behind the {@code v} variable-history key (VIEWER.md
 * §13.5): given one source line, decide whether it looks like a class-variable
 * (field) declaration and, if so, which identifier(s) it declares.
 *
 * <p>Deliberately dumb — no parser, no symbol table — exactly as specified:
 * <ul>
 *   <li>strip a {@code //} comment, then consider the text before the first
 *       {@code =} (else before the first {@code ;}; neither → no candidates);
 *   <li>reject when that head contains {@code (} or {@code )} (method signatures
 *       and calls) or starts with a control keyword ({@code if}, {@code for},
 *       {@code return}, {@code new}, …);
 *   <li>tokenise with generics ({@code <…>}) and brackets ({@code []}) stripped
 *       and marker annotations dropped; require at least a type token AND a name
 *       token (≥ 2 identifier-ish tokens), every token identifier-ish (dots
 *       allowed for qualified types), and the last token a plain non-reserved
 *       identifier — that token is the declared name (so a bare assignment
 *       {@code x = 5;} has only one token and is rejected);
 *   <li>support multi-declarators by splitting on commas at angle-bracket depth 0
 *       ({@code int a, b;} → {@code a}, {@code b}): each continuation segment
 *       contributes its single plain identifier.
 * </ul>
 * The result is heuristic by design: it may accept a line that is not a field
 * declaration (e.g. a local variable declaration) — the engine query that follows
 * simply finds no field writes then.
 */
public final class FieldDecl {

    /** The §13.5 control keywords that disqualify a segment when they start it. */
    private static final Set<String> CONTROL = Set.of(
            "if", "for", "while", "return", "switch", "case", "throw", "new", "else", "do", "try",
            "catch", "import", "package", "class", "interface", "enum", "record", "assert", "yield");

    /** Java reserved words (plus literals and {@code _}) that can never be a declared name. */
    private static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null", "_");

    /** Identifier-ish token: an identifier, optionally dotted (qualified type like {@code Map.Entry}). */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.]*");

    /** A plain (undotted) identifier — the only shape a declared name may have. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private FieldDecl() {}

    /**
     * Parses one source line for field-declaration candidates (§13.5).
     *
     * @param line the raw source line ({@code null} tolerated)
     * @return the declared identifier(s) in source order, or an empty list when the line does not
     *     look like a declaration
     */
    public static List<String> parse(final String line) {
        if (line == null) {
            return List.of();
        }
        String text = stripComment(line);
        int eq = text.indexOf('=');
        int cut = eq >= 0 ? eq : text.indexOf(';');
        if (cut < 0) {
            return List.of();
        }
        String head = text.substring(0, cut);
        if (head.indexOf('(') >= 0 || head.indexOf(')') >= 0) {
            return List.of();
        }
        List<String> segments = splitTopLevel(head);
        List<String> first = tokensOf(segments.get(0));
        if (!isDeclarationHead(first)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add(first.get(first.size() - 1));
        for (int i = 1; i < segments.size(); i++) {
            List<String> t = tokensOf(segments.get(i));
            if (t.size() == 1 && isPlainName(t.get(0))) {
                out.add(t.get(0));
            }
        }
        return List.copyOf(out);
    }

    /** Everything before a {@code //} comment (a {@code //} inside a string is cut too — lexical only). */
    private static String stripComment(final String line) {
        int idx = line.indexOf("//");
        return idx >= 0 ? line.substring(0, idx) : line;
    }

    /** Splits on commas at angle-bracket depth 0 so a generic type's commas don't split declarators. */
    private static List<String> splitTopLevel(final String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>' && depth > 0) {
                depth--;
            }
            if (c == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /**
     * Whitespace tokens of a segment with balanced {@code <…>} regions and {@code []} pairs
     * stripped and {@code @Marker} annotation tokens dropped. A char that survives the stripping
     * but is not identifier-ish (an operator, brace, …) poisons its token, which
     * {@link #isDeclarationHead} then rejects.
     */
    private static List<String> tokensOf(final String segment) {
        String stripped = stripAngles(segment).replace("[]", "");
        List<String> out = new ArrayList<>();
        for (String t : stripped.trim().split("\\s+")) {
            if (!t.isEmpty() && !t.startsWith("@")) {
                out.add(t);
            }
        }
        return out;
    }

    /** Removes balanced {@code <…>} regions; an unmatched {@code >} at depth 0 is kept (poisons the token). */
    private static String stripAngles(final String s) {
        StringBuilder b = new StringBuilder(s.length());
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>' && depth > 0) {
                depth--;
            } else if (depth == 0) {
                b.append(c);
            }
        }
        return b.toString();
    }

    /**
     * The §13.5 head rule: ≥ 2 identifier-ish tokens (type + name), not starting with a control
     * keyword, every token identifier-ish, and the last token a plain non-reserved identifier.
     */
    private static boolean isDeclarationHead(final List<String> tokens) {
        if (tokens.size() < 2 || CONTROL.contains(tokens.get(0))) {
            return false;
        }
        for (String t : tokens) {
            if (!TOKEN.matcher(t).matches()) {
                return false;
            }
        }
        return isPlainName(tokens.get(tokens.size() - 1));
    }

    private static boolean isPlainName(final String t) {
        return NAME.matcher(t).matches() && !RESERVED.contains(t);
    }
}
