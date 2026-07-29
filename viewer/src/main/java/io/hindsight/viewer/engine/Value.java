// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

/**
 * A reconstructed value as it appeared at some point on the recorded timeline
 * (VIEWER.md §2.4). One shared abstraction backs locals, object-tree nodes and
 * watch results so the TUI has a single rendering path.
 *
 * <p>The variants mirror the format's encoding decisions (FORMAT.md §2.4): a
 * primitive/boxed value is a {@link Primitive} carrying its already-rendered
 * text; a captured string is a {@link Str} (kept unquoted so callers can decide
 * quoting/eliding); a null reference is {@link Null}; any other reference is a
 * {@link Ref} to a recorded object id — the <em>only</em> expandable variant,
 * because only object ids can be resolved into fields/elements. {@link Absent}
 * marks a slot that is known to exist but for which no value was recorded (an
 * unwritten local, a container's {@code …+N more} sentinel), which the viewer
 * must distinguish from a real {@code null}.
 *
 * <p>{@link #display()} yields the human text for every variant. For {@link Ref}
 * it is the {@code renderObjectId} string produced by the reader's
 * {@code ValueRenderer}; the record component accessor supplies it directly.
 */
public sealed interface Value {

    /** Human-readable text for this value, ready to drop into a tree label or locals row. */
    String display();

    /** A primitive or boxed primitive, already rendered by the reader's {@code ValueRenderer}. */
    record Primitive(String rendered) implements Value {
        @Override
        public String display() {
            return rendered;
        }
    }

    /**
     * A captured string. {@code text} is the raw (possibly truncated) content; {@code truncated}
     * reflects the record's {@code TRUNCATED} flag so {@link #display()} can append an ellipsis
     * inside the quotes exactly as the reader does.
     */
    record Str(String text, boolean truncated) implements Value {
        @Override
        public String display() {
            return "\"" + text + (truncated ? "…" : "") + "\"";
        }
    }

    /** A null reference. */
    record Null() implements Value {
        @Override
        public String display() {
            return "null";
        }
    }

    /**
     * A reference to a recorded object id. {@code display} is the reader's
     * {@code renderObjectId} rendering (e.g. {@code Account#123} or {@code Enum.CONSTANT}),
     * with the §20.1/§20.2 inline-wrapper suffix {@code   [ <value> ]} appended by the engine
     * when the id is an always-dump scalar wrapper or an {@code AtomicReference}; the generated
     * accessor satisfies {@link #display()}. {@code expandable} is whether a tree node built from
     * this ref may be expanded into children: {@code true} for an ordinary object (the default,
     * via the two-arg constructor), {@code false} for a scalar wrapper or a null-holding
     * {@code AtomicReference} whose single value is already shown inline (§20.1/§20.2).
     */
    record Ref(long objectId, String display, boolean expandable) implements Value {

        /** An ordinary, expandable reference (the common case): the id resolves into fields/elements. */
        public Ref(final long objectId, final String display) {
            this(objectId, display, true);
        }
    }

    /** A slot known to exist but for which no value was recorded (unwritten local, sentinel). */
    record Absent() implements Value {
        @Override
        public String display() {
            return "<absent>";
        }
    }
}
