// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.reader.Format;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * White-box tests for the VIEWER.md §22 engine additions: run-to-line by file name incl. the §22.5
 * {@code Ctrl-Q} backward investigation (§22.5/§22.14), container size in the {@code /} explore
 * (§22.8), array elements omitting the size suffix + numeric alignment (§22.9, alignment unit-tested
 * in {@link NumberFormatUtilTest}), the {@code V} thread filter + uncapped browse (§22.11), ID-field
 * expandability (§22.12) and the last-record / max-seq hint (§22.13). Built against synthetic
 * schema-3 recordings assembled byte-by-byte from FORMAT.md via {@link SyntheticRecording}.
 */
final class EngineV22Test {

    // ---- §22.5 / §22.14 run-to-line by file name (incl. the Ctrl-Q backward fix) ----

    /**
     * One thread; {@code Foo.java:10} passes at record indices 3 (A), 5 (B), 7 (C) and 9 (D). C is a
     * LINE of the inner class {@code Foo$Inner} whose site carries a PATH file symbol
     * ({@code src/com/ex/Foo.java}); it must still resolve under the base name {@code Foo.java},
     * exercising both inner-class sharing and base-name stripping. Index 4 is {@code Foo.java:11}.
     */
    private static Path buildFileLine(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int fileFoo = r.sym("FILE", "Foo.java");
        int filePath = r.sym("FILE", "src/com/ex/Foo.java"); // same base name, via a path
        int clsFoo = r.sym("CLASS", "com.ex.Foo");
        int clsInner = r.sym("CLASS", "com.ex.Foo$Inner");
        int mRun = r.sym("METHOD", "run");
        int mWork = r.sym("METHOD", "work");
        int siteRun = r.methodSite(clsFoo, fileFoo, 5, mRun, "()V", true);
        int siteWork = r.methodSite(clsInner, filePath, 20, mWork, "()V", true);
        int l10a = r.lineSite(clsFoo, fileFoo, 10); // Foo.java:10 (outer)
        int l10b = r.lineSite(clsInner, filePath, 10); // Foo.java:10 (inner, path fileSym)
        int l11 = r.lineSite(clsFoo, fileFoo, 11);
        int voidNone = SyntheticRecording.flags(Format.KIND_NONE, Format.KIND_NONE, 0);

        r.threadStart(1, "main", 1L, 0L); // idx 0,1
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteRun, 0, 0, 0); // idx 2
        r.rec(1, Format.LINE, 0, 0, 0, l10a, 0, 0, 0); // idx 3  A
        r.rec(1, Format.LINE, 0, 0, 0, l11, 0, 0, 0); // idx 4
        r.rec(1, Format.LINE, 0, 0, 0, l10a, 0, 0, 0); // idx 5  B
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteWork, 0, 0, 0); // idx 6
        r.rec(1, Format.LINE, 0, 0, 0, l10b, 0, 0, 0); // idx 7  C (inner class)
        r.rec(1, Format.METHOD_EXIT, voidNone, Format.TAG_VOID, 0, siteWork, 0, 0, 0); // idx 8
        r.rec(1, Format.LINE, 0, 0, 0, l10a, 0, 0, 0); // idx 9  D
        r.rec(1, Format.METHOD_EXIT, voidNone, Format.TAG_VOID, 0, siteRun, 0, 0, 0); // idx 10
        return r.write();
    }

    @Test
    void runToLineByFileForwardAndBackwardAreSymmetric(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildFileLine(dir))) {
            e.seek(1, 3); // A
            assertTrue(e.runToLine("Foo.java", 10, true, true).moved());
            assertEquals(5, e.currentIndex(), "forward A -> B");
            assertTrue(e.runToLine("Foo.java", 10, true, true).moved());
            assertEquals(7, e.currentIndex(), "forward B -> C (inner class sharing Foo.java, path fileSym)");
            assertTrue(e.runToLine("Foo.java", 10, true, true).moved());
            assertEquals(9, e.currentIndex(), "forward C -> D");
            assertFalse(e.runToLine("Foo.java", 10, true, true).moved(), "no later pass past D");
            assertEquals(Engine.NO_LATER_PASS, e.runToLine("Foo.java", 10, true, true).message());

            // §22.5 Ctrl-Q: backward same-thread lands on the IMMEDIATELY-preceding pass each time.
            assertTrue(e.runToLine("Foo.java", 10, false, true).moved());
            assertEquals(7, e.currentIndex(), "back D -> C (not skipping to A)");
            assertTrue(e.runToLine("Foo.java", 10, false, true).moved());
            assertEquals(5, e.currentIndex(), "back C -> B");
            assertTrue(e.runToLine("Foo.java", 10, false, true).moved());
            assertEquals(3, e.currentIndex(), "back B -> A");
            assertFalse(e.runToLine("Foo.java", 10, false, true).moved(), "no earlier pass before A");
            assertEquals(Engine.NO_EARLIER_PASS, e.runToLine("Foo.java", 10, false, true).message());
        }
    }

    @Test
    void runToLineBackwardExactlyReversesForward(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildFileLine(dir))) {
            e.seek(1, 5); // B
            e.runToLine("Foo.java", 10, true, true); // -> C (idx 7)
            assertEquals(7, e.currentIndex());
            e.runToLine("Foo.java", 10, false, true); // -> back to B (idx 5)
            assertEquals(5, e.currentIndex(), "backward exactly reverses the forward hop (symmetry)");
        }
    }

    @Test
    void runToLineUnknownFileIsUnresolvable(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildFileLine(dir))) {
            StepResult res = e.runToLine("Missing.java", 10, true, true);
            assertFalse(res.moved());
            assertEquals(Engine.NO_LINE_RECORD_FOR_LINE, res.message());
        }
    }

    // ---- §22.8 container size shows via the engine's ref rendering (objectTree/children) ----

    /** A {@code HashMap#500} of true size 8 with a single captured pair {@code "k1" -> 42}. */
    private static Path buildMap(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int file = r.sym("FILE", "M.java");
        int cls = r.sym("CLASS", "com.ex.M");
        int mMain = r.sym("METHOD", "main");
        int siteMain = r.methodSite(cls, file, 5, mMain, "()V", true);
        int l5 = r.lineSite(cls, file, 5);
        int rtMap = r.rtSym("RTCLASS", "java.util.HashMap");
        int blobAfter = SyntheticRecording.flags(Format.KIND_NONE, Format.KIND_BLOB, 0);

        r.threadStart(1, "main", 1L, 0L); // idx 0,1
        r.firstSeen(1, 500L, "java.util.HashMap"); // idx 2
        long k1 = r.blobUtf8(1, "k1");
        long mapSnap = r.snapshotContainer(1, rtMap, Format.FORM_MAP, 8, 1, List.of(
                new SyntheticRecording.Entry(Format.TAG_STRING, Format.KIND_BLOB, k1),
                new SyntheticRecording.Entry(Format.TAG_INT, Format.KIND_INLINE, 42L)));
        r.rec(1, Format.SNAPSHOT, blobAfter, 0, 0, 0, 500L, 0, mapSnap); // idx 3
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteMain, 0, 0, 0); // idx 4
        r.rec(1, Format.LINE, 0, 0, 0, l5, 0, 0, 0); // idx 5
        return r.write();
    }

    @Test
    void instanceExploreRootRefCarriesContainerSize(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildMap(dir))) {
            e.seek(1, 5);
            // §22.8: the engine's ref rendering already carries ", size=N" on the objectTree ROOT and
            // on its value Ref, so the / instance-explore only needs to build its root from this ref.
            ObjectNode root = e.objectTree(500L);
            assertTrue(root.value() instanceof Value.Ref, "root value is a ref");
            assertEquals("HashMap#500, size=8", ((Value.Ref) root.value()).display());
            assertEquals("HashMap#500, size=8", root.label());
            assertTrue(root.expandable());
            List<ObjectNode> kids = e.children(root);
            assertEquals(2, kids.size(), "1 captured pair + the …+7 more sentinel");
            assertEquals("\"k1\" -> 42  0x0000002A", kids.get(0).label());
            assertEquals("…+7 more", kids.get(1).label());
        }
    }

    // ---- §22.12 ID fields are expandable (except primitives / scalar wrappers) ----

    /**
     * {@code Bar#100} with four fields: {@code accountId} → an ordinary object, {@code id} → an
     * {@code AtomicInteger} (scalar wrapper), {@code nodeId} → a primitive long, and {@code name} → an
     * ordinary object. Cursor past the snapshot.
     */
    private static Path buildIdFields(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int file = r.sym("FILE", "Bar.java");
        int cls = r.sym("CLASS", "com.ex.Bar");
        int mMain = r.sym("METHOD", "main");
        int siteMain = r.methodSite(cls, file, 5, mMain, "()V", true);
        int l5 = r.lineSite(cls, file, 5);
        int rtBar = r.rtSym("RTCLASS", "com.ex.Bar");
        int fAccountId = r.rtSym("FIELDNAME", "accountId");
        int fId = r.rtSym("FIELDNAME", "id");
        int fNodeId = r.rtSym("FIELDNAME", "nodeId");
        int fName = r.rtSym("FIELDNAME", "name");
        int blobAfter = SyntheticRecording.flags(Format.KIND_NONE, Format.KIND_BLOB, 0);

        r.threadStart(1, "main", 1L, 0L); // idx 0,1
        r.firstSeen(1, 100L, "com.ex.Bar"); // idx 2
        r.firstSeen(1, 200L, "com.ex.AccountID"); // idx 3 (ordinary object)
        r.firstSeen(1, 300L, "java.util.concurrent.atomic.AtomicInteger"); // idx 4 (scalar wrapper)
        r.firstSeen(1, 400L, "com.ex.Name"); // idx 5 (ordinary object)

        long barSnap = r.snapshotFields(1, rtBar, SyntheticRecording.fieldList(
                new SyntheticRecording.Field(fAccountId, Format.TAG_REF, Format.KIND_OBJECT_ID, 200L),
                new SyntheticRecording.Field(fId, Format.TAG_REF, Format.KIND_OBJECT_ID, 300L),
                new SyntheticRecording.Field(fNodeId, Format.TAG_LONG, Format.KIND_INLINE, 5L),
                new SyntheticRecording.Field(fName, Format.TAG_REF, Format.KIND_OBJECT_ID, 400L)));
        r.rec(1, Format.SNAPSHOT, blobAfter, 0, 0, 0, 100L, 0, barSnap); // idx 6
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteMain, 0, 0, 0); // idx 7
        r.rec(1, Format.LINE, 0, 0, 0, l5, 0, 0, 0); // idx 8
        return r.write();
    }

    @Test
    void idFieldsExpandableExceptPrimitiveAndScalarWrapper(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildIdFields(dir))) {
            e.seek(1, 8);
            List<ObjectNode> fields = e.children(e.objectTree(100L));
            assertEquals(4, fields.size());
            // accountId -> ordinary object ref: expandable (id + a drillable object).
            assertTrue(fields.get(0).label().startsWith("accountId = "));
            assertTrue(fields.get(0).expandable(), "an id field holding an object ref is drillable");
            // id -> AtomicInteger (scalar wrapper): NOT expandable even though the name is an id.
            assertTrue(fields.get(1).label().startsWith("id = "));
            assertFalse(fields.get(1).expandable(), "an id field holding a scalar wrapper stays collapsed");
            // nodeId -> primitive long: NOT expandable (an id name must not force a primitive open).
            assertTrue(fields.get(2).label().startsWith("nodeId = "));
            assertFalse(fields.get(2).expandable(), "an id field holding a primitive stays non-expandable");
            // name -> ordinary object ref (non-id): expandable as any object ref is.
            assertTrue(fields.get(3).label().startsWith("name = "));
            assertTrue(fields.get(3).expandable());
        }
    }

    // ---- §22.13 last record + max seq ----

    /**
     * Two threads whose records interleave by seq; the global-max-seq record is t1's EXIT (seq 10,
     * index 4). t2's last record is seq 9.
     */
    private static Path buildTwoThreads(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int file = r.sym("FILE", "T.java");
        int cls = r.sym("CLASS", "com.ex.T");
        int m = r.sym("METHOD", "run");
        int site = r.methodSite(cls, file, 5, m, "()V", true);
        int l5 = r.lineSite(cls, file, 5);
        int voidNone = SyntheticRecording.flags(Format.KIND_NONE, Format.KIND_NONE, 0);

        r.threadStart(1, "t1", 1L, 0L); // t1 idx 0,1 (seq 1,2)
        r.threadStart(2, "t2", 2L, 0L); // t2 idx 0,1 (seq 3,4)
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, site, 0, 0, 0); // t1 idx 2 (seq 5)
        r.rec(1, Format.LINE, 0, 0, 0, l5, 0, 0, 0); // t1 idx 3 (seq 6)
        r.rec(2, Format.METHOD_ENTER, 0, 0, 0, site, 0, 0, 0); // t2 idx 2 (seq 7)
        r.rec(2, Format.LINE, 0, 0, 0, l5, 0, 0, 0); // t2 idx 3 (seq 8)
        r.rec(2, Format.METHOD_EXIT, voidNone, Format.TAG_VOID, 0, site, 0, 0, 0); // t2 idx 4 (seq 9)
        r.rec(1, Format.METHOD_EXIT, voidNone, Format.TAG_VOID, 0, site, 0, 0, 0); // t1 idx 4 (seq 10)
        return r.write();
    }

    @Test
    void lastRecordAndMaxSeqPointAtGlobalMax(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildTwoThreads(dir))) {
            assertEquals(10L, e.maxSeq(), "highest global seq across all threads");
            SeqLocation last = e.lastRecord();
            assertTrue(last.found());
            assertEquals(1, last.tid(), "the max-seq record is t1's EXIT");
            assertEquals(4L, last.index());
            // Consistent with findSeq of that seq.
            SeqLocation viaSeq = e.findSeq(e.maxSeq());
            assertEquals(last.tid(), viaSeq.tid());
            assertEquals(last.index(), viaSeq.index());
        }
    }

    // ---- §22.11 fieldWriteChanges thread filter + uncapped ----

    /** Two threads: t1 writes {@code balance} twice and {@code count} once; t2 writes {@code balance} once. */
    private static Path buildThreadedWrites(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int file = r.sym("FILE", "Acct.java");
        int cls = r.sym("CLASS", "com.ex.Acct");
        int fBalance = r.sym("FIELD", "balance");
        int fCount = r.sym("FIELD", "count");
        int sBalance = r.fieldSite(cls, file, 10, fBalance, "I", false);
        int sCount = r.fieldSite(cls, file, 11, fCount, "I", false);
        int inline = SyntheticRecording.flags(Format.KIND_INLINE, Format.KIND_INLINE, 0);

        r.threadStart(1, "t1", 1L, 0L);
        r.threadStart(2, "t2", 2L, 0L);
        r.firstSeen(1, 100L, "com.ex.Acct");
        r.firstSeen(2, 200L, "com.ex.Acct");
        r.rec(1, Format.FIELD_WRITE, inline, Format.TAG_INT, 0, sBalance, 100L, 0, 10);
        r.rec(1, Format.FIELD_WRITE, inline, Format.TAG_INT, 0, sBalance, 100L, 10, 20);
        r.rec(1, Format.FIELD_WRITE, inline, Format.TAG_INT, 0, sCount, 100L, 0, 1);
        r.rec(2, Format.FIELD_WRITE, inline, Format.TAG_INT, 0, sBalance, 200L, 0, 30);
        return r.write();
    }

    @Test
    void fieldWriteChangesThreadFilterAndUncap(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildThreadedWrites(dir))) {
            // All threads (tid <= 0), uncapped — three balance writes (2 on t1, 1 on t2).
            assertEquals(3, e.fieldWriteChanges("balance", 0, Integer.MAX_VALUE).size(), "all threads, uncapped");
            assertEquals(3, e.countFieldWriteChanges("balance", 0));
            // Thread 1 only.
            assertEquals(2, e.fieldWriteChanges("balance", 1, Integer.MAX_VALUE).size());
            assertEquals(2, e.countFieldWriteChanges("balance", 1));
            // Thread 2 only.
            List<FieldWriteHit> t2 = e.fieldWriteChanges("balance", 2, Integer.MAX_VALUE);
            assertEquals(1, t2.size());
            assertEquals(2, t2.get(0).tid());
            assertEquals(1, e.countFieldWriteChanges("balance", 2));
            // The old all-threads overloads still delegate to tid 0.
            assertEquals(3, e.fieldWriteChanges("balance", Integer.MAX_VALUE).size());
            assertEquals(3, e.countFieldWriteChanges("balance"));
            // A cap of 1 is still honoured under the thread-aware overload.
            assertEquals(1, e.fieldWriteChanges("balance", 0, 1).size());
        }
    }
}
