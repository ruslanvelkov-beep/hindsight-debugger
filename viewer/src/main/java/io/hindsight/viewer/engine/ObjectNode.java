// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

/**
 * A single node of a lazily-expanded object/locals/watch tree (VIEWER.md §2.5).
 *
 * <p>A node carries only its <em>display</em> ({@link #label()}), its own
 * {@link Value} and whether it is {@link #expandable()}; its children are never
 * held here — the TUI materialises them on demand via
 * {@link Engine#children(ObjectNode, java.util.Set)}. That keeps a deep or cyclic
 * object graph from being walked eagerly and lets the engine re-resolve each
 * level against the current cursor's {@code seq}. A node is expandable exactly
 * when its value is a non-null {@link Value.Ref}, because only an object id can
 * be resolved into fields or elements; leaves such as {@code <No info available>}
 * or a {@code …+N more} sentinel are deliberately non-expandable.
 */
public final class ObjectNode {

    private final String label;
    private final Value value;
    private final boolean expandable;

    /**
     * Creates a node with an explicit expandability, used by the engine for special
     * leaves (cycle markers, {@code …+N more}, {@code <No info available>}).
     */
    public ObjectNode(final String label, final Value value, final boolean expandable) {
        this.label = label;
        this.value = value;
        this.expandable = expandable;
    }

    /**
     * Creates a node whose expandability is derived from its value: expandable iff the
     * value is a non-null reference. Callers building top-level local/watch nodes use this
     * so they need not repeat the reference test.
     */
    public static ObjectNode of(final String label, final Value value) {
        return new ObjectNode(label, value, value instanceof Value.Ref ref && ref.objectId() != 0);
    }

    /** The text the TUI renders for this row (name, {@code =} value, map arrow, etc.). */
    public String label() {
        return label;
    }

    /** This node's own value; a {@link Value.Ref} here is what {@link Engine#children} resolves. */
    public Value value() {
        return value;
    }

    /** Whether this node can be expanded into children (true only for non-null references). */
    public boolean expandable() {
        return expandable;
    }
}
