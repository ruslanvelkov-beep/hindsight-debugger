// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

/**
 * One recorded object instance: the position of its {@code OBJECT_FIRST_SEEN}
 * record (VIEWER.md §13.6). The instance-browse dialog lists these ascending by
 * {@code seq}, and the instance explore window's "jump to creation" action seeks
 * to this {@code (tid, index)} — {@link Engine#creationOf(long)} returns the same
 * value for the instance's object id.
 *
 * @param objectId the registered object id (the first-seen record's target)
 * @param seq global sequence number of the {@code OBJECT_FIRST_SEEN} record
 * @param tid thread id that registered the object
 * @param index record index of the {@code OBJECT_FIRST_SEEN} within that thread
 */
public record Instance(long objectId, long seq, int tid, long index) {}
