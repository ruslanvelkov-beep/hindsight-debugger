// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Parsed command-line arguments for the viewer (VIEWER.md §1, §26.7).
 *
 * <p>Kept as a small immutable value object with a pure {@link #parse(String[])}
 * factory so the argument grammar can be unit-tested without constructing a
 * terminal or opening a recording — the whole point of factoring it out of
 * {@link Main}. Parsing is strict about structure (a missing {@code --dir}, an
 * unknown flag, a flag without a value, a non-integer {@code --tid}) because a
 * misparsed invocation should fail fast with a clear message rather than open the
 * wrong recording; source-root existence, by contrast, is intentionally <em>not</em>
 * validated here (missing roots are non-fatal per §1 and merely show a placeholder).
 *
 * @param dir the recording directory ({@code --dir}, required)
 * @param sources explicit package roots gathered from every {@code --source} colon list
 * @param repos repository trees to auto-scan for source roots ({@code --repo}, repeatable)
 * @param tid the initial thread to focus ({@code --tid}), or empty for the engine default
 * @param seq the record (global seq) to open on ({@code --seq}, §26.7), or empty
 * @param last open on the last line-traced record ({@code --last}, §26.7)
 */
public record CliArgs(
        Path dir, List<Path> sources, List<Path> repos, OptionalInt tid, OptionalLong seq, boolean last) {

    /** Usage string printed on a parse error or {@code --help}. */
    public static final String USAGE =
            "usage: viewer --dir <recordingDir> [--source <root1:root2:...>]... "
                    + "[--repo <dir>]... [--tid N] [--seq N | --last]";

    /** Thrown when the argument vector does not conform to §1; message is user-facing. */
    public static final class CliException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** Creates an exception whose message is shown to the user alongside {@link #USAGE}. */
        public CliException(final String message) {
            super(message);
        }
    }

    /**
     * Parses an argument vector into a {@link CliArgs}, throwing {@link CliException} with a
     * user-facing message on any structural violation ({@code --seq} and {@code --last} are
     * mutually exclusive, §26.7).
     *
     * @param argv the raw program arguments
     * @return the parsed arguments
     * @throws CliException if the arguments are missing {@code --dir}, malformed, or unknown
     */
    public static CliArgs parse(final String[] argv) {
        Path dir = null;
        List<Path> sources = new ArrayList<>();
        List<Path> repos = new ArrayList<>();
        OptionalInt tid = OptionalInt.empty();
        OptionalLong seq = OptionalLong.empty();
        boolean last = false;

        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            switch (a) {
                case "--dir" -> dir = Path.of(value(argv, ++i, a));
                case "--source" -> {
                    for (String seg : value(argv, ++i, a).split(":", -1)) {
                        if (!seg.isEmpty()) {
                            sources.add(Path.of(seg));
                        }
                    }
                }
                case "--repo" -> repos.add(Path.of(value(argv, ++i, a)));
                case "--tid" -> {
                    String v = value(argv, ++i, a);
                    try {
                        tid = OptionalInt.of(Integer.parseInt(v.trim()));
                    } catch (NumberFormatException e) {
                        throw new CliException("--tid must be an integer, got: " + v);
                    }
                }
                case "--seq" -> {
                    String v = value(argv, ++i, a);
                    try {
                        seq = OptionalLong.of(Long.parseLong(v.trim()));
                    } catch (NumberFormatException e) {
                        throw new CliException("--seq must be an integer record number, got: " + v);
                    }
                }
                case "--last" -> last = true;
                default -> throw new CliException("unknown argument: " + a);
            }
        }
        if (dir == null) {
            throw new CliException("--dir <recordingDir> is required");
        }
        if (seq.isPresent() && last) {
            throw new CliException("--seq and --last are mutually exclusive");
        }
        return new CliArgs(dir, List.copyOf(sources), List.copyOf(repos), tid, seq, last);
    }

    /** Reads the value token at {@code i}, or fails with a message naming the {@code flag}. */
    private static String value(final String[] argv, final int i, final String flag) {
        if (i >= argv.length) {
            throw new CliException("missing value for " + flag);
        }
        return argv[i];
    }
}
