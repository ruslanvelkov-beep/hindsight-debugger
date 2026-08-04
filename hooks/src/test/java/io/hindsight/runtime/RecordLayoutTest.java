// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.hooks.FieldWriteHook;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Byte-level record layout (FORMAT.md §2): every field is asserted at its absolute offset with
 * hand-rolled little-endian decoding, independent of the {@link Recordings} parser, so the
 * writer and the parser cannot agree on a wrong layout.
 */
class RecordLayoutTest {

    @TempDir
    Path dir;

    @AfterEach
    void shutdown() {
        HindsightRuntime.shutdownForTests();
    }

    @Test
    void fixed40ByteLayoutMatchesSpecOffsets() throws IOException {
        HindsightRuntime.boot(Recordings.config(dir));
        FieldWriteHook.recordLong(77, null, 111L, 222L);
        HindsightRuntime.shutdownForTests();

        final byte[] bytes = Files.readAllBytes(dir.resolve("records-00001.bin"));
        assertEquals(0, bytes.length % Wire.RECORD_SIZE, "file must be whole 40-byte records");
        assertTrue(bytes.length / Wire.RECORD_SIZE >= 3, "THREAD_START, TIME_SYNC, FIELD_WRITE");

        // Record 0 must be THREAD_START, record 1 TIME_SYNC (§2.1).
        assertEquals(Wire.TYPE_THREAD_START, bytes[8]);
        assertEquals(Wire.TYPE_TIME_SYNC, bytes[Wire.RECORD_SIZE + 8]);

        // Find the FIELD_WRITE and check every offset of §2's table by hand.
        int base = -1;
        for (int i = 0; i < bytes.length; i += Wire.RECORD_SIZE) {
            if (bytes[i + 8] == Wire.TYPE_FIELD_WRITE) {
                base = i;
                break;
            }
        }
        assertTrue(base >= 0, "FIELD_WRITE record present");

        assertTrue(leLong(bytes, base) > 0, "offset 0: seq");
        assertEquals(Wire.TYPE_FIELD_WRITE, bytes[base + 8] & 0xFF, "offset 8: type");
        final int expectedFlags =
                Wire.KIND_INLINE | (Wire.KIND_INLINE << Wire.AFTER_KIND_SHIFT) | Wire.FLAG_STATIC;
        assertEquals(expectedFlags, bytes[base + 9] & 0xFF, "offset 9: flags");
        assertEquals(0, bytes[base + 10] & 0xFF, "offset 10: aux idx (low byte)");
        assertEquals(Wire.TAG_LONG, bytes[base + 11] & 0xFF, "offset 11: aux tag (high byte)");
        assertEquals(77, leInt(bytes, base + 12), "offset 12: location = site id");
        assertEquals(0L, leLong(bytes, base + 16), "offset 16: target (0 for static)");
        assertEquals(111L, leLong(bytes, base + 24), "offset 24: before");
        assertEquals(222L, leLong(bytes, base + 32), "offset 32: after");

        // Roundtrip through the shared parser must agree with the manual decode.
        final Recordings.Rec rec = Recordings.parse(dir.resolve("records-00001.bin")).get(base / Wire.RECORD_SIZE);
        assertEquals(leLong(bytes, base), rec.seq());
        assertEquals(77, rec.location());
        assertEquals(111L, rec.before());
        assertEquals(222L, rec.after());
    }

    @Test
    void threadStartCarriesNameSymbolAndJavaThreadId() throws IOException {
        HindsightRuntime.boot(Recordings.config(dir));
        FieldWriteHook.recordLong(1, null, 0L, 1L); // force worker creation on this thread
        final String threadName = Thread.currentThread().getName();
        final long javaTid = Thread.currentThread().threadId();
        HindsightRuntime.shutdownForTests();

        final Recordings.Rec start = Recordings.parse(dir.resolve("records-00001.bin")).get(0);
        assertEquals(Wire.TYPE_THREAD_START, start.type());
        assertEquals(javaTid, start.after(), "after = Thread.threadId()");
        final String[] sym = Recordings.rtSymbols(dir).get(start.location());
        assertEquals("THREAD", sym[0]);
        assertEquals(threadName, sym[1]);
    }

    private static long leLong(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static int leInt(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
