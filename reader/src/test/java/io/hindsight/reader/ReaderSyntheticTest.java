// SPDX-License-Identifier: Apache-2.0
package io.hindsight.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec cross-check: recordings assembled byte-by-byte from FORMAT.md (never via
 * the writer) must round-trip through {@code dump} / {@code stats} / {@code verify}
 * with the §12 semantics — including truncated-tail tolerance, EXCEPTIONAL exit
 * rendering, the BOXED flag, and snapshot blob parsing.
 */
final class ReaderSyntheticTest {

    @TempDir
    Path tmp;

    /** The shared "one transfer call" fixture used by several tests. */
    private SyntheticRecording basic(final Path dir) {
        SyntheticRecording sr = new SyntheticRecording(dir);
        int clsBank = sr.sym("CLASS", "com.example.Bank");
        int fileBank = sr.sym("FILE", "Bank.java");
        int mTransfer = sr.sym("METHOD", "transfer");
        int fBalance = sr.sym("FIELD", "balance");
        int vFee = sr.sym("VAR", "fee");
        int siteTransfer = sr.methodSite(clsBank, fileBank, 80, mTransfer, "(ILjava/lang/String;)J", false);
        int siteBalance = sr.fieldSite(clsBank, fileBank, 88, fBalance, "J", false);
        int siteFee = sr.localSite(clsBank, fileBank, 90, vFee, "D", 4);

        sr.threadStart(1, "main", 1, 1_000_000L);
        sr.firstSeen(1, 5, "com.example.Bank");
        sr.firstSeen(1, 7, "com.example.Account");
        long aliceOff = sr.blobUtf8(1, "alice");
        sr.rec(1, Format.METHOD_ENTER, 0, 0, 2, siteTransfer, 5, 0, 0);
        sr.rec(1, Format.ARG, SyntheticRecording.flags(0, Format.KIND_INLINE, 0),
                Format.TAG_INT, 0, siteTransfer, 0, 0, 42);
        sr.rec(1, Format.ARG, SyntheticRecording.flags(0, Format.KIND_BLOB, 0),
                Format.TAG_STRING, 1, siteTransfer, 0, 0, aliceOff);
        sr.rec(1, Format.FIELD_WRITE, SyntheticRecording.flags(Format.KIND_INLINE, Format.KIND_INLINE, 0),
                Format.TAG_LONG, 0, siteBalance, 7, 1000, 900);
        sr.rec(1, Format.LOCAL_WRITE, SyntheticRecording.flags(0, Format.KIND_INLINE, 0),
                Format.TAG_DOUBLE, 4, siteFee, 0, 0, Double.doubleToRawLongBits(3.5));
        sr.rec(1, Format.METHOD_EXIT, SyntheticRecording.flags(0, Format.KIND_INLINE, 0),
                Format.TAG_LONG, 0, siteTransfer, 0, 0, 9);
        return sr;
    }

    @Test
    void dumpRendersCallTreeFieldWritesAndStrings() throws IOException {
        Path dir = basic(tmp.resolve("rec")).write();
        TestCli.Result r = TestCli.run("dump", "--dir", dir.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.outContains("Bank.transfer(2 args) this=Bank#5 (Bank.java:80)"), r.out());
        assertTrue(r.outContains("arg0 = 42"), r.out());
        assertTrue(r.outContains("arg1 = \"alice\""), r.out());
        // §12 example shape: [seq N tT] indented body; depth 1 under the ENTER
        assertTrue(r.outContains("]   Account#7.balance: 1000 -> 900 (Bank.java:88)"), r.out());
        assertTrue(r.outContains("local fee = 3.5 (Bank.java:90)"), r.out());
        assertTrue(r.outContains("return 9 [Bank.transfer]"), r.out());
        assertTrue(r.outContains(" t1] "), "merged dump must carry thread prefix: " + r.out());
    }

    @Test
    void dumpSingleThreadOmitsThreadPrefixAndHonorsLimit() throws IOException {
        Path dir = basic(tmp.resolve("rec")).write();
        TestCli.Result r = TestCli.run("dump", "--dir", dir.toString(), "--thread", "1", "--limit", "3");
        assertEquals(0, r.code(), r.err());
        assertFalse(r.outContains(" t1]"), r.out());
        assertEquals(3, r.out().lines().count(), r.out());
    }

    @Test
    void dumpMethodFilterKeepsOnlyMatchingMethodRecords() throws IOException {
        Path dir = basic(tmp.resolve("rec")).write();
        TestCli.Result r = TestCli.run("dump", "--dir", dir.toString(), "--method", "Bank.transfer");
        assertEquals(0, r.code(), r.err());
        assertTrue(r.outContains("Bank.transfer"), r.out());
        assertFalse(r.outContains("balance: 1000"), r.out());
        assertFalse(r.outContains("thread start"), r.out());
    }

    @Test
    void verifyPassesOnSpecConformantRecording() throws IOException {
        Path dir = basic(tmp.resolve("rec")).write();
        TestCli.Result r = TestCli.run("verify", "--dir", dir.toString());
        assertEquals(0, r.code(), "unexpected violations:\n" + r.out());
        assertTrue(r.outContains("OK"), r.out());
    }

    @Test
    void statsCountsTypesThreadsObjectsAndTimeSpan() throws IOException {
        Path dir = basic(tmp.resolve("rec")).write();
        TestCli.Result r = TestCli.run("stats", "--dir", dir.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.outContains("records: 10"), r.out());
        assertTrue(r.outContains("METHOD_ENTER"), r.out());
        assertTrue(r.outContains("distinct objects: 2"), r.out());
        assertTrue(r.outContains("Bank.transfer"), r.out());
        assertTrue(r.outContains("wall-clock span: 0 ms"), r.out());
    }

    @Test
    void truncatedTailIsToleratedAndMarkerSurfaced() throws IOException {
        SyntheticRecording sr = basic(tmp.resolve("rec"));
        sr.appendRawRecordBytes(1, new byte[17]); // torn trailing record
        Path dir = sr.write();
        sr.markTruncated("size cap reached");

        try (Recording rec = Recording.open(dir)) {
            assertEquals(10, rec.recordCount(1), "partial record must not count");
            assertTrue(rec.hasPartialTail(1));
            assertTrue(rec.isTruncated());
            assertEquals("size cap reached", rec.truncationReason());
        }
        TestCli.Result verify = TestCli.run("verify", "--dir", dir.toString());
        assertEquals(0, verify.code(), "truncated tail must not be a violation:\n" + verify.out());
        TestCli.Result dump = TestCli.run("dump", "--dir", dir.toString());
        assertTrue(dump.outContains("# recording TRUNCATED: size cap reached"), dump.out());
    }

    @Test
    void exceptionalExitRendersThrowWithThrowableObjectId() throws IOException {
        SyntheticRecording sr = new SyntheticRecording(tmp.resolve("rec"));
        int cls = sr.sym("CLASS", "com.example.Bank");
        int file = sr.sym("FILE", "Bank.java");
        int m = sr.sym("METHOD", "withdraw");
        int site = sr.methodSite(cls, file, 30, m, "(J)V", false);
        sr.threadStart(1, "main", 1, 500L);
        sr.firstSeen(1, 9, "com.example.InsufficientFundsException");
        sr.rec(1, Format.METHOD_ENTER, 0, 0, 1, site, 0, 0, 0);
        sr.rec(1, Format.METHOD_EXIT,
                SyntheticRecording.flags(0, Format.KIND_OBJECT_ID, Format.FLAG_EXCEPTIONAL),
                Format.TAG_REF, 0, site, 0, 0, 9);
        Path dir = sr.write();

        TestCli.Result dump = TestCli.run("dump", "--dir", dir.toString());
        assertEquals(0, dump.code(), dump.err());
        assertTrue(dump.outContains("throw InsufficientFundsException#9"), dump.out());
        assertEquals(0, TestCli.run("verify", "--dir", dir.toString()).code());
    }

    @Test
    void boxedFlagRendersAsPrimitiveInline() throws IOException {
        SyntheticRecording sr = new SyntheticRecording(tmp.resolve("rec"));
        int cls = sr.sym("CLASS", "com.example.Bank");
        int m = sr.sym("METHOD", "count");
        int site = sr.methodSite(cls, 0, 0, m, "(Ljava/lang/Integer;)V", true);
        sr.threadStart(1, "main", 1, 500L);
        sr.rec(1, Format.METHOD_ENTER, 0, 0, 1, site, 0, 0, 0);
        sr.rec(1, Format.ARG,
                SyntheticRecording.flags(0, Format.KIND_INLINE, Format.FLAG_BOXED),
                Format.TAG_INT, 0, site, 0, 0, 42);
        Path dir = sr.write();

        try (Recording rec = Recording.open(dir)) {
            Rec arg = rec.recordAt(1, 3);
            assertEquals(Format.ARG, arg.type());
            assertTrue(arg.isBoxed(), "BOXED flag must decode");
            assertEquals(Format.KIND_INLINE, arg.afterKind());
            assertEquals("42", new ValueRenderer(rec).render(arg, false));
        }
        TestCli.Result dump = TestCli.run("dump", "--dir", dir.toString());
        assertTrue(dump.outContains("arg0 = 42"), dump.out());
        assertEquals(0, TestCli.run("verify", "--dir", dir.toString()).code());
    }

    @Test
    void snapshotBlobParsesAndRenders() throws IOException {
        SyntheticRecording sr = new SyntheticRecording(tmp.resolve("rec"));
        sr.threadStart(1, "main", 1, 500L);
        int rtAccount = sr.rtSym("RTCLASS", "com.example.Account");
        int fName = sr.rtSym("FIELDNAME", "name");
        int fBalance = sr.rtSym("FIELDNAME", "balance");
        sr.rec(1, Format.OBJECT_FIRST_SEEN, 0, 0, 0, rtAccount, 7, 0, 0);
        long aliceOff = sr.blobUtf8(1, "alice");
        byte[] payload = SyntheticRecording.snapshotPayload(rtAccount, new int[][] {
            {fName, Format.TAG_STRING, Format.KIND_BLOB, (int) aliceOff, (int) (aliceOff >>> 32)},
            {fBalance, Format.TAG_LONG, Format.KIND_INLINE, 100, 0},
        });
        long snapOff = sr.blobRaw(1, Format.BLOB_MAGIC, Format.BLOB_SNAPSHOT, payload);
        sr.rec(1, Format.SNAPSHOT, SyntheticRecording.flags(0, Format.KIND_BLOB, 0),
                0, 0, 0, 7, 0, snapOff);
        Path dir = sr.write();

        try (Recording rec = Recording.open(dir)) {
            Recording.Snapshot snap = rec.snapshotAt(1, snapOff);
            assertEquals(rtAccount, snap.classSym());
            assertEquals(2, snap.fields().size());
            assertEquals(fName, snap.fields().get(0).nameSym());
            assertEquals(Format.KIND_BLOB, snap.fields().get(0).kind());
            assertEquals(100, snap.fields().get(1).value());
            assertNotNull(rec.objectClassName(7));
        }
        TestCli.Result dump = TestCli.run("dump", "--dir", dir.toString());
        assertEquals(0, dump.code(), dump.err());
        assertTrue(dump.outContains("snapshot Account#7 {name=\"alice\", balance=100}"), dump.out());
        assertEquals(0, TestCli.run("verify", "--dir", dir.toString()).code());
    }

    @Test
    void staticSuffixOnDescIsSplitIntoSiteFlag() throws IOException {
        SyntheticRecording sr = new SyntheticRecording(tmp.resolve("rec"));
        int cls = sr.sym("CLASS", "com.example.Bank");
        int m = sr.sym("METHOD", "compound");
        int site = sr.methodSite(cls, 0, 0, m, "(JII)J", true);
        sr.threadStart(1, "main", 1, 500L);
        Path dir = sr.write();
        try (Recording rec = Recording.open(dir)) {
            Site s = rec.site(site);
            assertNotNull(s);
            assertTrue(s.isStatic());
            assertEquals("(JII)J", s.desc());
        }
    }

    @Test
    void unknownSubcommandAndMissingDirAreUsageErrors() {
        assertEquals(2, TestCli.run("frobnicate").code());
        assertEquals(2, TestCli.run("dump").code());
        assertEquals(2, TestCli.run("dump", "--dir", tmp.resolve("nope").toString()).code());
    }

    @Test
    void findReportsFirstSeenReferencesAndNoSnapshot() throws IOException {
        // basic fixture: Bank#5 is the ENTER's `this`; Account#7 owns the field write.
        Path dir = basic(tmp.resolve("rec")).write();
        TestCli.Result r = TestCli.run("find", "--dir", dir.toString(), "--object", "5");
        assertEquals(0, r.code(), r.err());
        assertTrue(r.outContains("object #5  (com.example.Bank)"), r.out());
        assertTrue(r.outContains("first-seen: seq"), r.out());
        assertTrue(r.outContains("snapshots:  none"), r.out());
        // referenced once, as the ENTER target (this); the FIRST_SEEN itself is not a reference.
        assertTrue(r.outContains("references: 1  (this/owner 1,"), r.out());
        assertTrue(r.outContains("Bank.transfer"), r.out());
    }

    @Test
    void findAcceptsClassHashIdFormAndCountsFieldOwner() throws IOException {
        Path dir = basic(tmp.resolve("rec")).write();
        TestCli.Result r = TestCli.run("find", "--dir", dir.toString(), "--object", "Account#7");
        assertEquals(0, r.code(), r.err());
        assertTrue(r.outContains("object #7  (com.example.Account)"), r.out());
        assertTrue(r.outContains("references: 1  (this/owner 1,"), r.out());
        assertTrue(r.outContains(".balance:"), r.out());
    }

    @Test
    void findRendersAnExistingSnapshot() throws IOException {
        SyntheticRecording sr = new SyntheticRecording(tmp.resolve("rec"));
        sr.threadStart(1, "main", 1, 100L);
        int rtCls = sr.rtSym("RTCLASS", "com.example.Box");
        int fName = sr.rtSym("FIELDNAME", "value");
        sr.rec(1, Format.OBJECT_FIRST_SEEN, 0, 0, 0, rtCls, 5, 0, 0);
        long snapOff = sr.blobRaw(1, Format.BLOB_MAGIC, Format.BLOB_SNAPSHOT,
                SyntheticRecording.snapshotPayload(
                        rtCls, new int[][] {{fName, Format.TAG_INT, Format.KIND_INLINE, 42, 0}}));
        sr.rec(1, Format.SNAPSHOT, SyntheticRecording.flags(0, Format.KIND_BLOB, 0), 0, 0, 0, 5, 0, snapOff);
        Path dir = sr.write();

        TestCli.Result r = TestCli.run("find", "--dir", dir.toString(), "--object", "5");
        assertEquals(0, r.code(), r.err());
        assertTrue(r.outContains("snapshots:  1"), r.out());
        assertTrue(r.outContains("snapshot Box#5 {value=42}"), r.out());
    }

    @Test
    void findOnMissingIdReportsNoneAndSucceeds() throws IOException {
        Path dir = basic(tmp.resolve("rec")).write();
        TestCli.Result r = TestCli.run("find", "--dir", dir.toString(), "--object", "99");
        assertEquals(0, r.code(), r.err());
        assertTrue(r.outContains("first-seen: none"), r.out());
        assertTrue(r.outContains("references: none"), r.out());
    }

    @Test
    void findRejectsMissingAndMalformedObjectId() throws IOException {
        Path dir = basic(tmp.resolve("rec")).write();
        assertEquals(2, TestCli.run("find", "--dir", dir.toString()).code());
        assertEquals(2, TestCli.run("find", "--dir", dir.toString(), "--object", "notanumber").code());
        assertEquals(2, TestCli.run("find", "--dir", dir.toString(), "--object", "0").code());
    }
}
