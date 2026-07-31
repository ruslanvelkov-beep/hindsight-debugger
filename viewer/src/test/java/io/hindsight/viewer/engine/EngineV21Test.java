// SPDX-License-Identifier: Apache-2.0
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
 * White-box tests for the VIEWER.md §21 engine additions: name-gated date (§21.1), container
 * size label + always-populated (§21.5), uncapped instance/method browse (§21.6) and the
 * selected-line / cross-thread run-to-line family (§21.7). Built against synthetic schema-3
 * recordings assembled byte-by-byte from FORMAT.md via {@link SyntheticRecording}.
 *
 * <p>The date literal {@code 2001-09-09 01:46:40 UTC} is the UTC wall-clock of the millis value
 * {@code 1_000_000_000_000L} (matching {@code NumberFormatUtilTest}); its long hex is
 * {@code 0x000000E8D4A51000}.
 */
final class EngineV21Test {

    private static final long MILLIS = 1_000_000_000_000L;
    private static final String MILLIS_HEX = "1000000000000  0x000000E8D4A51000";
    private static final String MILLIS_DATED = MILLIS_HEX + "  (2001-09-09 01:46:40 UTC)";

    // ---- §21.1 name-gated date ----

    /**
     * A static {@code T.run} that writes two long locals both equal to the millis value: one named
     * {@code lastUpdateTime} (dated) and one named {@code count} (hex only). The cursor rests on the
     * trailing LINE so both writes are in scope.
     */
    private static Path buildLocals(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int file = r.sym("FILE", "T.java");
        int cls = r.sym("CLASS", "com.ex.T");
        int mRun = r.sym("METHOD", "run");
        int vTime = r.sym("VAR", "lastUpdateTime");
        int vCount = r.sym("VAR", "count");
        int siteRun = r.methodSite(cls, file, 5, mRun, "()V", true);
        int l5 = r.lineSite(cls, file, 5);
        int sTime = r.localSite(cls, file, 6, vTime, "J", 1);
        int sCount = r.localSite(cls, file, 7, vCount, "J", 3);
        int afterInline = SyntheticRecording.flags(Format.KIND_NONE, Format.KIND_INLINE, 0);

        r.threadStart(1, "main", 1L, 0L);                                   // idx 0,1
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteRun, 0, 0, 0);           // idx 2
        r.rec(1, Format.LOCAL_WRITE, afterInline, Format.TAG_LONG, 1, sTime, 0, 0, MILLIS);   // idx 3
        r.rec(1, Format.LOCAL_WRITE, afterInline, Format.TAG_LONG, 3, sCount, 0, 0, MILLIS);  // idx 4
        r.rec(1, Format.LINE, 0, 0, 0, l5, 0, 0, 0);                        // idx 5
        return r.write();
    }

    @Test
    void dateShowsForEveryInRangeLocalRegardlessOfName(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildLocals(dir))) {
            e.seek(1, 5);
            List<Local> locals = e.locals(0);
            assertEquals(2, locals.size());

            Local time = locals.get(0); // slot 1
            assertEquals("lastUpdateTime", time.name());
            assertEquals(MILLIS_DATED, time.value().display());

            // §26.4: the date is purely value-range-gated — the name no longer matters.
            Local count = locals.get(1); // slot 3
            assertEquals("count", count.name());
            assertEquals(MILLIS_DATED, count.value().display(),
                    "an in-range value is dated whatever its name (the §22.1 gate is retired)");
        }
    }

    // ---- §21.5 container size + populate (and §21.1 nameless element = no date) ----

    /**
     * Snapshotted containers: a {@code HashMap#500} with true size 128 but only 2 captured pairs
     * (so the size must reflect 128, not the shown 2), an {@code ArrayList#600} of {@code [42, millis]}
     * (the millis element must NOT be dated — a nameless element), a {@code HashSet#700} of size 3
     * with 2 captured, an {@code int[]#800} of {@code [10,20,30]}, and a FIELDS {@code Bar#100}
     * (which must get NO size suffix). Cursor on the trailing LINE, past every snapshot.
     */
    private static Path buildContainers(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int file = r.sym("FILE", "C.java");
        int cls = r.sym("CLASS", "com.ex.C");
        int mMain = r.sym("METHOD", "main");
        int siteMain = r.methodSite(cls, file, 5, mMain, "()V", true);
        int l5 = r.lineSite(cls, file, 5);
        int rtMap = r.rtSym("RTCLASS", "java.util.HashMap");
        int rtList = r.rtSym("RTCLASS", "java.util.ArrayList");
        int rtSet = r.rtSym("RTCLASS", "java.util.HashSet");
        int rtArr = r.rtSym("RTCLASS", "[I");
        int rtBar = r.rtSym("RTCLASS", "com.ex.Bar");
        int rtBalance = r.rtSym("FIELDNAME", "balance");
        int rtItems = r.rtSym("FIELDNAME", "items");
        int blobAfter = SyntheticRecording.flags(Format.KIND_NONE, Format.KIND_BLOB, 0);

        r.threadStart(1, "main", 1L, 0L);                     // idx 0,1
        r.firstSeen(1, 100L, "com.ex.Bar");                    // idx 2
        r.firstSeen(1, 500L, "java.util.HashMap");             // idx 3
        r.firstSeen(1, 600L, "java.util.ArrayList");           // idx 4
        r.firstSeen(1, 700L, "java.util.HashSet");             // idx 5
        r.firstSeen(1, 800L, "[I");                            // idx 6

        long barSnap = r.snapshotFields(1, rtBar, SyntheticRecording.fieldList(
                new SyntheticRecording.Field(rtBalance, Format.TAG_INT, Format.KIND_INLINE, 1000L),
                new SyntheticRecording.Field(rtItems, Format.TAG_REF, Format.KIND_OBJECT_ID, 600L)));
        r.rec(1, Format.SNAPSHOT, blobAfter, 0, 0, 0, 100L, 0, barSnap);    // idx 7

        long k2 = r.blobUtf8(1, "k2");
        long mapSnap = r.snapshotContainer(1, rtMap, Format.FORM_MAP, 128, 2, List.of(
                new SyntheticRecording.Entry(Format.TAG_REF, Format.KIND_OBJECT_ID, 100L),
                new SyntheticRecording.Entry(Format.TAG_INT, Format.KIND_INLINE, 7L),
                new SyntheticRecording.Entry(Format.TAG_STRING, Format.KIND_BLOB, k2),
                new SyntheticRecording.Entry(Format.TAG_INT, Format.KIND_INLINE, 42L)));
        r.rec(1, Format.SNAPSHOT, blobAfter, 0, 0, 0, 500L, 0, mapSnap);    // idx 8

        long listSnap = r.snapshotContainer(1, rtList, Format.FORM_LIST, 2, 2, List.of(
                new SyntheticRecording.Entry(Format.TAG_INT, Format.KIND_INLINE, 42L),
                new SyntheticRecording.Entry(Format.TAG_LONG, Format.KIND_INLINE, MILLIS)));
        r.rec(1, Format.SNAPSHOT, blobAfter, 0, 0, 0, 600L, 0, listSnap);   // idx 9

        long setSnap = r.snapshotContainer(1, rtSet, Format.FORM_SET, 3, 2, List.of(
                new SyntheticRecording.Entry(Format.TAG_INT, Format.KIND_INLINE, 11L),
                new SyntheticRecording.Entry(Format.TAG_INT, Format.KIND_INLINE, 22L)));
        r.rec(1, Format.SNAPSHOT, blobAfter, 0, 0, 0, 700L, 0, setSnap);    // idx 10

        long arrSnap = r.snapshotContainer(1, rtArr, Format.FORM_ARRAY, 3, 3, List.of(
                new SyntheticRecording.Entry(Format.TAG_INT, Format.KIND_INLINE, 10L),
                new SyntheticRecording.Entry(Format.TAG_INT, Format.KIND_INLINE, 20L),
                new SyntheticRecording.Entry(Format.TAG_INT, Format.KIND_INLINE, 30L)));
        r.rec(1, Format.SNAPSHOT, blobAfter, 0, 0, 0, 800L, 0, arrSnap);    // idx 11

        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteMain, 0, 0, 0);          // idx 12
        r.rec(1, Format.LINE, 0, 0, 0, l5, 0, 0, 0);                        // idx 13
        return r.write();
    }

    @Test
    void hashMapShowsTrueSizeAndPopulates(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildContainers(dir))) {
            ObjectNode root = e.objectTree(500L);
            assertEquals("HashMap#500, size=128", root.label(), "size is the true totalSize, not the shown 2");
            assertTrue(root.expandable(), "a container ref stays expandable");
            List<ObjectNode> kids = e.children(root);
            assertEquals(3, kids.size(), "2 captured pairs + the …+126 more sentinel");
            assertEquals("Bar#100 -> 7  0x00000007", kids.get(0).label());
            assertEquals("\"k2\" -> 42  0x0000002A", kids.get(1).label());
            assertEquals("…+126 more", kids.get(2).label());
        }
    }

    @Test
    void arrayListShowsSizePopulatesAndInRangeElementIsDated(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildContainers(dir))) {
            ObjectNode root = e.objectTree(600L);
            assertEquals("ArrayList#600, size=2", root.label());
            List<ObjectNode> kids = e.children(root);
            assertEquals(2, kids.size());
            // §22.9: [42, millis] are both integral, so 42's decimal right-aligns to the 13-digit
            // millis width; the hex tail follows the padded decimal unchanged.
            assertEquals("[0] = " + " ".repeat(11) + "42  0x0000002A", kids.get(0).label());
            // §26.4: elements are dated by value range alone — namelessness no longer matters.
            assertEquals("[1] = " + MILLIS_DATED, kids.get(1).label());
        }
    }

    @Test
    void hashSetAndArrayShowSizeAndPopulate(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildContainers(dir))) {
            ObjectNode set = e.objectTree(700L);
            assertEquals("HashSet#700, size=3", set.label());
            List<ObjectNode> setKids = e.children(set);
            assertEquals(3, setKids.size(), "2 captured + …+1 more");
            assertEquals("[0] = 11  0x0000000B", setKids.get(0).label());
            assertEquals("…+1 more", setKids.get(2).label());

            ObjectNode arr = e.objectTree(800L);
            // §22.9: arrays omit the ", size=N" suffix — their length shows in the type name.
            assertEquals("int[]#800", arr.label());
            List<ObjectNode> arrKids = e.children(arr);
            assertEquals(3, arrKids.size());
            // Elements 10/20/30 are already the same width, so alignment is a no-op here.
            assertEquals("[2] = 30  0x0000001E", arrKids.get(2).label());
        }
    }

    @Test
    void fieldsObjectGetsNoSizeSuffix(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildContainers(dir))) {
            // A plain FIELDS object never gets a size label (only unfolded containers do).
            assertEquals("Bar#100", e.objectTree(100L).label());
        }
    }

    @Test
    void containerValuedFieldIsExpandableWithSizeAndPopulates(@TempDir final Path dir) throws IOException {
        // §21.5 candidate: "a field whose value is a container" must be expandable, carry the size,
        // and populate — verified via the field path (childNode), not just a top-level objectTree.
        try (Engine e = Engine.open(buildContainers(dir))) {
            List<ObjectNode> barFields = e.children(e.objectTree(100L));
            assertEquals(2, barFields.size());
            ObjectNode items = barFields.get(1);
            assertEquals("items = ArrayList#600, size=2", items.label());
            assertTrue(items.expandable(), "a container-valued field node stays expandable");
            List<ObjectNode> elems = e.children(items);
            assertEquals(2, elems.size(), "the container-valued field populates");
            // §22.9: 42 right-aligns to the 13-digit millis element in the same list.
            assertEquals("[0] = " + " ".repeat(11) + "42  0x0000002A", elems.get(0).label());
        }
    }

    // ---- §21.6 uncapped browse ----

    /** Three instances of {@code com.ex.Foo} and two entries of one method site, LINE-free. */
    private static Path buildBrowse(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int file = r.sym("FILE", "Foo.java");
        int cls = r.sym("CLASS", "com.ex.Foo");
        int mAlpha = r.sym("METHOD", "alpha");
        int siteAlpha = r.methodSite(cls, file, 10, mAlpha, "()V", true);
        r.threadStart(1, "t1", 1L, 0L);
        r.firstSeen(1, 100L, "com.ex.Foo");
        r.firstSeen(1, 101L, "com.ex.Foo");
        r.firstSeen(1, 102L, "com.ex.Foo");
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteAlpha, 0, 0, 0);
        r.rec(1, Format.METHOD_EXIT, SyntheticRecording.flags(Format.KIND_NONE, Format.KIND_NONE, 0),
                Format.TAG_VOID, 0, siteAlpha, 0, 0, 0);
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteAlpha, 0, 0, 0);
        return r.write();
    }

    @Test
    void instancesAndCallsHonourAHugeCap(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildBrowse(dir))) {
            List<Instance> all = e.instancesOf("com.ex.Foo", Integer.MAX_VALUE);
            assertEquals(3, all.size(), "a huge cap returns min(cap,size) = all instances, no internal 500 clamp");

            int siteId = e.methodSites().get(0).siteId();
            List<Call> calls = e.callsOf(siteId, Integer.MAX_VALUE);
            assertEquals(2, calls.size(), "a huge cap returns all calls");
        }
    }

    // ---- §21.7 run-to-line (selected line + cross-thread) ----

    /**
     * Two threads whose LINE records for {@code com.ex.Foo:10} interleave by seq. t1: LINE10 (seq6),
     * LINE11 (seq9), LINE10 (seq10). t2: LINE10 (seq8) — sits by seq between t1's two LINE10s.
     * Record indices: t1 [ENTER 2, LINE10 3, LINE11 4, LINE10 5, EXIT 6], t2 [ENTER 2, LINE10 3, EXIT 4].
     */
    private static Path buildRunToLine(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int file = r.sym("FILE", "Foo.java");
        int cls = r.sym("CLASS", "com.ex.Foo");
        int mRun = r.sym("METHOD", "run");
        int site = r.methodSite(cls, file, 5, mRun, "()V", true);
        int l10 = r.lineSite(cls, file, 10);
        int l11 = r.lineSite(cls, file, 11);
        int voidNone = SyntheticRecording.flags(Format.KIND_NONE, Format.KIND_NONE, 0);

        r.threadStart(1, "t1", 1L, 0L);                              // t1 idx 0,1 (seq 1,2)
        r.threadStart(2, "t2", 2L, 0L);                              // t2 idx 0,1 (seq 3,4)
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, site, 0, 0, 0);       // t1 idx 2 (seq 5)
        r.rec(1, Format.LINE, 0, 0, 0, l10, 0, 0, 0);                // t1 idx 3 (seq 6)  A
        r.rec(2, Format.METHOD_ENTER, 0, 0, 0, site, 0, 0, 0);       // t2 idx 2 (seq 7)
        r.rec(2, Format.LINE, 0, 0, 0, l10, 0, 0, 0);                // t2 idx 3 (seq 8)  B
        r.rec(1, Format.LINE, 0, 0, 0, l11, 0, 0, 0);                // t1 idx 4 (seq 9)
        r.rec(1, Format.LINE, 0, 0, 0, l10, 0, 0, 0);                // t1 idx 5 (seq 10) C
        r.rec(1, Format.METHOD_EXIT, voidNone, Format.TAG_VOID, 0, site, 0, 0, 0); // t1 idx 6 (seq 11)
        r.rec(2, Format.METHOD_EXIT, voidNone, Format.TAG_VOID, 0, site, 0, 0, 0); // t2 idx 4 (seq 12)
        return r.write();
    }

    @Test
    void runToLineSameThreadForwardAndBack(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildRunToLine(dir))) {
            e.seek(1, 3); // t1 LINE10 (A)
            StepResult fwd = e.runToLine("Foo.java", 10, true, true);
            assertTrue(fwd.moved());
            assertEquals(1, e.currentTid());
            assertEquals(5, e.currentIndex(), "same-thread forward reaches t1's next LINE10 (C), skipping LINE11");

            StepResult back = e.runToLine("Foo.java", 10, false, true);
            assertTrue(back.moved());
            assertEquals(1, e.currentTid());
            assertEquals(3, e.currentIndex(), "same-thread backward returns to A");
        }
    }

    @Test
    void runToLineCrossThreadUsesGlobalSeq(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildRunToLine(dir))) {
            e.seek(1, 3); // t1 LINE10 (A, seq 6)
            StepResult fwd = e.runToLine("Foo.java", 10, true, false);
            assertTrue(fwd.moved());
            assertEquals(2, e.currentTid(), "cross-thread forward lands on t2's LINE10 (B, seq 8) before t1's C (seq 10)");
            assertEquals(3, e.currentIndex());

            e.seek(1, 5); // t1 LINE10 (C, seq 10)
            StepResult back = e.runToLine("Foo.java", 10, false, false);
            assertTrue(back.moved());
            assertEquals(2, e.currentTid(), "cross-thread backward lands on t2's B (seq 8) before t1's A (seq 6)");
            assertEquals(3, e.currentIndex());
        }
    }

    @Test
    void runToLineNoAppearanceInDirection(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildRunToLine(dir))) {
            e.seek(1, 5); // t1's last LINE10 (C)
            StepResult fwd = e.runToLine("Foo.java", 10, true, true);
            assertFalse(fwd.moved());
            assertEquals(Engine.NO_LATER_PASS, fwd.message());

            // LINE11 occurs once (t1 idx 4); no earlier / later pass in either thread mode.
            e.seek(1, 4);
            assertFalse(e.runToLine("Foo.java", 11, false, true).moved());
            assertEquals(Engine.NO_EARLIER_PASS, e.runToLine("Foo.java", 11, false, true).message());
            assertEquals(Engine.NO_LATER_PASS, e.runToLine("Foo.java", 11, true, false).message());
        }
    }

    @Test
    void runToLineUnresolvableLine(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildRunToLine(dir))) {
            StepResult noLine = e.runToLine("Foo.java", 99, true, true);
            assertFalse(noLine.moved());
            assertEquals(Engine.NO_LINE_RECORD_FOR_LINE, noLine.message(), "a line with no LINE record is unresolvable");

            StepResult noFile = e.runToLine("Nope.java", 10, true, false);
            assertFalse(noFile.moved());
            assertEquals(Engine.NO_LINE_RECORD_FOR_LINE, noFile.message(), "an unknown file is unresolvable");
        }
    }
}
