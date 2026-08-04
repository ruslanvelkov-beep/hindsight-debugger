// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.engine;

/**
 * Snapshot of everything the Record Info window shows for the cursor record
 * (VIEWER.md §2.8). It is a flat value object (not a tree) so the TUI can render
 * it as a simple key/value list without calling back into the engine.
 *
 * @param seq global sequence number of the cursor record
 * @param tid dense thread id of the cursor
 * @param threadName recorded thread name, or a fallback when unknown
 * @param typeName record-type name (e.g. {@code LINE}, {@code METHOD_ENTER})
 * @param siteText {@code Class.method (File:line)} for the cursor record
 * @param index zero-based record index within the thread file
 * @param threadRecordCount total records in this thread's file
 * @param globalRecordCount total records across all threads
 * @param approxEpochMillis wall-clock estimate from the nearest preceding {@code TIME_SYNC}, or 0
 * @param depth live stack depth at the cursor
 * @param truncatedRecording whether the recording carries a {@code TRUNCATED} marker
 */
public record RecordInfo(
        long seq,
        int tid,
        String threadName,
        String typeName,
        String siteText,
        long index,
        long threadRecordCount,
        long globalRecordCount,
        long approxEpochMillis,
        int depth,
        boolean truncatedRecording) {}
