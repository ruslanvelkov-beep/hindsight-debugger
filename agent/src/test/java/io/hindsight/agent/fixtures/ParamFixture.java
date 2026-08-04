// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.agent.fixtures;

/**
 * Instrumentation fixture for the §5 {@code #p} parameter-name suffix: an instance and a
 * static variant of the same {@code (long, String)} shape, so the tests pin the exact
 * first-instruction LVT slots — instance parameters start at slot 1 ({@code this} owns 0),
 * statics at 0, and the leading {@code long} occupies two slots either way.
 *
 * <p>Compiled by the test toolchain with full debug info (like {@link Fixture}), so the
 * LocalVariableTable names are present; the no-debug-info case is exercised by stripping
 * these same bytes with {@code ClassReader.SKIP_DEBUG}.
 */
public final class ParamFixture {

    /** Instance shape: amount at slot 1 (long, slots 1-2), memo at slot 3. */
    public int pay(long amount, String memo) {
        return (int) amount + memo.length();
    }

    /** Static shape: amount at slot 0 (long, slots 0-1), memo at slot 2. */
    public static int payStatic(long amount, String memo) {
        return (int) amount + memo.length();
    }
}
