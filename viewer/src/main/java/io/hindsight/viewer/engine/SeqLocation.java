// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.engine;

/**
 * The coordinates of the record carrying a given global {@code seq} — the result of
 * {@link Engine#findSeq(long)} (VIEWER.md §16.7).
 *
 * <p>{@code seq} is a global {@code AtomicLong} incremented once per record, so it is unique
 * across the whole recording: at most one record — in at most one thread — matches a given
 * seq. When no record carries the requested seq, {@code found} is {@code false} and both
 * {@code tid} and {@code index} are {@code 0}.
 *
 * @param found whether a record with the requested seq exists in the recording
 * @param tid the thread id carrying that record, or {@code 0} when not found
 * @param index the record index within that thread, or {@code 0} when not found
 */
public record SeqLocation(boolean found, int tid, long index) {}
