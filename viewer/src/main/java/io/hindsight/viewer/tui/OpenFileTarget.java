// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

/**
 * Parses the input of the "open a project source file" dialog (VIEWER.md §11.8): a
 * dotted class name ({@code a.b.C}), a {@code .java} filesystem path (absolute or
 * relative to a source root), either optionally suffixed with {@code :<line>}.
 *
 * <p>Purely lexical — it decides <em>how</em> to resolve (as a class name via the
 * package path, or as a path relative to the roots / the filesystem) and extracts
 * the requested line, but performs no I/O. That keeps the (fiddly) grammar — telling
 * a dotted class from a dotted path, splitting a trailing {@code :line} without
 * eating a Windows drive letter — unit-testable, with the actual resolution left to
 * {@code SourcePath} in the TUI.
 */
public final class OpenFileTarget {

    /**
     * A parsed open-file request.
     *
     * @param path whether this is a filesystem path ({@code true}) or a dotted class name
     * @param classBinary the dotted class binary name when {@code !path}, else {@code null}
     * @param fileName the bare source file name (e.g. {@code Bar.java}) — the last path segment,
     *     or {@code SimpleClass.java} for a class name
     * @param rawPath the path text as typed when {@code path}, else {@code null}
     * @param line the requested 1-based line, or {@code -1} when none was given
     */
    public record Target(boolean path, String classBinary, String fileName, String rawPath, int line) {}

    private OpenFileTarget() {}

    /**
     * Parses {@code input} into a {@link Target}, or {@code null} when it is blank.
     *
     * <p>A trailing {@code :<digits>} is taken as the line unless it is the {@code :} of a Windows
     * drive letter (a single leading letter before {@code :}). Input that ends in {@code .java} or
     * contains a path separator is treated as a path; anything else is treated as a dotted class
     * name (its simple name plus {@code .java} becomes the file name).
     *
     * @param input the raw dialog text
     * @return the parsed target, or {@code null} if {@code input} is blank
     */
    public static Target parse(final String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim();
        if (s.isEmpty()) {
            return null;
        }
        int line = -1;
        int colon = s.lastIndexOf(':');
        if (colon > 1 && colon < s.length() - 1 && isDigits(s.substring(colon + 1))) {
            line = Integer.parseInt(s.substring(colon + 1));
            s = s.substring(0, colon);
        }
        boolean isPath = s.endsWith(".java") || s.indexOf('/') >= 0 || s.indexOf('\\') >= 0;
        if (isPath) {
            String norm = s.replace('\\', '/');
            int slash = norm.lastIndexOf('/');
            String fileName = slash >= 0 ? norm.substring(slash + 1) : norm;
            return new Target(true, null, fileName, s, line);
        }
        int dot = s.lastIndexOf('.');
        String simple = dot >= 0 ? s.substring(dot + 1) : s;
        return new Target(false, s, simple + ".java", null, line);
    }

    private static boolean isDigits(final String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
