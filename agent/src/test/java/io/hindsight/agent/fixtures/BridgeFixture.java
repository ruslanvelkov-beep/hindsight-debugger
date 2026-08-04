// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.agent.fixtures;

/**
 * Instrumentation fixture for the synthetic-bridge exclusion of the flow pass. Implementing a
 * generic interface makes javac emit a {@code compareTo(Object)} <em>bridge</em>
 * ({@code ACC_BRIDGE | ACC_SYNTHETIC}) that erasure-casts and forwards to the real
 * {@code compareTo(BridgeFixture)}.
 *
 * <p>The wrinkle the fixture exists to pin: javac gives that bridge a LineNumberTable whose
 * single entry points at the <em>class-declaration</em> line, not at any statement. If the
 * {@link io.hindsight.agent.visitors.FieldLocalVisitor} line/local/branch pass ran over the
 * bridge it would mint a phantom LINE record on the class-declaration line — a line with no
 * code — displayed inside whatever caller happened to invoke the bridge polymorphically. The
 * bracket matcher already excludes bridges ({@code not(isBridge())}); the line pass must too.
 *
 * <p>Compiled by the test toolchain (JDK 25, classfile v69) with full debug info, exactly like
 * the production classes the agent targets.
 */
public final class BridgeFixture implements Comparable<BridgeFixture> {

    private final int rank;

    public BridgeFixture(int rank) {
        this.rank = rank;
    }

    @Override
    public int compareTo(BridgeFixture other) {
        int mine = this.rank;
        int theirs = other.rank;
        return Integer.compare(mine, theirs);
    }
}
