// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.engine;

/**
 * Test-only access to the package-private {@link ObjectNode} factories, so TUI tests (in
 * another package) can construct the §17.3 map-entry nodes the engine hands the tree views
 * without opening a recording.
 */
public final class TestObjectNodes {

    private TestObjectNodes() {}

    /** A §17.3 MAP-entry node, exactly as {@code Engine.children} builds them. */
    public static ObjectNode mapEntry(final String label, final Value key, final Value value) {
        return ObjectNode.mapEntry(label, key, value);
    }
}
