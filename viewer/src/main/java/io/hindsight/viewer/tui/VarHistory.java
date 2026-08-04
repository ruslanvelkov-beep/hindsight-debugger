// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import io.hindsight.reader.Format;
import io.hindsight.viewer.engine.FieldWriteHit;

/**
 * Pure text/geometry helpers for the v1.6 variable-history window (VIEWER.md §16.1) and the
 * §20.9 global variable-change window: the window width (75% of the terminal, clamped), the scope
 * description shown in the title, the assembled title, and the shared field-write {@link #row}
 * formatter used by BOTH windows. Kept terminal-free so the exact strings and the clamp arithmetic
 * are unit-testable without a live screen.
 *
 * <p>Row numbering reuses {@link DialogFormat#number} (right-aligned to the row count's
 * width) exactly as the {@code /} dialog does, so it is not duplicated here.
 */
public final class VarHistory {

    private VarHistory() {}

    /**
     * The shared field-write row (§16.1/§20.9): {@code seq <N> · t<tid> · <Owner>#<oid>.<field>:
     * <old> → <new>  (<File>:<line>)}. A static write omits the {@code #<oid>} (its owner id is 0);
     * an unknown owner class renders {@code ?} and an unknown file {@code ?}. Reused verbatim by the
     * v1.6 variable-history window and the §20.9 global variable-change window so the two render
     * identically. The old/new values arrive already rendered on the {@link FieldWriteHit} (with any
     * §20.4/§20.5 numeric decoration the engine baked in).
     *
     * @param h the field-write hit to format
     * @return the assembled row text
     */
    public static String row(final FieldWriteHit h) {
        String owner = h.ownerClass() == null ? "?" : Format.simpleName(h.ownerClass());
        String target = h.isStatic() ? owner : owner + "#" + h.ownerId();
        String file = h.fileName() != null ? h.fileName() : "?";
        return "seq " + h.seq() + " · t" + h.tid() + " · " + target + "." + h.fieldName()
                + ": " + h.before() + " → " + h.after() + "  (" + file + ":" + h.line() + ")";
    }

    /**
     * The §16.1 window width: 75% of the terminal columns, clamped to
     * {@code [40, cols-2]} — {@code w = clamp(cols*3/4, 40, cols-2)}.
     *
     * @param cols the terminal width in columns
     * @return the window width in columns
     */
    public static int width(final int cols) {
        return Math.min(Math.max(cols * 3 / 4, 40), Math.max(40, cols - 2));
    }

    /**
     * The §16.1 title scope suffix: {@code this <SimpleClass>#<id>} when the this-instance
     * filter is active and an enclosing instance exists; {@code all instances (no enclosing
     * this)} when there is no enclosing instance ({@code thisId == 0}); {@code all instances}
     * otherwise.
     *
     * @param thisOnly whether the this-instance filter is active
     * @param thisId the enclosing instance id captured at open time, or {@code 0} for none
     * @param simpleClass the simple class name of the enclosing instance ({@code ?} when unknown)
     * @return the scope text
     */
    public static String scope(final boolean thisOnly, final long thisId, final String simpleClass) {
        if (thisOnly && thisId != 0) {
            return "this " + (simpleClass == null ? "?" : simpleClass) + "#" + thisId;
        }
        if (thisId == 0) {
            return "all instances (no enclosing this)";
        }
        return "all instances";
    }

    /**
     * The §16.1 window title: {@code Variable history [v] — <count> entries · <scope>}.
     *
     * @param count the number of rows currently shown (post-filter, pre-cap-note)
     * @param scope the {@link #scope} text
     * @return the assembled title
     */
    public static String title(final int count, final String scope) {
        return "Variable history [v] — " + count + " entries · " + scope;
    }
}
