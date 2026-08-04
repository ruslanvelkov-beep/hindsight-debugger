// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.runtime;

/**
 * Test-only bridge into the hooks runtime's package-private lifecycle seams.
 *
 * <p>The agent's transformer tests need the real runtime: boot into a temp dir, run
 * instrumented fixtures, then flush everything to disk so the bytes can be parsed. The flush
 * lives in {@code HindsightRuntime.shutdownForTests()} — deliberately package-private so
 * production code cannot call it — and the hooks module's own tests reach it by living in the
 * same package. This class does the same from the agent module's test sources: both class
 * trees load into the unnamed module of the test classloader, so the package-private access
 * is legal at compile time and at run time.
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
