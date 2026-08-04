// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import io.hindsight.reader.Format;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The navigable history trail shown in the Records window (VIEWER.md §11.5): a
 * bounded ring of the positions the cursor has <em>landed on</em> through user
 * navigation, newest last. When more than {@link #CAPACITY} positions accumulate
 * the oldest is dropped.
 *
 * <p>The add/dedup rule is the subtle part, so it lives here as a small pure model
 * the TUI drives (append exactly one entry per user navigation that moves; never on
 * a view-only frame select or when re-selecting an existing entry) and a test pins:
 * {@link #add} skips an entry that exactly equals the current newest one, so holding
 * a step key on a line that cannot advance does not flood the trail with duplicates.
 * Rendering ({@link #render}) and package extraction ({@link #packageOf}) are static
 * so they can be tested without building a ring.
 */
public final class HistoryTrail {

    /** Maximum retained entries; appending beyond this drops the oldest (§11.5). */
    public static final int CAPACITY = 1024;

    private static final String DOT = " · ";

    /**
     * One landing in the trail (§11.5).
     *
     * @param tid thread the cursor landed in
     * @param index record index within that thread (a {@code LINE} record for a normal landing)
     * @param seq global sequence number of that record
     * @param line the source line the landing sits on ({@code 0} if unknown)
     * @param classBinary declaring class binary name, or {@code null} if unknown
     * @param method bare method name, or {@code null} if unknown
     * @param module best-effort owning source-project dir, or {@code "?"} when source is unresolved
     * @param pkg the class's package (binary up to the last dot, or {@code "(default)"})
     */
    public record HistoryEntry(
            int tid,
            long index,
            long seq,
            int line,
            String classBinary,
            String method,
            String module,
            String pkg) {}

    private final Deque<HistoryEntry> entries = new ArrayDeque<>();

    /**
     * Appends {@code entry} as the newest landing unless it exactly equals the current newest one
     * (no consecutive duplicates, §11.5); drops the oldest entry when {@link #CAPACITY} is exceeded.
     *
     * @param entry the landing to record
     * @return {@code true} if it was appended, {@code false} if skipped as a consecutive duplicate
     */
    public boolean add(final HistoryEntry entry) {
        HistoryEntry last = entries.peekLast();
        if (entry.equals(last)) {
            return false;
        }
        entries.addLast(entry);
        while (entries.size() > CAPACITY) {
            entries.removeFirst();
        }
        return true;
    }

    /** The trail oldest-first as an immutable snapshot (index 0 = oldest, last = newest). */
    public List<HistoryEntry> entries() {
        return List.copyOf(entries);
    }

    /** Number of retained entries. */
    public int size() {
        return entries.size();
    }

    /** Whether the trail is empty (no navigation recorded yet). */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * The one-line rendering of an entry for the Records window (§11.5):
     * {@code seq N · t<tid> · line L · Class.method · module · pkg}, using the middle-dot separator
     * shared by the rest of the UI and the class's simple name.
     *
     * @param e the entry to render
     * @return the display row
     */
    public static String render(final HistoryEntry e) {
        String cls = e.classBinary() == null ? "?" : Format.simpleName(e.classBinary());
        String method = e.method() != null ? e.method() : "?";
        return "seq " + e.seq() + DOT + "t" + e.tid() + DOT + "line " + e.line()
                + DOT + cls + "." + method + DOT + e.module() + DOT + e.pkg();
    }

    /**
     * The package of a class binary name for the trail's {@code pkg} column: everything up to the
     * last dot, or {@code "(default)"} for a default-package or unknown class (§11.5).
     *
     * @param classBinary the class binary name, or {@code null}
     * @return the package name, or {@code "(default)"}
     */
    public static String packageOf(final String classBinary) {
        if (classBinary == null || classBinary.isEmpty()) {
            return "(default)";
        }
        int dot = classBinary.lastIndexOf('.');
        return dot < 0 ? "(default)" : classBinary.substring(0, dot);
    }
}
