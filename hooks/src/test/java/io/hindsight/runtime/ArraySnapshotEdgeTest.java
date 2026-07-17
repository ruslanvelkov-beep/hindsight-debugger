// SPDX-License-Identifier: Apache-2.0
package io.hindsight.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FORMAT.md §3/§7 ARRAY-form edges: the 64-entry cap against the true length, and the
 * element tag encoding for the char/boolean primitive widths (§2.4 rule 1 — elements are
 * boxed by {@code Array.get} and unboxed back to inline primitive slots).
 */
class ArraySnapshotEdgeTest {

    @TempDir
    Path tmp;

    @AfterEach
    void shutdown() {
        HindsightRuntime.shutdownForTests();
    }

    @Test
    void arrayLongerThanCapKeepsTrueLengthAndCapsEntries() throws IOException {
        final RuntimeConfig cfg = Recordings.config(tmp);
        HindsightRuntime.boot(cfg);
        final Path dir = HindsightRuntime.recordingDir();

        final long[] arr = new long[100];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = i * 7L;
        }
        HindsightRuntime.worker().recordLocalWriteObject(1, 0, arr);
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        final long arrId = firstLocalWriteTarget(recs);
        final Unfolded dump = parseArray(dir, onlySnapshotOf(recs, arrId).after());
        assertEquals(100, dump.totalSize, "true length survives the cap");
        assertEquals(Wire.SNAPSHOT_MAX_FIELDS, dump.entries.size(), "entries capped at 64");
        for (int i = 0; i < dump.entries.size(); i++) {
            assertEquals(Wire.TAG_LONG, (int) dump.entries.get(i)[0], "element tag at " + i);
            assertEquals(Wire.KIND_INLINE, (int) dump.entries.get(i)[1], "element kind at " + i);
            assertEquals(i * 7L, dump.entries.get(i)[2], "element value at " + i);
        }
    }

    @Test
    void charAndBooleanElementsCarryTheirPrimitiveTags() throws IOException {
        final RuntimeConfig cfg = Recordings.config(tmp);
        HindsightRuntime.boot(cfg);
        final Path dir = HindsightRuntime.recordingDir();

        final char[] chars = {'a', 'Z'};
        final boolean[] bools = {true, false};
        final ThreadWorker worker = HindsightRuntime.worker();
        worker.recordLocalWriteObject(1, 0, chars);
        worker.recordLocalWriteObject(2, 1, bools);
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        final List<Long> targets = localWriteTargets(recs);
        assertEquals(2, targets.size());

        final Unfolded charDump = parseArray(dir, onlySnapshotOf(recs, targets.get(0)).after());
        assertEquals(2, charDump.totalSize);
        assertEquals(Wire.TAG_CHAR, (int) charDump.entries.get(0)[0]);
        assertEquals(Wire.KIND_INLINE, (int) charDump.entries.get(0)[1]);
        assertEquals('a', charDump.entries.get(0)[2], "char zero-extended");
        assertEquals('Z', charDump.entries.get(1)[2]);

        final Unfolded boolDump = parseArray(dir, onlySnapshotOf(recs, targets.get(1)).after());
        assertEquals(2, boolDump.totalSize);
        assertEquals(Wire.TAG_BOOLEAN, (int) boolDump.entries.get(0)[0]);
        assertEquals(1L, boolDump.entries.get(0)[2], "true -> 1");
        assertEquals(0L, boolDump.entries.get(1)[2], "false -> 0");
    }

    // ---------------------------------------------------------------------------------

    private static long firstLocalWriteTarget(List<Recordings.Rec> recs) {
        return localWriteTargets(recs).get(0);
    }

    private static List<Long> localWriteTargets(List<Recordings.Rec> recs) {
        final List<Long> out = new ArrayList<>();
        for (final Recordings.Rec r : recs) {
            if (r.type() == Wire.TYPE_LOCAL_WRITE) {
                out.add(r.after());
            }
        }
        return out;
    }

    private static Recordings.Rec onlySnapshotOf(List<Recordings.Rec> recs, long objectId) {
        Recordings.Rec found = null;
        for (final Recordings.Rec r : recs) {
            if (r.type() == Wire.TYPE_SNAPSHOT && r.target() == objectId) {
                assertEquals(null, found, "expected exactly one snapshot of #" + objectId);
                found = r;
            }
        }
        assertNotNull(found, "no snapshot of #" + objectId);
        return found;
    }

    private Path blobFile(Path dir) throws IOException {
        Path blobFile = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "blob-*.bin")) {
            for (final Path b : ds) {
                blobFile = b;
            }
        }
        assertNotNull(blobFile, "snapshot payloads require a blob file");
        return blobFile;
    }

    /** Parsed ARRAY payload: true array length + [tag, kind, value] entries. */
    private record Unfolded(long totalSize, List<long[]> entries) {}

    private Unfolded parseArray(Path dir, long blobOffset) throws IOException {
        final Recordings.Blob blob = Recordings.blobAt(blobFile(dir), blobOffset);
        assertEquals(Wire.BLOB_KIND_SNAPSHOT, blob.kind());
        final ByteBuffer buf = ByteBuffer.wrap(blob.payload()).order(ByteOrder.LITTLE_ENDIAN);
        buf.getInt(); // classSym
        assertEquals(Wire.SNAPSHOT_FORM_ARRAY, buf.get() & 0xFF, "arrays dump as ARRAY form");
        final long totalSize = buf.getInt() & 0xFFFF_FFFFL;
        final int count = buf.getShort() & 0xFFFF;
        final List<long[]> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(new long[] {buf.get() & 0xFF, buf.get() & 0xFF, buf.getLong()});
        }
        return new Unfolded(totalSize, out);
    }
}
