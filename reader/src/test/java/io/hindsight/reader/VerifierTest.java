// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code verify} must catch each §12 invariant violation and attribute it to an
 * exact file + record index, with exit code 1. Every scenario deliberately
 * corrupts one aspect of an otherwise spec-conformant synthetic recording.
 */
final class VerifierTest {

    @TempDir
    Path tmp;

    private SyntheticRecording fresh() {
        return new SyntheticRecording(tmp.resolve("rec"));
    }

    @Test
    void corruptedBlobMagicIsReported() throws IOException {
        SyntheticRecording sr = fresh();
        int cls = sr.sym("CLASS", "com.example.Bank");
        int m = sr.sym("METHOD", "note");
        int site = sr.methodSite(cls, 0, 0, m, "(Ljava/lang/String;)V", true);
        sr.threadStart(1, "main", 1, 500L);
        long badOff = sr.blobRaw(1, 0xB6, Format.BLOB_UTF8, "x".getBytes(StandardCharsets.UTF_8));
        sr.rec(1, Format.METHOD_ENTER, 0, 0, 1, site, 0, 0, 0);
        sr.rec(1, Format.ARG, SyntheticRecording.flags(0, Format.KIND_BLOB, 0),
                Format.TAG_STRING, 0, site, 0, 0, badOff);
        Path dir = sr.write();

        TestCli.Result r = TestCli.run("verify", "--dir", dir.toString());
        assertEquals(1, r.code(), r.out());
        assertTrue(r.outContains("bad blob magic"), r.out());
        assertTrue(r.outContains("records-00001.bin[3]"), "must carry file+index: " + r.out());
    }

    @Test
    void seqRegressionIsReported() throws IOException {
        SyntheticRecording sr = fresh();
        int thread = sr.rtSym("THREAD", "main");
        sr.recSeq(10, 1, Format.THREAD_START, 0, 0, 0, thread, 0, 0, 1);
        sr.recSeq(11, 1, Format.TIME_SYNC, 0, 0, 0, 0, 0, 0, 500L);
        sr.recSeq(11, 1, Format.TIME_SYNC, 0, 0, 0, 0, 0, 0, 501L); // not strictly increasing
        Path dir = sr.write();

        TestCli.Result r = TestCli.run("verify", "--dir", dir.toString());
        assertEquals(1, r.code(), r.out());
        assertTrue(r.outContains("seq regression"), r.out());
        assertTrue(r.outContains("records-00001.bin[2]"), r.out());
    }

    @Test
    void exitWithoutEnterIsReported() throws IOException {
        SyntheticRecording sr = fresh();
        int cls = sr.sym("CLASS", "com.example.Bank");
        int m = sr.sym("METHOD", "poke");
        int site = sr.methodSite(cls, 0, 0, m, "()V", true);
        sr.threadStart(1, "main", 1, 500L);
        sr.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, site, 0, 0, 0);
        Path dir = sr.write();

        TestCli.Result r = TestCli.run("verify", "--dir", dir.toString());
        assertEquals(1, r.code(), r.out());
        assertTrue(r.outContains("METHOD_EXIT with no open METHOD_ENTER"), r.out());
    }

    @Test
    void openFramesAtEndOfFileAreTolerated() throws IOException {
        SyntheticRecording sr = fresh();
        int cls = sr.sym("CLASS", "com.example.Bank");
        int m = sr.sym("METHOD", "spin");
        int site = sr.methodSite(cls, 0, 0, m, "()V", true);
        sr.threadStart(1, "main", 1, 500L);
        sr.rec(1, Format.METHOD_ENTER, 0, 0, 0, site, 0, 0, 0); // never exits: live at shutdown
        Path dir = sr.write();

        assertEquals(0, TestCli.run("verify", "--dir", dir.toString()).code());
    }

    @Test
    void unknownSiteIdIsReported() throws IOException {
        SyntheticRecording sr = fresh();
        sr.threadStart(1, "main", 1, 500L);
        sr.rec(1, Format.METHOD_ENTER, 0, 0, 0, 99, 0, 0, 0);
        Path dir = sr.write();

        TestCli.Result r = TestCli.run("verify", "--dir", dir.toString());
        assertEquals(1, r.code(), r.out());
        assertTrue(r.outContains("unknown site id 99"), r.out());
    }

    @Test
    void objectReferenceWithoutFirstSeenIsReported() throws IOException {
        SyntheticRecording sr = fresh();
        int cls = sr.sym("CLASS", "com.example.Account");
        int f = sr.sym("FIELD", "balance");
        int site = sr.fieldSite(cls, 0, 12, f, "J", false);
        sr.threadStart(1, "main", 1, 500L);
        sr.rec(1, Format.FIELD_WRITE, SyntheticRecording.flags(Format.KIND_INLINE, Format.KIND_INLINE, 0),
                Format.TAG_LONG, 0, site, 7, 5, 6); // owner id 7 never announced
        Path dir = sr.write();

        TestCli.Result r = TestCli.run("verify", "--dir", dir.toString());
        assertEquals(1, r.code(), r.out());
        assertTrue(r.outContains("object id 7 with no OBJECT_FIRST_SEEN"), r.out());
    }

    @Test
    void exceptionalFlagOutsideMethodExitIsReported() throws IOException {
        SyntheticRecording sr = fresh();
        int cls = sr.sym("CLASS", "com.example.Account");
        int v = sr.sym("VAR", "x");
        int site = sr.localSite(cls, 0, 3, v, "I", 1);
        sr.threadStart(1, "main", 1, 500L);
        sr.rec(1, Format.LOCAL_WRITE,
                SyntheticRecording.flags(0, Format.KIND_INLINE, Format.FLAG_EXCEPTIONAL),
                Format.TAG_INT, 1, site, 0, 0, 5);
        Path dir = sr.write();

        TestCli.Result r = TestCli.run("verify", "--dir", dir.toString());
        assertEquals(1, r.code(), r.out());
        assertTrue(r.outContains("EXCEPTIONAL flag on LOCAL_WRITE"), r.out());
    }

    @Test
    void snapshotNestedBlobMustBeEarlierUtf8Entry() throws IOException {
        SyntheticRecording sr = fresh();
        sr.threadStart(1, "main", 1, 500L);
        int rtCls = sr.rtSym("RTCLASS", "com.example.Account");
        int fName = sr.rtSym("FIELDNAME", "name");
        sr.rec(1, Format.OBJECT_FIRST_SEEN, 0, 0, 0, rtCls, 7, 0, 0);
        // nested blob offset points PAST the snapshot entry — spec requires "earlier"
        byte[] payload = SyntheticRecording.snapshotPayload(rtCls, new int[][] {
            {fName, Format.TAG_STRING, Format.KIND_BLOB, 10_000, 0},
        });
        long snapOff = sr.blobRaw(1, Format.BLOB_MAGIC, Format.BLOB_SNAPSHOT, payload);
        sr.rec(1, Format.SNAPSHOT, SyntheticRecording.flags(0, Format.KIND_BLOB, 0),
                0, 0, 0, 7, 0, snapOff);
        Path dir = sr.write();

        TestCli.Result r = TestCli.run("verify", "--dir", dir.toString());
        assertEquals(1, r.code(), r.out());
        assertTrue(r.outContains("snapshot nested blob"), r.out());
    }
}
