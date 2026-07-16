// SPDX-License-Identifier: Apache-2.0
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
 * {@code lineIndices} is the sorted list of {@code LINE} record indices, the search
 * structure that stepping binary-searches over; it is empty when the recording had
 * {@code lines=false}. {@code timeSyncIndices}/{@code timeSyncMillis} let
 * {@link #timeAt} answer "wall-clock at record i" from the nearest preceding
 * {@code TIME_SYNC} without a linear scan.
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
    final long[] lineIndices;
    final long[] timeSyncIndices;
    final long[] timeSyncMillis;
    final String threadName;

    ThreadIndex(
            final int tid,
            final int count,
            final int[] depth,
            final int[] frameEnter,
            final int[] matchingExit,
            final int[] parentEnter,
            final long[] lineIndices,
            final long[] timeSyncIndices,
            final long[] timeSyncMillis,
            final String threadName) {
        this.tid = tid;
        this.count = count;
        this.depth = depth;
        this.frameEnter = frameEnter;
        this.matchingExit = matchingExit;
        this.parentEnter = parentEnter;
        this.lineIndices = lineIndices;
        this.timeSyncIndices = timeSyncIndices;
        this.timeSyncMillis = timeSyncMillis;
        this.threadName = threadName;
    }

    /** Whether this thread carries any {@code LINE} records (else stepping degrades, §2.2). */
    boolean hasLines() {
        return lineIndices.length > 0;
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
