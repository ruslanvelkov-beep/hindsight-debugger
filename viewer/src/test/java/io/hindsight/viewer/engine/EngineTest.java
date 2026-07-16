// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.reader.Format;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * White-box tests of the reconstruction engine (VIEWER.md §2) against a synthetic
 * schema-3 recording assembled byte-by-byte from FORMAT.md. The scenario is a
 * static {@code Foo.main} that writes a static field then calls the instance
 * method {@code Foo.helper(int)} which writes a local and a {@code Bar#100} field,
 * exercising nested frames, stepping, locals, object trees and watches.
 */
final class EngineTest {

    // Record indices in the built recording (see build()), referenced by assertions.
    private static final int IDX_ENTER_MAIN = 5;
    private static final int IDX_LINE10 = 6;
    private static final int IDX_LINE11 = 7;
    private static final int IDX_LINE12 = 9;
    private static final int IDX_ENTER_HELPER = 10;
    private static final int IDX_LINE20 = 12;
    private static final int IDX_LINE21 = 14;
    private static final int IDX_EXIT_HELPER = 16;
    private static final int IDX_LINE13 = 17;
    private static final int IDX_LINE11_AGAIN = 18;
    private static final int IDX_EXIT_MAIN = 19;

    private static Path build(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int fileFoo = r.sym("FILE", "Foo.java");
        int classFoo = r.sym("CLASS", "com.ex.Foo");
        int classBar = r.sym("CLASS", "com.ex.Bar");
        int methMain = r.sym("METHOD", "main");
        int methHelper = r.sym("METHOD", "helper");
        int fieldBalance = r.sym("FIELD", "balance");
        int fieldCount = r.sym("FIELD", "COUNT");
        int varX = r.sym("VAR", "x");

        int rtBar = r.rtSym("RTCLASS", "com.ex.Bar");
        int rtBalance = r.rtSym("FIELDNAME", "balance");

        int siteMain = r.methodSite(classFoo, fileFoo, 10, methMain, "([Ljava/lang/String;)V", true);
        int siteHelper = r.methodSite(classFoo, fileFoo, 20, methHelper, "(I)I", false);
        int l10 = r.lineSite(classFoo, fileFoo, 10);
        int l11 = r.lineSite(classFoo, fileFoo, 11);
        int l12 = r.lineSite(classFoo, fileFoo, 12);
        int l13 = r.lineSite(classFoo, fileFoo, 13);
        int l20 = r.lineSite(classFoo, fileFoo, 20);
        int l21 = r.lineSite(classFoo, fileFoo, 21);
        int siteBalance = r.fieldSite(classBar, fileFoo, 21, fieldBalance, "I", false);
        int siteCount = r.fieldSite(classFoo, fileFoo, 11, fieldCount, "I", true);
        int siteLocalX = r.localSite(classFoo, fileFoo, 20, varX, "I", 2);

        int inline = SyntheticRecording.flags(Format.KIND_INLINE, Format.KIND_INLINE, 0);
        int afterInline = SyntheticRecording.flags(Format.KIND_NONE, Format.KIND_INLINE, 0);
        int blobAfter = SyntheticRecording.flags(Format.KIND_NONE, Format.KIND_BLOB, 0);
        int voidAfter = SyntheticRecording.flags(Format.KIND_NONE, Format.KIND_NONE, 0);
        int staticInline = SyntheticRecording.flags(Format.KIND_INLINE, Format.KIND_INLINE, Format.FLAG_STATIC);

        r.threadStart(1, "worker-1", 42L, 1000L);            // idx 0,1
        r.firstSeen(1, 200L, "com.ex.Foo");                   // idx 2
        r.firstSeen(1, 100L, "com.ex.Bar");                   // idx 3
        long snapOffset = r.snapshotFields(1, rtBar,
                SyntheticRecording.fieldList(new SyntheticRecording.Field(
                        rtBalance, Format.TAG_INT, Format.KIND_INLINE, 1000L)));
        r.rec(1, Format.SNAPSHOT, blobAfter, 0, 0, 0, 100L, 0, snapOffset);   // idx 4
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteMain, 0, 0, 0);           // idx 5
        r.rec(1, Format.LINE, 0, 0, 0, l10, 0, 0, 0);                        // idx 6
        r.rec(1, Format.LINE, 0, 0, 0, l11, 0, 0, 0);                        // idx 7
        r.rec(1, Format.FIELD_WRITE, staticInline, Format.TAG_INT, 0, siteCount, 0, 0, 5); // idx 8
        r.rec(1, Format.LINE, 0, 0, 0, l12, 0, 0, 0);                        // idx 9
        r.rec(1, Format.METHOD_ENTER, 0, 0, 1, siteHelper, 200L, 0, 0);      // idx 10
        r.rec(1, Format.ARG, afterInline, Format.TAG_INT, 0, siteHelper, 0, 0, 7); // idx 11
        r.rec(1, Format.LINE, 0, 0, 0, l20, 0, 0, 0);                        // idx 12
        r.rec(1, Format.LOCAL_WRITE, afterInline, Format.TAG_INT, 2, siteLocalX, 0, 0, 42); // idx 13
        r.rec(1, Format.LINE, 0, 0, 0, l21, 0, 0, 0);                        // idx 14
        r.rec(1, Format.FIELD_WRITE, inline, Format.TAG_INT, 0, siteBalance, 100L, 1000, 900); // idx 15
        r.rec(1, Format.METHOD_EXIT, afterInline, Format.TAG_INT, 0, siteHelper, 0, 0, 99); // idx 16
        r.rec(1, Format.LINE, 0, 0, 0, l13, 0, 0, 0);                        // idx 17
        r.rec(1, Format.LINE, 0, 0, 0, l11, 0, 0, 0);                        // idx 18
        r.rec(1, Format.METHOD_EXIT, voidAfter, Format.TAG_VOID, 0, siteMain, 0, 0, 0); // idx 19
        return r.write();
    }

    @Test
    void frameIndexBracketMatching(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            ThreadIndex ti = e.threadIndex(1);
            assertEquals(20, ti.count);
            assertArrayEqualsLong(new long[] {6, 7, 9, 12, 14, 17, 18}, ti.lineIndices);

            // depth convention: ENTER counts itself, EXIT counts the closing frame.
            assertEquals(1, ti.depth[IDX_LINE10]);
            assertEquals(1, ti.depth[IDX_LINE12]);
            assertEquals(2, ti.depth[IDX_LINE21]);
            assertEquals(2, ti.depth[IDX_EXIT_HELPER]);
            assertEquals(1, ti.depth[IDX_EXIT_MAIN]);

            // frameEnter: innermost enclosing frame's ENTER index.
            assertEquals(IDX_ENTER_MAIN, ti.frameEnter[IDX_LINE12]);
            assertEquals(IDX_ENTER_HELPER, ti.frameEnter[IDX_LINE21]);
            assertEquals(IDX_ENTER_HELPER, ti.frameEnter[IDX_EXIT_HELPER]);

            // parent links and matching exits.
            assertEquals(-1, ti.parentEnter[IDX_ENTER_MAIN]);
            assertEquals(IDX_ENTER_MAIN, ti.parentEnter[IDX_ENTER_HELPER]);
            assertEquals(IDX_EXIT_MAIN, ti.matchingExit[IDX_ENTER_MAIN]);
            assertEquals(IDX_EXIT_HELPER, ti.matchingExit[IDX_ENTER_HELPER]);
        }
    }

    @Test
    void initialCursorIsFirstLine(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            assertEquals(1, e.currentTid());
            assertEquals(IDX_LINE10, e.currentIndex());
            assertEquals(List.of(1), e.threads());
            assertEquals("worker-1", e.threadName(1));
        }
    }

    @Test
    void stackHasCallSiteLineForOuterFrame(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_LINE21);
            List<Frame> stack = e.stack();
            assertEquals(2, stack.size());

            Frame outer = stack.get(0);
            assertEquals(IDX_ENTER_MAIN, outer.enterIndex());
            assertEquals("com.ex.Foo", outer.classBinary());
            assertEquals("main", outer.methodName());
            assertTrue(outer.isStatic());
            assertEquals(12, outer.currentLine()); // call-site line, not entry line

            Frame inner = stack.get(1);
            assertEquals(IDX_ENTER_HELPER, inner.enterIndex());
            assertEquals("helper", inner.methodName());
            assertFalse(inner.isStatic());
            assertEquals(21, inner.currentLine()); // the cursor line
            assertEquals("Foo.java", inner.fileName());

            assertEquals(1, e.selectedFrame()); // innermost after a move
        }
    }

    @Test
    void steppingForwardAndReverse(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            assertEquals(IDX_LINE10, e.currentIndex());
            assertTrue(e.stepInto().moved());
            assertEquals(IDX_LINE11, e.currentIndex());
            assertTrue(e.stepInto().moved());
            assertEquals(IDX_LINE12, e.currentIndex());
            assertTrue(e.stepInto().moved()); // descends into helper
            assertEquals(IDX_LINE20, e.currentIndex());

            assertTrue(e.reverseStep().moved());
            assertEquals(IDX_LINE12, e.currentIndex());

            // stepOver from the call site skips the whole helper body, lands on caller line 13.
            assertTrue(e.stepOver().moved());
            assertEquals(IDX_LINE13, e.currentIndex());

            // reverseStepOver from line 13 skips helper, lands on caller line 12.
            assertTrue(e.reverseStepOver().moved());
            assertEquals(IDX_LINE12, e.currentIndex());
        }
    }

    @Test
    void stepIntoAtLastLineDoesNotMove(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_LINE11_AGAIN); // last LINE record
            StepResult res = e.stepInto();
            assertFalse(res.moved());
            assertEquals(Engine.NO_NEXT_LINE, res.message());
        }
    }

    @Test
    void reverseStepAtFirstLineDoesNotMove(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            StepResult res = e.reverseStep(); // already at first line
            assertFalse(res.moved());
            assertEquals(Engine.NO_EARLIER_LINE, res.message());
        }
    }

    @Test
    void runToCurrentLineHopsBetweenAppearances(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_LINE11); // line 11 appears again at idx 18
            StepResult fwd = e.runToCurrentLine(true);
            assertTrue(fwd.moved());
            assertEquals(IDX_LINE11_AGAIN, e.currentIndex());

            StepResult back = e.runToCurrentLine(false);
            assertTrue(back.moved());
            assertEquals(IDX_LINE11, e.currentIndex());
        }
    }

    @Test
    void runToCurrentLineWithNoOtherAppearance(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            // line 10 (the initial cursor) appears exactly once.
            StepResult res = e.runToCurrentLine(true);
            assertFalse(res.moved());
            assertEquals(Engine.NO_NEXT_APPEARANCE, res.message());
        }
    }

    @Test
    void localsReconstructParamThisAndWrite(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_LINE21);
            List<Local> locals = e.locals(1); // helper frame
            assertEquals(3, locals.size());

            Local self = locals.get(0);
            assertEquals(0, self.slot());
            assertEquals("this", self.name());
            assertInstanceOf(Value.Ref.class, self.value());
            assertEquals(200L, ((Value.Ref) self.value()).objectId());

            Local arg = locals.get(1);
            assertEquals(1, arg.slot());
            assertEquals("arg0", arg.name());
            assertEquals("7", arg.value().display());

            Local x = locals.get(2);
            assertEquals(2, x.slot());
            assertEquals("x", x.name());
            assertEquals("42", x.value().display());

            // The static outer frame has no locals.
            assertTrue(e.locals(0).isEmpty());
        }
    }

    @Test
    void constructorThisRecoveredFromMatchingExit(@TempDir final Path dir) throws IOException {
        // A constructor's METHOD_ENTER carries target 0 (this uninitialized); the live object's
        // id is on the matching METHOD_EXIT. locals() must surface `this` as that object, not null.
        SyntheticRecording r = new SyntheticRecording(dir);
        int cls = r.sym("CLASS", "com.ex.Widget");
        int file = r.sym("FILE", "Widget.java");
        int ctor = r.sym("METHOD", "<init>");
        int siteCtor = r.methodSite(cls, file, 8, ctor, "(I)V", false);
        int lineSite = r.lineSite(cls, file, 9);

        r.threadStart(1, "main", 1, 1_000L);
        r.firstSeen(1, 777L, "com.ex.Widget");
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteCtor, 0, 0, 0);          // ENTER, target 0
        r.rec(1, Format.LINE, 0, Format.TAG_VOID, 0, lineSite, 0, 0, 0);     // cursor lands here
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteCtor, 777L, 0, 0); // EXIT carries this

        try (Engine e = Engine.open(r.write())) {
            List<Local> locals = e.locals(e.stack().size() - 1); // the ctor frame
            Local self = locals.get(0);
            assertEquals("this", self.name());
            assertInstanceOf(Value.Ref.class, self.value(),
                    "constructor `this` must be the object from the matching EXIT, not null");
            assertEquals(777L, ((Value.Ref) self.value()).objectId());
        }
    }

    @Test
    void objectTreeSnapshotBaselineAndFieldWriteOverride(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            // Before the field write (cursor on line 21, seq 15): snapshot baseline 1000.
            stepTo(e, IDX_LINE21);
            ObjectNode root = e.objectTree(100L);
            assertTrue(root.expandable());
            assertEquals("Bar#100", root.label());
            List<ObjectNode> children = e.children(root);
            assertEquals(1, children.size());
            assertEquals("balance = 1000", children.get(0).label());

            // After the field write (cursor on line 13, seq 18): override to 900.
            assertTrue(e.stepInto().moved());
            assertEquals(IDX_LINE13, e.currentIndex());
            List<ObjectNode> after = e.children(e.objectTree(100L));
            assertEquals(1, after.size());
            assertEquals("balance = 900", after.get(0).label());
        }
    }

    @Test
    void objectTreeNoInfoAvailable(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            ObjectNode root = e.objectTree(999L); // never first-seen, no snapshot/writes
            List<ObjectNode> children = e.children(root);
            assertEquals(1, children.size());
            assertEquals("<No info available>", children.get(0).label());
            assertFalse(children.get(0).expandable());
        }
    }

    @Test
    void watchByObjectId(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_LINE21);
            WatchResult w = e.resolveWatch("#100");
            assertNull(w.error());
            assertNotNull(w.root());
            List<ObjectNode> children = e.children(w.root());
            assertEquals("balance = 1000", children.get(0).label());

            // bare id (no #) resolves identically
            assertNull(e.resolveWatch("100").error());
        }
    }

    @Test
    void watchStaticField(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_LINE21); // seq 15 > the static write's seq 9
            WatchResult w = e.resolveWatch("Foo.COUNT");
            assertNull(w.error());
            assertNotNull(w.root());
            assertEquals("5", w.root().value().display());

            // binary name also resolves
            assertNull(e.resolveWatch("com.ex.Foo.COUNT").error());
        }
    }

    @Test
    void watchUnsupportedExpression(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            WatchResult w = e.resolveWatch("a + b");
            assertNull(w.root());
            assertEquals(Engine.UNSUPPORTED_WATCH, w.error());
        }
    }

    @Test
    void recordInfoAtCursor(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_LINE21);
            RecordInfo info = e.recordInfo();
            assertEquals(1, info.tid());
            assertEquals("worker-1", info.threadName());
            assertEquals("LINE", info.typeName());
            assertEquals(IDX_LINE21, info.index());
            assertEquals(20, info.threadRecordCount());
            assertEquals(20, info.globalRecordCount());
            assertEquals(2, info.depth());
            assertEquals(1000L, info.approxEpochMillis());
            assertFalse(info.truncatedRecording());
            assertTrue(info.siteText().contains("helper"), info.siteText());
            assertTrue(info.siteText().contains("Foo.java:21"), info.siteText());
        }
    }

    @Test
    void selectFrameBounds(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_LINE21);
            assertTrue(e.selectFrame(0));
            assertEquals(0, e.selectedFrame());
            assertFalse(e.selectFrame(2));
            assertFalse(e.selectFrame(-1));
            // a cursor move resets selection to innermost
            e.reverseStep();
            assertEquals(e.stack().size() - 1, e.selectedFrame());
        }
    }

    @Test
    void unmatchedBracketsAreTolerated(@TempDir final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int fileFoo = r.sym("FILE", "T.java");
        int classFoo = r.sym("CLASS", "T");
        int meth = r.sym("METHOD", "m");
        int siteM = r.methodSite(classFoo, fileFoo, 1, meth, "()V", true);
        int line = r.lineSite(classFoo, fileFoo, 2);

        r.threadStart(1, "t", 1L, 0L);                          // idx 0,1
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteM, 0, 0, 0); // idx 2: unmatched EXIT
        r.rec(1, Format.LINE, 0, 0, 0, line, 0, 0, 0);          // idx 3
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteM, 0, 0, 0); // idx 4: never closed
        r.rec(1, Format.LINE, 0, 0, 0, line, 0, 0, 0);          // idx 5
        Path built = r.write();

        try (Engine e = Engine.open(built)) {
            ThreadIndex ti = e.threadIndex(1);
            assertEquals(0, ti.depth[2]);       // unmatched EXIT → depth 0
            assertEquals(-1, ti.frameEnter[2]);
            assertEquals(1, ti.depth[5]);       // inside the never-closed frame
            assertEquals(4, ti.frameEnter[5]);
            assertEquals(ti.count - 1, ti.matchingExit[4]); // sentinel for unmatched ENTER
        }
    }

    @Test
    void seekJumpsToLineAndIsNoOpWhenAlreadyThere(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            // Round-trip: jump to a known LINE index, then back to the start.
            StepResult jump = e.seek(1, IDX_LINE21);
            assertTrue(jump.moved());
            assertEquals(IDX_LINE21, e.currentIndex());
            assertEquals(e.stack().size() - 1, e.selectedFrame()); // reset to innermost on a move

            // Selecting an outer frame then seeking again resets the selection.
            assertTrue(e.selectFrame(0));
            StepResult back = e.seek(1, IDX_LINE10);
            assertTrue(back.moved());
            assertEquals(IDX_LINE10, e.currentIndex());
            assertEquals(e.stack().size() - 1, e.selectedFrame());

            // Seeking to the current (tid,index) is a no-op with the "already here" message.
            StepResult same = e.seek(1, IDX_LINE10);
            assertFalse(same.moved());
            assertEquals(Engine.ALREADY_HERE, same.message());
        }
    }

    @Test
    void exitFrameLandsOnFrameReturnAndEntry(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            // Cursor on the helper frame's first line (l20); its lines are l20@12, l21@14.
            stepTo(e, IDX_LINE20);
            StepResult fwd = e.exitFrameForward();
            assertTrue(fwd.moved());
            assertEquals(IDX_LINE21, e.currentIndex()); // greatest LINE in the frame
            assertEquals(e.stack().size() - 1, e.selectedFrame());

            // Already at the frame return → no-op.
            StepResult atReturn = e.exitFrameForward();
            assertFalse(atReturn.moved());
            assertEquals(Engine.ALREADY_AT_FRAME_RETURN, atReturn.message());

            // Backward from the return lands on the frame entry (l20).
            StepResult bwd = e.exitFrameBackward();
            assertTrue(bwd.moved());
            assertEquals(IDX_LINE20, e.currentIndex()); // least LINE in the frame

            // Already at the frame entry → no-op.
            StepResult atEntry = e.exitFrameBackward();
            assertFalse(atEntry.moved());
            assertEquals(Engine.ALREADY_AT_FRAME_ENTRY, atEntry.message());

            // Outer (main) frame: lines are split around the helper call (l10@6..l11again@18).
            stepTo(e, IDX_LINE13); // back in main after helper returned
            assertTrue(e.exitFrameForward().moved());
            assertEquals(IDX_LINE11_AGAIN, e.currentIndex()); // greatest main-frame LINE
            assertTrue(e.exitFrameBackward().moved());
            assertEquals(IDX_LINE10, e.currentIndex()); // least main-frame LINE
        }
    }

    @Test
    void methodSitesDistinctWithCountsAndCallsAscendingCapped(@TempDir final Path dir)
            throws IOException {
        try (Engine e = Engine.open(buildCalls(dir))) {
            List<MethodSite> sites = e.methodSites();
            assertEquals(2, sites.size());
            // Sorted by classBinary then methodName: alpha before beta, both on com.ex.Foo.
            MethodSite alpha = sites.get(0);
            MethodSite beta = sites.get(1);
            assertEquals("com.ex.Foo", alpha.classBinary());
            assertEquals("alpha", alpha.methodName());
            assertEquals("()V", alpha.descriptor());
            assertEquals(3L, alpha.invocationCount()); // 2 on t1 + 1 on t2
            assertEquals("beta", beta.methodName());
            assertEquals(1L, beta.invocationCount());

            // callsOf: ascending by seq across all threads, capped at cap.
            List<Call> all = e.callsOf(alpha.siteId(), 10);
            assertEquals(3, all.size());
            assertEquals(5L, all.get(0).seq());
            assertEquals(1, all.get(0).tid());
            assertEquals(7L, all.get(1).seq());
            assertEquals(2, all.get(1).tid()); // interleaved: the t2 call sits between two t1 calls
            assertEquals(11L, all.get(2).seq());
            assertEquals(1, all.get(2).tid());

            List<Call> capped = e.callsOf(alpha.siteId(), 2);
            assertEquals(2, capped.size());
            assertEquals(5L, capped.get(0).seq());
            assertEquals(7L, capped.get(1).seq());

            // Cached: repeated calls return an equal, stable list.
            assertEquals(sites, e.methodSites());
            // Unknown site id / non-positive cap → empty.
            assertTrue(e.callsOf(999, 5).isEmpty());
            assertTrue(e.callsOf(alpha.siteId(), 0).isEmpty());
        }
    }

    /**
     * A LINE-free recording that enters {@code Foo.alpha} three times (twice on t1, once on t2)
     * and {@code Foo.beta} once, with the invocations interleaved across threads so {@code seq}
     * order differs from per-thread order — exercising distinct counts and cross-thread ordering.
     */
    private static Path buildCalls(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int file = r.sym("FILE", "Foo.java");
        int cls = r.sym("CLASS", "com.ex.Foo");
        int mAlpha = r.sym("METHOD", "alpha");
        int mBeta = r.sym("METHOD", "beta");
        int siteAlpha = r.methodSite(cls, file, 10, mAlpha, "()V", true);
        int siteBeta = r.methodSite(cls, file, 20, mBeta, "()V", true);

        r.threadStart(1, "t1", 1L, 0L);   // seq 1,2
        r.threadStart(2, "t2", 2L, 0L);   // seq 3,4
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteAlpha, 0, 0, 0); // seq 5,  t1 alpha #1
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteAlpha, 0, 0, 0); // seq 6
        r.rec(2, Format.METHOD_ENTER, 0, 0, 0, siteAlpha, 0, 0, 0); // seq 7,  t2 alpha
        r.rec(2, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteAlpha, 0, 0, 0); // seq 8
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteBeta, 0, 0, 0);  // seq 9,  t1 beta
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteBeta, 0, 0, 0);  // seq 10
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteAlpha, 0, 0, 0); // seq 11, t1 alpha #2
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteAlpha, 0, 0, 0); // seq 12
        return r.write();
    }

    // ---- helpers ----

    private static void stepTo(final Engine e, final int targetIndex) {
        int guard = 0;
        while (e.currentIndex() != targetIndex) {
            StepResult res = e.stepInto();
            if (!res.moved() || ++guard > 1000) {
                throw new IllegalStateException("could not step to " + targetIndex
                        + " (stuck at " + e.currentIndex() + ")");
            }
        }
    }

    private static void assertArrayEqualsLong(final long[] expected, final long[] actual) {
        assertEquals(expected.length, actual.length, "line-index length");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "line index " + i);
        }
    }
}
