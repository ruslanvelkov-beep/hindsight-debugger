// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import java.util.List;

/**
 * Pure lexical finder for a method's <em>declaration</em> line (VIEWER.md §18.3): when a
 * recorded frame is line-less (enter/exit-only — no {@code LINE} records), the Source
 * highlight sits on the method declaration instead of a body line that never executed.
 * Terminal-free so the scan is unit-testable.
 */
public final class DeclLine {

    /** How far above the body's first line the upward scan looks (§18.3: at most 30 lines). */
    static final int MAX_SCAN = 30;

    private DeclLine() {}

    /**
     * §19.1: whether the "method-declaration line + dark-green {@code EXEC_NOLINE_BG}" treatment
     * of §18.3 applies to the SELECTED frame — <em>only</em> when it is the innermost (execution)
     * frame ({@code selectedFrame == stackSize - 1}), is a recorded frame and executed no
     * {@code LINE} record of its own. A selected NON-innermost line-less frame instead keeps its
     * own reconstructed call-site line with the ordinary §12.3 frame marker (this method returns
     * {@code false} for it), fixing the v1.8 bug where selecting any outer callsOnly frame jumped
     * to the declaration line.
     *
     * @param selectedFrame the selected stack index (0 = outermost)
     * @param stackSize the number of frames in {@code stack()}
     * @param recorded whether the selected frame is a recorded (non-capture) frame
     * @param hasLines whether the selected frame executed a {@code LINE} of its own
     *     ({@link io.hindsight.viewer.engine.Engine#frameHasLines})
     * @return {@code true} iff the declaration-line + green treatment applies
     */
    public static boolean innermostLineless(
            final int selectedFrame, final int stackSize, final boolean recorded, final boolean hasLines) {
        return selectedFrame >= 0 && selectedFrame == stackSize - 1 && recorded && !hasLines;
    }

    /**
     * Finds the 1-based declaration line of {@code methodName} by scanning {@code lines}
     * upward from {@code min(bodyFirstLine, lines.size())} for at most {@value #MAX_SCAN}
     * lines (§18.3). A line matches when its text contains the method name — for a
     * constructor ({@code <init>}) the class's simple name — immediately followed by
     * optional whitespace and {@code '('}; lines whose first non-blank character is
     * {@code '@'} (annotations) and comment-only lines (first non-blank text starting
     * {@code //} or {@code *}) are skipped, which together with the {@code '('} requirement
     * guards against the name appearing in a nearby comment. When nothing matches (name
     * unknown, multi-line signature split before the {@code '('}, scan budget exhausted)
     * the fallback is {@code bodyFirstLine} itself.
     *
     * @param lines the shown file's lines, first line at index 0
     * @param methodName the frame's bare method name; {@code <init>} selects the constructor rule
     * @param simpleClassName the frame's simple class name, used for constructors
     * @param bodyFirstLine the 1-based line the frame reports (the site's first body line)
     * @return the 1-based declaration line, or {@code bodyFirstLine} when none is found
     */
    public static int find(
            final List<String> lines,
            final String methodName,
            final String simpleClassName,
            final int bodyFirstLine) {
        int found = findOrZero(lines, methodName, simpleClassName, bodyFirstLine, MAX_SCAN);
        return found != 0 ? found : bodyFirstLine;
    }

    /**
     * §28.2 core: the 1-based declaration line of {@code methodName} (constructors via the class's
     * simple name, exactly like {@link #find}) scanning upward from {@code fromLine} for at most
     * {@code maxScan} lines — or {@code 0} when nothing matches. Scanning upward means the NEAREST
     * declaration at/above the line wins, which is what disambiguates overloads: the enclosing
     * definition is the closest one above the executing line.
     *
     * @param maxScan the scan budget ({@link Integer#MAX_VALUE} = to the top of the file)
     */
    public static int findOrZero(
            final List<String> lines,
            final String methodName,
            final String simpleClassName,
            final int fromLine,
            final int maxScan) {
        String name = "<init>".equals(methodName) ? simpleClassName : methodName;
        if (lines == null || name == null || name.isEmpty()) {
            return 0;
        }
        int start = Math.min(fromLine, lines.size());
        int stop = maxScan >= start ? 1 : Math.max(1, start - (maxScan - 1));
        for (int l = start; l >= stop; l--) {
            String text = lines.get(l - 1);
            if (skip(text)) {
                continue;
            }
            if (containsNameBeforeParen(text, name)) {
                return l;
            }
        }
        return 0;
    }

    /**
     * §28.2: the 1-based declaration line of the TYPE named {@code simpleClassName} — the nearest
     * line at/above {@code fromLine} containing {@code class}/{@code interface}/{@code enum}/
     * {@code record} followed by the name as a whole word (comment/annotation lines skipped) —
     * or {@code 0} when none is found up to the top of the file.
     */
    public static int findTypeOrZero(final List<String> lines, final String simpleClassName, final int fromLine) {
        if (lines == null || simpleClassName == null || simpleClassName.isEmpty()) {
            return 0;
        }
        int start = Math.min(fromLine, lines.size());
        for (int l = start; l >= 1; l--) {
            String text = lines.get(l - 1);
            if (skip(text)) {
                continue;
            }
            if (declaresType(text, simpleClassName)) {
                return l;
            }
        }
        return 0;
    }

    /** Whether {@code text} declares the type: a type keyword then {@code name} as a whole word. */
    private static boolean declaresType(final String rawText, final String name) {
        String text = stripLiterals(rawText); // §28.2: not a `class Foo` mentioned in a string
        for (String kw : new String[] {"class ", "interface ", "enum ", "record "}) {
            int from = 0;
            int at;
            while ((at = text.indexOf(kw + name, from)) >= 0) {
                // keyword must start a word (not e.g. "subclass Foo")
                boolean kwOk = at == 0 || !Character.isJavaIdentifierPart(text.charAt(at - 1));
                int end = at + kw.length() + name.length();
                boolean nameOk = end >= text.length() || !Character.isJavaIdentifierPart(text.charAt(end));
                if (kwOk && nameOk) {
                    return true;
                }
                from = at + 1;
            }
        }
        return false;
    }

    /** §18.3 skip rule: annotation lines ({@code @} first) and comment-only lines ({@code //} or {@code *}). */
    private static boolean skip(final String text) {
        String t = text.strip();
        return t.startsWith("@") || t.startsWith("//") || t.startsWith("*");
    }

    /** Whether {@code text} contains {@code name} immediately followed by optional whitespace and {@code '('}. */
    private static boolean containsNameBeforeParen(final String rawText, final String name) {
        String text = stripLiterals(rawText); // §28.2: never match a name inside a string/comment
        int from = 0;
        int at;
        while ((at = text.indexOf(name, from)) >= 0) {
            from = at + 1;
            // Whole word: the char before must not continue an identifier (guards `preVisit`).
            if (at > 0 && Character.isJavaIdentifierPart(text.charAt(at - 1))) {
                continue;
            }
            // §28.2: a name preceded (after whitespace) by '.' is a method CALL, not a
            // declaration (`child.visit(` must not out-vote the real `void visit(`).
            int b = at - 1;
            while (b >= 0 && Character.isWhitespace(text.charAt(b))) {
                b--;
            }
            if (b >= 0 && text.charAt(b) == '.') {
                continue;
            }
            int p = at + name.length();
            while (p < text.length() && Character.isWhitespace(text.charAt(p))) {
                p++;
            }
            if (p < text.length() && text.charAt(p) == '(') {
                return true;
            }
        }
        return false;
    }

    /**
     * §28.2: blanks string/char literal contents and line comments on ONE line (structure
     * preserved, lengths kept) so a {@code class}/method name mentioned in a string or a
     * {@code //} comment never counts as a declaration. Block comments across lines are already
     * handled by {@link #skip} (a {@code *}-led continuation line is skipped whole).
     */
    static String stripLiterals(final String raw) {
        StringBuilder b = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '/' && i + 1 < raw.length() && raw.charAt(i + 1) == '/') {
                while (i < raw.length()) {
                    b.append(' ');
                    i++;
                }
            } else if (c == '"' || c == '\'') {
                char q = c;
                b.append(' ');
                i++;
                while (i < raw.length()) {
                    char d = raw.charAt(i);
                    b.append(' ');
                    i++;
                    if (d == '\\' && i < raw.length()) {
                        b.append(' ');
                        i++;
                    } else if (d == q) {
                        break;
                    }
                }
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }
}
