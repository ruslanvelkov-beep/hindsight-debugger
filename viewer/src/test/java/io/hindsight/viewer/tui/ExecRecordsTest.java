// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.reader.Format;
import org.junit.jupiter.api.Test;

/**
 * Pins the §17.1/§17.2 exec set the landing helpers gate on: exactly LINE, METHOD_ENTER
 * and METHOD_EXIT — every metadata and value record type stays out.
 */
final class ExecRecordsTest {

    @Test
    void execSetIsExactlyLineEnterExit() {
        assertTrue(ExecRecords.isExec(Format.LINE));
        assertTrue(ExecRecords.isExec(Format.METHOD_ENTER));
        assertTrue(ExecRecords.isExec(Format.METHOD_EXIT));
    }

    @Test
    void metadataAndValueRecordsAreNotLandings() {
        assertFalse(ExecRecords.isExec(Format.ARG));
        assertFalse(ExecRecords.isExec(Format.BRANCH));
        assertFalse(ExecRecords.isExec(Format.FIELD_WRITE));
        assertFalse(ExecRecords.isExec(Format.LOCAL_WRITE));
        assertFalse(ExecRecords.isExec(Format.OBJECT_FIRST_SEEN));
        assertFalse(ExecRecords.isExec(Format.SNAPSHOT));
        assertFalse(ExecRecords.isExec(Format.THREAD_START));
        assertFalse(ExecRecords.isExec(Format.TIME_SYNC));
        assertFalse(ExecRecords.isExec(Format.STACK_SNAPSHOT));
    }
}
