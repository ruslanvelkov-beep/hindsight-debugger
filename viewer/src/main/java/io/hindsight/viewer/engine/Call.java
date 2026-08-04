// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.engine;

/**
 * One {@code METHOD_ENTER} occurrence of a {@link MethodSite} (VIEWER.md §11.7): a
 * single invocation the recording captured, identified by its global sequence
 * number and its {@code (tid, index)} location within that thread. The "browse
 * method calls" dialog lists these ascending by {@code seq} and jumps the cursor
 * to the nearest {@code LINE} at or after the call via {@link Engine#seek(int, long)}.
 *
 * @param seq global sequence number of the {@code METHOD_ENTER} record
 * @param tid thread id the invocation occurred on
 * @param index record index of the {@code METHOD_ENTER} within that thread
 */
public record Call(long seq, int tid, long index) {}
