// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.engine;

/**
 * An immutable snapshot of the engine's view state (VIEWER.md §2.2): where the
 * cursor sits and which frame is selected. It is intentionally lightweight — the
 * full stack/locals/record-info are recomputed on demand via the {@link Engine}
 * accessors — but it is enough for the TUI to detect movement (compare two
 * positions) and to label the status row without a second round of queries.
 *
 * @param tid dense thread id of the cursor
 * @param index record index of the cursor within its thread
 * @param seq global sequence number of the cursor record
 * @param selectedFrame currently selected frame index ({@code 0} = outermost)
 * @param depth live stack depth at the cursor
 */
public record Position(int tid, long index, long seq, int selectedFrame, int depth) {}
