// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.reader;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Test-only harness around {@link Main#run}: captures stdout/stderr and the
 * exit code so tests exercise the exact CLI surface a user sees, without
 * forking a JVM or touching {@code System.exit}.
 */
final class TestCli {

    private TestCli() {}

    /** Captured result of one CLI invocation. */
    record Result(int code, String out, String err) {

        /** Asserts helper: whole stdout as one string, for contains() checks. */
        boolean outContains(final String needle) {
            return out.contains(needle);
        }
    }

    static Result run(final String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code;
        try (PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8)) {
            code = Main.run(args, out, err);
        }
        return new Result(
                code,
                outBuf.toString(StandardCharsets.UTF_8),
                errBuf.toString(StandardCharsets.UTF_8));
    }
}
