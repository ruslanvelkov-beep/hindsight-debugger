// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.stackcapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.runtime.HindsightRuntime;
import io.hindsight.runtime.Recordings;
import io.hindsight.runtime.RuntimeConfig;
import io.hindsight.runtime.TestRuntime;
import io.hindsight.runtime.ThreadWorker;
import io.hindsight.runtime.Wire;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FORMAT.md §2.1/§3 STACK_SNAPSHOT (schema 4): a capture accompanies each thread's first
 * business record and then recurs every {@code stackEvery} records; the payload is
 * innermost-first with all leading recorder frames stripped; {@code stackEvery=0} disables
 * captures entirely.
 *
 * <p>Deliberately OUTSIDE {@code io.hindsight.*}: the capture strips leading frames by that
 * package prefix, so only a foreign-package caller can assert that its own frames survive
 * (they are the innermost application frames of the recording call chain) while no
 * {@code io.hindsight.} frame does.
 */
class StackSnapshotTest {

    @TempDir
    Path tmp;

    @AfterEach
    void shutdown() {
        TestRuntime.shutdown();
    }

    @Test
    void firstBusinessRecordIsAccompaniedByAParsableCapture() throws IOException {
        final RuntimeConfig cfg = Recordings.config(tmp);
        cfg.stackEvery = 1024; // spec default; first capture rides the first business record
        HindsightRuntime.boot(cfg);
        final Path dir = HindsightRuntime.recordingDir();

        HindsightRuntime.worker().recordLocalWriteObject(1, 0, "hello");
        TestRuntime.shutdown();

        final List<Recordings.Rec> recs = Recordings.parse(dir.resolve("records-00001.bin"));
        assertEquals((int) Wire.TYPE_THREAD_START, recs.get(0).type());
        assertEquals((int) Wire.TYPE_TIME_SYNC, recs.get(1).type());
        assertEquals((int) Wire.TYPE_LOCAL_WRITE, recs.get(2).type(), "first business record");
        final Recordings.Rec capture = recs.get(3);
        assertEquals((int) Wire.TYPE_STACK_SNAPSHOT, capture.type(),
                "capture accompanies the thread's first business record");
        assertEquals(Wire.KIND_BLOB, capture.afterKind(), "after = blob offset, AFTER_KIND=BLOB");
        assertEquals(0, capture.location());
        assertEquals(0L, capture.target());

        final List<String[]> frames = frames(dir, capture.after());
        assertFalse(frames.isEmpty(), "the recording call chain must survive the strip");
        for (final String[] f : frames) {
            assertFalse(f[0].startsWith("io.hindsight."), "recorder frame leaked into capture: " + f[0]);
        }
        // Innermost-first: frame 0 is the exact call that entered the recording chain.
        assertEquals(getClass().getName(), frames.get(0)[0]);
        assertEquals("firstBusinessRecordIsAccompaniedByAParsableCapture", frames.get(0)[1]);
        assertEquals("StackSnapshotTest.java", frames.get(0)[2]);
        assertTrue(Integer.parseInt(frames.get(0)[3]) > 0, "line of the recording call");
        assertTrue(frames.stream().anyMatch(f -> f[0].startsWith("org.junit.")),
                "outer JUnit frames follow the test frame when innermost-first ordering holds");
    }

    @Test
    void cadenceRecapturesEveryStackEveryRecordsAndNotBetween() throws IOException {
        final RuntimeConfig cfg = Recordings.config(tmp);
        cfg.stackEvery = 8;
        HindsightRuntime.boot(cfg);
        final Path dir = HindsightRuntime.recordingDir();

        final ThreadWorker worker = HindsightRuntime.worker();
        for (int i = 0; i < 12; i++) {
            worker.recordLocalWriteInline(1, 0, i, Wire.TAG_INT);
        }
        TestRuntime.shutdown();

        final List<Recordings.Rec> recs = Recordings.parse(dir.resolve("records-00001.bin"));
        final List<Integer> captureIndexes = new ArrayList<>();
        for (int i = 0; i < recs.size(); i++) {
            if (recs.get(i).type() == Wire.TYPE_STACK_SNAPSHOT) {
                captureIndexes.add(i);
            }
        }
        assertEquals(2, captureIndexes.size(),
                "12 business records at stackEvery=8: the first-record capture plus one recapture");
        assertEquals(3, captureIndexes.get(0),
                "first capture right after THREAD_START, TIME_SYNC and the first business record");
        assertEquals(8, captureIndexes.get(1) - captureIndexes.get(0),
                "second capture exactly stackEvery records later — and none between");
        assertFalse(frames(dir, recs.get(captureIndexes.get(1)).after()).isEmpty());
    }

    @Test
    void stackEveryZeroEmitsNoCaptures() throws IOException {
        final RuntimeConfig cfg = Recordings.config(tmp);
        cfg.stackEvery = 0;
        HindsightRuntime.boot(cfg);
        final Path dir = HindsightRuntime.recordingDir();

        final ThreadWorker worker = HindsightRuntime.worker();
        for (int i = 0; i < 50; i++) {
            worker.recordLocalWriteInline(1, 0, i, Wire.TAG_INT);
        }
        TestRuntime.shutdown();

        for (final Recordings.Rec r : Recordings.parseAll(dir)) {
            assertFalse(r.type() == Wire.TYPE_STACK_SNAPSHOT, "stackEvery=0 must never capture");
        }
    }

    // -------------------------------------------------------------------------------------

    /**
     * Parses a §3 STACK payload into {@code [classBinary, methodName, fileNameOrNull, line]}
     * rows, innermost-first, asserting the blob kind, the exact payload size and the §4
     * rt-symbol kinds (RTCLASS / METHODNAME / FILENAME) along the way.
     */
    private static List<String[]> frames(Path dir, long blobOffset) throws IOException {
        final Recordings.Blob blob = Recordings.blobAt(dir.resolve("blob-00001.bin"), blobOffset);
        assertEquals(Wire.BLOB_KIND_STACK, blob.kind(), "STACK blob kind");
        final Map<Integer, String[]> syms = Recordings.rtSymbols(dir);
        final ByteBuffer b = ByteBuffer.wrap(blob.payload()).order(ByteOrder.LITTLE_ENDIAN);
        final int count = b.getShort() & 0xFFFF;
        assertEquals(2 + count * Wire.STACK_FRAME_SIZE, blob.payload().length,
                "payload is exactly count 16-byte frame entries");
        assertTrue(count <= Wire.STACK_MAX_FRAMES, "frame count is capped");
        final List<String[]> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final String[] cls = syms.get(b.getInt());
            final String[] method = syms.get(b.getInt());
            final int fileSym = b.getInt();
            final int line = b.getInt();
            assertNotNull(cls, "class symbol resolvable");
            assertNotNull(method, "method symbol resolvable");
            assertEquals("RTCLASS", cls[0]);
            assertEquals("METHODNAME", method[0]);
            String file = null;
            if (fileSym != 0) {
                final String[] f = syms.get(fileSym);
                assertNotNull(f, "file symbol resolvable");
                assertEquals("FILENAME", f[0]);
                file = f[1];
            }
            out.add(new String[] {cls[1], method[1], file, Integer.toString(line)});
        }
        return out;
    }
}
