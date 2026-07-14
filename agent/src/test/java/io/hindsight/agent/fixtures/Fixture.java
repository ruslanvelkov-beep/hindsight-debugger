// SPDX-License-Identifier: Apache-2.0
package io.hindsight.agent.fixtures;

import java.util.function.IntSupplier;

/**
 * Instrumentation fixture exercising the local-variable surface of the agent: every primitive
 * category and reference locals, a loop (so the classfile carries stack-map frames), an IINC,
 * a lambda body, recursion and an exceptional unwind.
 *
 * <p>Compiled by the test toolchain (JDK 25, classfile v69) with full debug info, so the LVT
 * entries the splicer depends on are present — deliberately the same shape as the production
 * classes the agent targets. Values are deterministic; tests assert them byte-for-byte from
 * the recording.
 */
public final class Fixture {

    /** Keeps javac from discarding computed values. */
    public static volatile int sink;

    private Fixture() {}

    /** int/long/float/double/String/Object locals, a loop with IINC, returns "hello3". */
    public static String locals() {
        int total = 0;
        for (int i = 0; i < 3; i++) {
            total = total + i;
        }
        long big = 1_234_567_890_123L;
        float ratio = 2.5f;
        double precise = 3.25;
        String label = "hello";
        Object box = new StringBuilder("sb");
        consume((int) big + (int) ratio + (int) precise + label.length() + box.hashCode());
        return label + total;
    }

    /** A synthetic {@code lambda$...} body with its own local; returns 41. */
    public static int lambda() {
        final IntSupplier supplier = () -> {
            int inner = 41;
            return inner;
        };
        return supplier.getAsInt();
    }

    /** Recursion: nested ENTERs on one site; returns n + (n-1) + ... + 1. */
    public static int recurse(int n) {
        if (n <= 0) {
            return 0;
        }
        return n + recurse(n - 1);
    }

    /** Always unwinds exceptionally. */
    public static void throwing() {
        throw new IllegalStateException("boom");
    }

    /** Calls {@link #throwing()} and swallows; returns 7. */
    public static int catcher() {
        try {
            throwing();
            return -1;
        } catch (IllegalStateException e) {
            return 7;
        }
    }

    private static void consume(int value) {
        sink = value;
    }
}
