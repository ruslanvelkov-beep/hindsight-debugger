// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.engine;

/**
 * Per-thread reconstruction index built once at {@link Engine} open by a single
 * bracket-matching pass over the thread's records (VIEWER.md §2.1).
 *
 * <p>The parallel arrays are indexed by record index within the thread:
 * <ul>
 *   <li>{@code depth[i]} — live frame count at record {@code i} (a {@code METHOD_ENTER}
 *       counts itself; a {@code METHOD_EXIT} counts the frame it closes).</li>
 *   <li>{@code frameEnter[i]} — the {@code METHOD_ENTER} index of the innermost frame
 *       enclosing {@code i}, or {@code -1} when {@code i} is outside any frame.</li>
 *   <li>{@code matchingExit[e]} — for a {@code METHOD_ENTER} at {@code e}, the index of its
 *       matching {@code METHOD_EXIT}, or the {@code count-1} sentinel for a frame that never
 *       closed (truncated tail / live at shutdown).</li>
 *   <li>{@code parentEnter[e]} — for a {@code METHOD_ENTER} at {@code e}, the enclosing frame's
 *       ENTER index when {@code e} was pushed, or {@code -1} at depth 0.</li>
 * </ul>
 * {@code execIndices} is the sorted list of <em>execution</em>-record indices — the
 * {@code LINE}/{@code METHOD_ENTER}/{@code METHOD_EXIT} exec set of VIEWER.md §17.1
 * that all stepping walks; metadata and value records never appear in it.
 * {@code lineIndices} is the sorted list of {@code LINE} record indices (empty when the
 * recording had {@code lines=false}); {@code lineInfoIndices} (§28.9) additionally folds in
 * the {@code BRANCH} records of LINE-less frames — branch-mode methods' only line-bearing
 * records — and is what the line-oriented commands (run-to-line, exit-frame, line-trace)
 * walk, so they work identically over line-mode and branch-mode frames. {@code timeSyncIndices}/{@code timeSyncMillis} let
 * {@link #timeAt} answer "wall-clock at record i" from the nearest preceding
 * {@code TIME_SYNC} without a linear scan. {@code stackSnapshotIndices} is the
 * ascending list of {@code STACK_SNAPSHOT} record indices (schema 4, VIEWER.md
 * §14.5) — the §14.5 stack merge binary-searches it for the nearest capture at or
 * before the cursor; it is empty for schema ≤ 3 recordings.
 *
 * <p>These are {@code int}/{@code long} arrays rather than objects because a thread
 * can hold millions of records; the index must stay compact.
 */
final class ThreadIndex {

    final int tid;
    final int count;
    final int[] depth;
    final int[] frameEnter;
    final int[] matchingExit;
    final int[] parentEnter;
    final long[] execIndices;
    final long[] lineIndices;
    final long[] lineInfoIndices;
    final long[] timeSyncIndices;
    final long[] timeSyncMillis;
    final long[] stackSnapshotIndices;
    final String threadName;

    ThreadIndex(
            final int tid,
            final int count,
            final int[] depth,
            final int[] frameEnter,
            final int[] matchingExit,
            final int[] parentEnter,
            final long[] execIndices,
            final long[] lineIndices,
            final long[] lineInfoIndices,
            final long[] timeSyncIndices,
            final long[] timeSyncMillis,
            final long[] stackSnapshotIndices,
            final String threadName) {
        this.tid = tid;
        this.count = count;
        this.depth = depth;
        this.frameEnter = frameEnter;
        this.matchingExit = matchingExit;
        this.parentEnter = parentEnter;
        this.execIndices = execIndices;
        this.lineIndices = lineIndices;
        this.lineInfoIndices = lineInfoIndices;
        this.timeSyncIndices = timeSyncIndices;
        this.timeSyncMillis = timeSyncMillis;
        this.stackSnapshotIndices = stackSnapshotIndices;
        this.threadName = threadName;
    }

    /** Whether this thread carries any {@code LINE} records (the §18.4 thread-kind cache key). */
    boolean hasLines() {
        return lineIndices.length > 0;
    }

    /** §28.9: whether this thread carries any line-bearing record (LINE, or branch-mode BRANCH). */
    boolean hasLineInfo() {
        return lineInfoIndices.length > 0;
    }

    /** Wall-clock millis of the nearest {@code TIME_SYNC} at or before {@code index}, else 0. */
    long timeAt(final long index) {
        int lo = 0;
        int hi = timeSyncIndices.length - 1;
        int ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (timeSyncIndices[mid] <= index) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans >= 0 ? timeSyncMillis[ans] : 0;
    }
}
