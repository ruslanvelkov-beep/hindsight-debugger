// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import io.hindsight.reader.Format;

/**
 * Membership test for the <b>exec set</b> (VIEWER.md §17.1/§17.2): the record types that
 * change the execution position — {@code LINE}, {@code METHOD_ENTER}, {@code METHOD_EXIT} —
 * and are therefore the only legal cursor landings for steps and jumps. Metadata records
 * (first-seen, snapshots, thread-start, time-sync, stack captures) and value records (ARG,
 * LOCAL_WRITE, FIELD_WRITE, BRANCH) are never landings: a jump that ends on one is a bug.
 *
 * <p>Factored out of the {@code ViewerApp} landing helpers as a pure function so the exact
 * set is pinned by a unit test rather than re-derived at each call site.
 */
public final class ExecRecords {

    private ExecRecords() {}

    /**
     * Whether a record type is in the §17.1 exec set and may carry the cursor.
     *
     * @param recordType a {@link Format} record-type constant
     * @return {@code true} for {@code LINE}, {@code METHOD_ENTER} and {@code METHOD_EXIT}
     */
    public static boolean isExec(final int recordType) {
        return recordType == Format.LINE
                || recordType == Format.METHOD_ENTER
                || recordType == Format.METHOD_EXIT;
    }
}
