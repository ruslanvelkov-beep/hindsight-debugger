// SPDX-License-Identifier: Apache-2.0
package io.hindsight.runtime;

/**
 * Test-only bridge into the runtime's package-private lifecycle seams, for hooks tests that
 * must live OUTSIDE {@code io.hindsight.*} (the STACK_SNAPSHOT tests: a capture strips leading
 * recorder frames by that package prefix, so a test asserting that its own frames survive the
 * strip cannot share the package). Mirrors the agent module's identically-named bridge.
 */
public final class TestRuntime {

    private TestRuntime() {}

    /**
     * Runs the full shutdown sequence synchronously (steal worker buffers, drain the writer,
     * close channels) and unboots the runtime so the next test can boot fresh.
     */
    public static void shutdown() {
        HindsightRuntime.shutdownForTests();
    }
}
