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
 * level against the current cursor's {@code seq}. A node is expandable when its
 * value is a non-null {@link Value.Ref}, because only an object id can be
 * resolved into fields or elements — or when it is a MAP-entry node (§17.3),
 * which always expands into exactly two children, {@code key = <key>} and
 * {@code value = <value>}. Leaves such as {@code <No info available>} or a
 * {@code …+N more} sentinel are deliberately non-expandable.
 */
public final class ObjectNode {

    private final String label;
    private final Value value;
    private final boolean expandable;
    private final Value entryKey; // non-null only for MAP-entry nodes (§17.3)
    private final Value entryValue; // non-null only for MAP-entry nodes (§17.3)

    /**
     * Creates a node with an explicit expandability, used by the engine for special
     * leaves (cycle markers, {@code …+N more}, {@code <No info available>}).
     */
    public ObjectNode(final String label, final Value value, final boolean expandable) {
        this(label, value, expandable, null, null);
    }

    private ObjectNode(final String label, final Value value, final boolean expandable,
            final Value entryKey, final Value entryValue) {
        this.label = label;
        this.value = value;
        this.expandable = expandable;
        this.entryKey = entryKey;
        this.entryValue = entryValue;
    }

    /**
     * Creates a MAP-entry node (§17.3): always expandable, yielding exactly the two
     * children {@code key = <key>} and {@code value = <value>} from
     * {@link Engine#children(ObjectNode, java.util.Set)}. The node's own {@link #value()}
     * stays the entry's map <em>value</em>, matching what the pre-§17.3 entry leaf carried,
     * so value-typed UI affordances (e.g. the §14.4 info line) are unchanged.
     */
    static ObjectNode mapEntry(final String label, final Value key, final Value value) {
        return new ObjectNode(label, value, true, key, value);
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

    /** Whether this node can be expanded into children (non-null references and map entries). */
    public boolean expandable() {
        return expandable;
    }

    /**
     * Whether this is a MAP-entry node (§17.3): its children are the fixed
     * {@code key =}/{@code value =} pair rather than a {@link Value.Ref} resolution, so tree
     * walkers must not treat its {@link #value()} as the id being expanded (in particular it
     * contributes no ancestor id of its own on the way down).
     */
    public boolean isMapEntry() {
        return entryKey != null;
    }

    /** The entry's key value — non-null exactly when {@link #isMapEntry()}. */
    Value entryKey() {
        return entryKey;
    }

    /** The entry's map value — non-null exactly when {@link #isMapEntry()}. */
    Value entryValue() {
        return entryValue;
    }
}
