// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure lexical outline of one Java class for the §28.8 Structure window: given a file's lines
 * and a line of interest, finds the ENCLOSING type (the innermost type whose braces span the
 * line, else the first type in the file) and lists its members — fields, constructors, methods
 * and inner types — each with its declaration line.
 *
 * <p>This is a heuristic, single-pass scanner, not a parser: comments, string/char literals and
 * text blocks are blanked first (structure characters preserved), then brace depth tracking
 * locates type spans and member-level declarations. It is deliberately tolerant — a line it
 * cannot classify is simply not listed — and terminal-free so it is unit-testable.
 */
public final class JavaStructure {

    /** One outlined member: its (field/method/type) name, 1-based declaration line, decl text. */
    public record Member(String name, int line, String text) {}

    /**
     * The outlined class.
     *
     * @param className the type's simple name
     * @param classLine the type declaration's 1-based line
     * @param fields field declarations, in appearance order
     * @param constructors constructor declarations, in appearance order
     * @param methods method declarations, in appearance order
     * @param innerTypes directly nested type declarations, in appearance order
     */
    public record ClassStructure(
            String className,
            int classLine,
            List<Member> fields,
            List<Member> constructors,
            List<Member> methods,
            List<Member> innerTypes) {}

    private static final Pattern TYPE_DECL =
            Pattern.compile("(?:^|[^\\w.])(class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern METHOD_DECL = Pattern.compile("(\\w+)\\s*\\(");
    private static final Pattern FIELD_DECL = Pattern.compile("(\\w+)\\s*(?:=|;|,)");
    private static final List<String> NOT_METHOD_NAMES = List.of(
            "if", "for", "while", "switch", "catch", "return", "new", "synchronized",
            "super", "this", "throw", "assert", "do", "else", "try", "yield");

    private JavaStructure() {}

    /**
     * Outlines the type enclosing {@code aroundLine} (1-based) — the innermost type whose brace
     * span covers the line, or the FIRST type in the file when the line precedes/escapes all of
     * them. {@code null} when the file declares no type at all.
     *
     * @param lines the file's lines, first line at index 0
     * @param aroundLine the 1-based line whose enclosing class is wanted
     */
    public static ClassStructure parse(final List<String> lines, final int aroundLine) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        List<String> code = blankNonCode(lines);
        List<int[]> types = typeSpans(code); // {startLine, endLine, depthInside, nameStart..}: see below
        if (types.isEmpty()) {
            return null;
        }
        int[] chosen = null;
        for (int[] t : types) {
            boolean covers = aroundLine >= t[0] && aroundLine <= t[1];
            // Innermost cover wins: spans are recorded in opening order, so a later covering
            // span is nested inside (or after) an earlier one — keep the deepest cover.
            if (covers && (chosen == null || t[2] >= chosen[2])) {
                chosen = t;
            }
        }
        if (chosen == null) {
            chosen = types.get(0);
        }
        return outline(lines, code, chosen);
    }

    /**
     * Every type declaration's {@code {startLine, endLine, depthAtBody, declLine}} — depthAtBody
     * is the brace depth INSIDE the type's body, so members of the type sit at exactly that
     * depth. The span starts at the declaration line and ends where its opening brace closes.
     */
    private static List<int[]> typeSpans(final List<String> code) {
        List<int[]> out = new ArrayList<>();
        List<int[]> open = new ArrayList<>(); // pending: {declLine, depthAtBody, outIndex}
        int depth = 0;
        for (int l = 1; l <= code.size(); l++) {
            String text = code.get(l - 1);
            Matcher m = TYPE_DECL.matcher(text);
            boolean declared = m.find();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{') {
                    depth++;
                    if (declared) {
                        // The first '{' at/after a type declaration opens its body.
                        out.add(new int[] {l, code.size(), depth, l});
                        open.add(new int[] {l, depth, out.size() - 1});
                        declared = false;
                    }
                } else if (c == '}') {
                    for (int oi = open.size() - 1; oi >= 0; oi--) {
                        if (open.get(oi)[1] == depth) {
                            out.get(open.get(oi)[2])[1] = l; // the body closes here
                            open.remove(oi);
                            break;
                        }
                    }
                    depth = Math.max(0, depth - 1);
                }
            }
            if (declared) {
                // Declaration whose '{' is on a LATER line: mark pending at the depth the brace
                // will open. Handled by carrying the flag forward via a synthetic entry.
                out.add(new int[] {l, code.size(), depth + 1, l});
                open.add(new int[] {l, depth + 1, out.size() - 1});
                // The next '{' at this depth is consumed implicitly by the depth++ above on a
                // later line; the span end is still matched by depth in the '}' branch.
            }
        }
        return out;
    }

    /** Collects the chosen type's members at exactly its body depth. */
    private static ClassStructure outline(final List<String> lines, final List<String> code, final int[] type) {
        int declLine = type[3];
        int endLine = type[1];
        int bodyDepth = type[2];
        Matcher declMatch = TYPE_DECL.matcher(code.get(declLine - 1));
        String className = declMatch.find() ? declMatch.group(2) : "?";
        boolean isEnum = className.equals("?") ? false : "enum".equals(declMatch.group(1));
        List<Member> fields = new ArrayList<>();
        List<Member> ctors = new ArrayList<>();
        List<Member> methods = new ArrayList<>();
        List<Member> inner = new ArrayList<>();

        int depth = depthBefore(code, declLine);
        // §28.8: skip lines that CONTINUE a multi-line field initializer (a member-level `=`
        // without a `;` on its line) — the continuation `compute();` of `int N = compute();` is
        // not a member; and, in an enum, the leading constant list up to the first `;`.
        boolean midInitializer = false;
        boolean enumConstants = isEnum;
        for (int l = declLine; l <= Math.min(endLine, code.size()); l++) {
            String text = code.get(l - 1);
            boolean memberLevel = depth == bodyDepth;
            boolean annotation = text.strip().startsWith("@");
            if (l > declLine && memberLevel && !text.isBlank() && !annotation
                    && !midInitializer && !enumConstants) {
                Matcher tm = TYPE_DECL.matcher(text);
                if (tm.find()) {
                    inner.add(new Member(tm.group(2), l, lines.get(l - 1).strip()));
                } else {
                    Member m = methodAt(text, l, lines, className);
                    if (m != null) {
                        (m.name().equals(className) ? ctors : methods).add(m);
                    } else {
                        Member f = fieldAt(text, l, lines);
                        if (f != null) {
                            fields.add(f);
                        }
                    }
                }
            }
            // §28.8: update the continuation / enum-constant state at member level (a `;` at the
            // body's own depth closes either) — annotation lines are inert.
            if (memberLevel && !annotation) {
                boolean semicolon = text.indexOf(';') >= 0;
                if (enumConstants) {
                    if (semicolon) {
                        enumConstants = false;
                    }
                } else if (midInitializer) {
                    if (semicolon) {
                        midInitializer = false;
                    }
                } else if (assignsWithoutTerminator(text)) {
                    midInitializer = true;
                }
            }
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
            }
        }
        return new ClassStructure(className, declLine, List.copyOf(fields), List.copyOf(ctors),
                List.copyOf(methods), List.copyOf(inner));
    }

    /** Brace depth at the START of {@code line} (1-based). */
    private static int depthBefore(final List<String> code, final int line) {
        int depth = 0;
        for (int l = 1; l < line; l++) {
            String text = code.get(l - 1);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
            }
        }
        return depth;
    }

    private static String typeNameAt(final String codeLine) {
        Matcher m = TYPE_DECL.matcher(codeLine);
        return m.find() ? m.group(2) : "?";
    }

    /** A method/constructor declaration on this member-level line, or {@code null}. */
    private static Member methodAt(final String text, final int line, final List<String> lines, final String cls) {
        String t = text.strip();
        if (t.startsWith("@") || t.startsWith("=") || t.isEmpty()) {
            return null;
        }
        // §28.8: an assignment (`… = …( …)`) is a field initializer / statement, not a method
        // declaration — a `new Foo(…)` or a call on its right side must not read as a member.
        int assign = topLevelAssign(text);
        Matcher m = METHOD_DECL.matcher(text);
        while (m.find()) {
            int at = m.start(1);
            if (assign >= 0 && at > assign) {
                return null; // the name is on the value side of an assignment
            }
            String name = m.group(1);
            if (NOT_METHOD_NAMES.contains(name)) {
                return null; // a control-flow statement, not a declaration
            }
            // A declaration's name is preceded by a type/modifier (or nothing but whitespace for
            // compact records); an assignment/call like x = foo( or foo.bar( is not a decl.
            int p = at - 1;
            while (p >= 0 && Character.isWhitespace(text.charAt(p))) {
                p--;
            }
            if (p >= 0 && (text.charAt(p) == '.' || text.charAt(p) == '=' || text.charAt(p) == '('
                    || text.charAt(p) == ',' || text.charAt(p) == '!' || text.charAt(p) == '&'
                    || text.charAt(p) == '|' || text.charAt(p) == '+' || text.charAt(p) == '-'
                    || text.charAt(p) == '*' || text.charAt(p) == '/' || text.charAt(p) == '<'
                    || text.charAt(p) == '>' || text.charAt(p) == ':' || text.charAt(p) == '?')) {
                return null;
            }
            // §28.8: a name immediately following the word `new` is an instantiation, not a decl.
            if (precededByWord(text, at, "new")) {
                return null;
            }
            // Constructors have NO preceding type word other than modifiers; methods do. Both are
            // accepted here — the caller splits on name == class.
            return new Member(name, line, lines.get(line - 1).strip());
        }
        return null;
    }

    /** §28.8: the index of a top-level assignment {@code =} (not {@code == <= >= != +=}…), or -1. */
    private static int topLevelAssign(final String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '=') {
                continue;
            }
            char prev = i > 0 ? text.charAt(i - 1) : ' ';
            char next = i + 1 < text.length() ? text.charAt(i + 1) : ' ';
            if (next != '=' && "=<>!+-*/%&|^~".indexOf(prev) < 0) {
                return i;
            }
        }
        return -1;
    }

    /** §28.8: whether the identifier starting at {@code at} directly follows the word {@code kw}. */
    private static boolean precededByWord(final String text, final int at, final String kw) {
        int p = at - 1;
        while (p >= 0 && Character.isWhitespace(text.charAt(p))) {
            p--;
        }
        int end = p + 1;
        while (p >= 0 && Character.isJavaIdentifierPart(text.charAt(p))) {
            p--;
        }
        return text.substring(p + 1, end).equals(kw);
    }

    /** §28.8: whether {@code text} opens a field initializer (a top-level {@code =}) with no {@code ;}. */
    private static boolean assignsWithoutTerminator(final String text) {
        int assign = topLevelAssign(text);
        return assign >= 0 && text.indexOf(';', assign) < 0;
    }

    /** A field declaration on this member-level line, or {@code null}. */
    private static Member fieldAt(final String text, final int line, final List<String> lines) {
        String t = text.strip();
        if (t.isEmpty() || t.startsWith("@") || t.startsWith("}") || t.startsWith("{")
                || t.startsWith("case ") || t.startsWith("default")) {
            return null;
        }
        // A field line ends its declaration (or starts its initializer) without any '(' before
        // the terminator — method calls/decls were already claimed by methodAt.
        int eq = text.indexOf('=');
        int semi = text.indexOf(';');
        int stop = eq >= 0 && (semi < 0 || eq < semi) ? eq : semi;
        if (stop < 0) {
            return null;
        }
        String head = text.substring(0, stop);
        if (head.indexOf('(') >= 0 || head.indexOf(')') >= 0) {
            return null;
        }
        // The declared name is the LAST identifier before the terminator.
        Matcher m = Pattern.compile("(\\w+)\\s*$").matcher(head.stripTrailing());
        if (!m.find()) {
            return null;
        }
        String name = m.group(1);
        // At least type + name: a lone identifier (enum constant, label) is not a field.
        String beforeName = head.substring(0, m.start(1)).strip();
        if (beforeName.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return null;
        }
        return new Member(name, line, lines.get(line - 1).strip());
    }

    /**
     * Blanks comments, string/char literals and text blocks (structure characters preserved,
     * lengths kept so columns stay aligned) — the scanner's whole view of the file.
     */
    static List<String> blankNonCode(final List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        boolean inBlock = false; // /* … */
        boolean inText = false; // """ … """
        for (String raw : lines) {
            StringBuilder b = new StringBuilder(raw.length());
            int i = 0;
            while (i < raw.length()) {
                char c = raw.charAt(i);
                if (inBlock) {
                    if (c == '*' && i + 1 < raw.length() && raw.charAt(i + 1) == '/') {
                        inBlock = false;
                        b.append("  ");
                        i += 2;
                    } else {
                        b.append(' ');
                        i++;
                    }
                } else if (inText) {
                    if (c == '"' && raw.startsWith("\"\"\"", i)) {
                        inText = false;
                        b.append("   ");
                        i += 3;
                    } else {
                        b.append(' ');
                        i++;
                    }
                } else if (c == '/' && i + 1 < raw.length() && raw.charAt(i + 1) == '/') {
                    while (i < raw.length()) {
                        b.append(' ');
                        i++;
                    }
                } else if (c == '/' && i + 1 < raw.length() && raw.charAt(i + 1) == '*') {
                    inBlock = true;
                    b.append("  ");
                    i += 2;
                } else if (c == '"' && raw.startsWith("\"\"\"", i)) {
                    inText = true;
                    b.append("   ");
                    i += 3;
                } else if (c == '"' || c == '\'') {
                    char quote = c;
                    b.append(' ');
                    i++;
                    while (i < raw.length()) {
                        char d = raw.charAt(i);
                        b.append(' ');
                        i++;
                        if (d == '\\') {
                            if (i < raw.length()) {
                                b.append(' ');
                                i++;
                            }
                        } else if (d == quote) {
                            break;
                        }
                    }
                } else {
                    b.append(c);
                    i++;
                }
            }
            out.add(b.toString());
        }
        return out;
    }
}
