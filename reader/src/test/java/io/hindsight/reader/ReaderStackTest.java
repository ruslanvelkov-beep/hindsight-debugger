// SPDX-License-Identifier: Apache-2.0
package io.hindsight.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Schema-4 STACK_SNAPSHOT support (FORMAT.md §2.1/§3/§12/§13), spec cross-checked on
 * byte-by-byte synthetic recordings: schema 4 opens, {@code stackAt} round-trips
 * {@link Recording.CapturedFrame}s, {@code dump} renders the header + innermost-first
 * {@code at} lines, {@code verify}/{@code stats} handle the new type, damaged payloads are
 * violations, and schema ≤ 3 recordings (including the real transfer-11 capture when present)
 * keep verifying.
 */
final class ReaderStackTest {

    @TempDir
    Path tmp;

    /** A minimal schema-4 recording: one thread, one capture with two frames. */
    private Path stackRecording() throws IOException {
        SyntheticRecording sr = new SyntheticRecording(tmp.resolve("rec")).schema(4);
        sr.threadStart(1, "main", 1, 500L);
        int clsFoo = sr.rtSym("RTCLASS", "com.example.Foo");
        int mRun = sr.rtSym("METHODNAME", "run");
        int fFoo = sr.rtSym("FILENAME", "Foo.java");
        int clsMain = sr.rtSym("RTCLASS", "com.example.Main");
        int mMain = sr.rtSym("METHODNAME", "main");
        long off = sr.blobRaw(1, Format.BLOB_MAGIC, Format.BLOB_STACK, stackPayload(new int[][] {
            {clsFoo, mRun, fFoo, 42},
            {clsMain, mMain, 0, -1}, // fileSym 0 allowed (§12)
        }));
        sr.rec(1, Format.STACK_SNAPSHOT, SyntheticRecording.flags(0, Format.KIND_BLOB, 0),
                0, 0, 0, 0, 0, off);
        return sr.write();
    }

    /** Encodes a §3 STACK payload: count then {classSym, methodSym, fileSym, line} entries. */
    private static byte[] stackPayload(final int[][] frames) {
        ByteBuffer b = ByteBuffer.allocate(2 + frames.length * Format.STACK_FRAME_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) frames.length);
        for (int[] f : frames) {
            b.putInt(f[0]);
            b.putInt(f[1]);
            b.putInt(f[2]);
            b.putInt(f[3]);
        }
        return b.array();
    }

    @Test
    void schema4OpensAndCapturedFramesRoundTrip() throws IOException {
        Path dir = stackRecording();
        try (Recording rec = Recording.open(dir)) {
            assertEquals(4, rec.schemaVersion());
            Rec r = rec.recordAt(1, 2);
            assertEquals(Format.STACK_SNAPSHOT, r.type());
            assertEquals(Format.KIND_BLOB, r.afterKind());
            List<Recording.CapturedFrame> frames = rec.stackAt(1, r.after());
            assertEquals(2, frames.size());
            assertEquals(new Recording.CapturedFrame("com.example.Foo", "run", "Foo.java", 42),
                    frames.get(0), "innermost frame first, fully resolved");
            assertEquals("com.example.Main", frames.get(1).classBinary());
            assertEquals("main", frames.get(1).methodName());
            assertNull(frames.get(1).fileName(), "fileSym 0 resolves to a null file name");
            assertEquals(-1, frames.get(1).line());
        }
    }

    @Test
    void dumpRendersStackSnapshotHeaderAndFrameLines() throws IOException {
        Path dir = stackRecording();
        TestCli.Result r = TestCli.run("dump", "--dir", dir.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.outContains("stack-snapshot (2 frames)"), r.out());
        assertTrue(r.outContains("at com.example.Foo.run (Foo.java:42)"), r.out());
        assertTrue(r.outContains("at com.example.Main.main (Unknown Source)"), r.out());
        // innermost-first: the Foo frame line precedes the Main frame line
        assertTrue(r.out().indexOf("Foo.run") < r.out().indexOf("Main.main"), r.out());
    }

    @Test
    void verifyPassesAndStatsCountsTheNewType() throws IOException {
        Path dir = stackRecording();
        TestCli.Result verify = TestCli.run("verify", "--dir", dir.toString());
        assertEquals(0, verify.code(), "unexpected violations:\n" + verify.out());
        TestCli.Result stats = TestCli.run("stats", "--dir", dir.toString());
        assertEquals(0, stats.code(), stats.err());
        assertTrue(stats.outContains("STACK_SNAPSHOT"), stats.out());
    }

    @Test
    void verifyFlagsOutOfBoundsCountAndUnresolvableFrameSymbols() throws IOException {
        SyntheticRecording sr = new SyntheticRecording(tmp.resolve("rec")).schema(4);
        sr.threadStart(1, "main", 1, 500L);
        int cls = sr.rtSym("RTCLASS", "com.example.Foo");
        // capture 1: count says 3 frames, payload holds 1 — §12 bounds violation
        byte[] tooShort = stackPayload(new int[][] {{cls, cls, 0, 1}});
        tooShort[0] = 3;
        long shortOff = sr.blobRaw(1, Format.BLOB_MAGIC, Format.BLOB_STACK, tooShort);
        sr.rec(1, Format.STACK_SNAPSHOT, SyntheticRecording.flags(0, Format.KIND_BLOB, 0),
                0, 0, 0, 0, 0, shortOff);
        // capture 2: method symbol id 99 has no rt-symbols row
        long badSymOff = sr.blobRaw(1, Format.BLOB_MAGIC, Format.BLOB_STACK,
                stackPayload(new int[][] {{cls, 99, 0, 1}}));
        sr.rec(1, Format.STACK_SNAPSHOT, SyntheticRecording.flags(0, Format.KIND_BLOB, 0),
                0, 0, 0, 0, 0, badSymOff);
        Path dir = sr.write();

        TestCli.Result r = TestCli.run("verify", "--dir", dir.toString());
        assertEquals(1, r.code(), r.out());
        assertTrue(r.outContains("stack payload declares 3 frames"), r.out());
        assertTrue(r.outContains("stack frame references unknown method symbol 99"), r.out());
    }

    @Test
    void schemaBeyondFourIsStillRefused() throws IOException {
        SyntheticRecording sr = new SyntheticRecording(tmp.resolve("rec")).schema(5);
        sr.threadStart(1, "main", 1, 500L);
        Path dir = sr.write();
        IOException e = assertThrows(IOException.class, () -> Recording.open(dir).close());
        assertTrue(e.getMessage().contains("unsupported schema version"), e.getMessage());
    }

    /**
     * Real-world schema-3 regression: the consensus-node transfer capture must keep opening,
     * reading and verifying untouched by the schema-4 additions. The capture itself carries a
     * handful of pre-existing violations (the node died by SIGKILL: torn blob tails,
     * unbalanced exits), so the assertion is not "verify is clean" but "nothing the schema-4
     * machinery added fires on it". Self-skips when the recording is not on this machine.
     */
    @Test
    void realSchema3RecordingStillOpensAndVerifies() throws IOException {
        Path dir = Path.of(System.getProperty("hindsight.realRecordingDir",
                "/Users/ruslan.velkov/IdeaProjects/cloned/hiero-consensus-node/hedera-node/"
                        + "test-clients/build/hindsight/recordings/transfer-11"));
        assumeTrue(Files.isDirectory(dir), "real schema-3 recording not present — skipping");
        try (Recording rec = Recording.open(dir)) {
            assertEquals(3, rec.schemaVersion());
        }
        TestCli.Result stats = TestCli.run("stats", "--dir", dir.toString());
        assertEquals(0, stats.code(), stats.err());
        assertFalse(stats.outContains("STACK_SNAPSHOT"), "a schema-3 recording has no captures");
        TestCli.Result verify = TestCli.run("verify", "--dir", dir.toString());
        assertFalse(verify.outContains("STACK_SNAPSHOT"),
                "schema-4 checks must not fire on schema-3 data:\n" + verify.out());
        assertFalse(verify.outContains("stack payload") || verify.outContains("stack frame"),
                "schema-4 checks must not fire on schema-3 data:\n" + verify.out());
        assertFalse(verify.outContains("unknown record type"),
                "every schema-3 record type must stay known:\n" + verify.out());
    }
}
