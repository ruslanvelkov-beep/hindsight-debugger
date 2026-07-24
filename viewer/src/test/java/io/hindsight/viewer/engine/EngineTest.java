// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.reader.Format;
import io.hindsight.reader.Rec;
import io.hindsight.reader.Recording;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * White-box tests of the reconstruction engine (VIEWER.md §2, §17.1 exec-record
 * stepping) against a synthetic schema-3 recording assembled byte-by-byte from
 * FORMAT.md. The scenario is a static {@code Foo.main} that writes a static field
 * then calls the instance method {@code Foo.helper(int)} which writes a local and
 * a {@code Bar#100} field, exercising nested frames, stepping over the
 * LINE/METHOD_ENTER/METHOD_EXIT exec set, locals, object trees and watches.
 */
final class EngineTest {

    // Record indices in the built recording (see build()), referenced by assertions.
    private static final int IDX_ENTER_MAIN = 5;
    private static final int IDX_LINE10 = 6;
    private static final int IDX_LINE11 = 7;
    private static final int IDX_WRITE_COUNT = 8;
    private static final int IDX_LINE12 = 9;
    private static final int IDX_ENTER_HELPER = 10;
    private static final int IDX_LINE20 = 12;
    private static final int IDX_LINE21 = 14;
    private static final int IDX_WRITE_BALANCE = 15;
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
            // §17.1: the exec set = every LINE, METHOD_ENTER and METHOD_EXIT, ascending;
            // metadata (thread-start, time-sync, first-seen, snapshot) and value records
            // (ARG, LOCAL_WRITE, FIELD_WRITE) never appear in it.
            assertArrayEqualsLong(new long[] {5, 6, 7, 9, 10, 12, 14, 16, 17, 18, 19}, ti.execIndices);

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
    void initialCursorIsFirstExecRecord(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            assertEquals(1, e.currentTid());
            // §17.1: the initial cursor is the first EXEC record — here main's METHOD_ENTER,
            // which precedes the first LINE.
            assertEquals(IDX_ENTER_MAIN, e.currentIndex());
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
            assertTrue(outer.recorded());
            assertEquals(12, outer.currentLine()); // call-site line, not entry line

            Frame inner = stack.get(1);
            assertEquals(IDX_ENTER_HELPER, inner.enterIndex());
            assertEquals("helper", inner.methodName());
            assertFalse(inner.isStatic());
            assertTrue(inner.recorded());
            assertEquals(21, inner.currentLine()); // the cursor line
            assertEquals("Foo.java", inner.fileName());

            assertEquals(1, e.selectedFrame()); // innermost after a move
        }
    }

    /**
     * Pins the FULL §17.1 stepInto landing sequence over the Foo.main/helper scenario:
     * main's ENTER → main's LINEs → helper's ENTER → helper's LINEs → helper's EXIT →
     * caller LINEs → main's EXIT — every landing an exec record, never the interleaved
     * ARG/LOCAL_WRITE/FIELD_WRITE/metadata records. reverseStep replays it backwards.
     */
    @Test
    void stepIntoWalksTheFullExecLandingSequence(@TempDir final Path dir) throws IOException {
        Path built = build(dir);
        long[] landings = {
            IDX_ENTER_MAIN,   // initial cursor (first exec record)
            IDX_LINE10,
            IDX_LINE11,
            IDX_LINE12,       // the call-site line
            IDX_ENTER_HELPER, // stepInto descends: the callee's ENTER is a landing
            IDX_LINE20,
            IDX_LINE21,
            IDX_EXIT_HELPER,  // the callee's EXIT is a landing
            IDX_LINE13,
            IDX_LINE11_AGAIN,
            IDX_EXIT_MAIN,    // main's EXIT is the final landing
        };
        try (Engine e = Engine.open(built);
                Recording probe = Recording.open(built)) {
            assertEquals(landings[0], e.currentIndex());
            for (int i = 1; i < landings.length; i++) {
                assertTrue(e.stepInto().moved(), "stepInto landing " + i);
                assertEquals(landings[i], e.currentIndex(), "stepInto landing " + i);
                assertTrue(isExecType(probe.recordAt(1, e.currentIndex()).type()),
                        "landing " + i + " must be a LINE/ENTER/EXIT record");
            }
            StepResult end = e.stepInto();
            assertFalse(end.moved());
            assertEquals(Engine.NO_NEXT_STEP, end.message());

            // reverseStep replays the same landings backwards, ending on the first exec record.
            for (int i = landings.length - 2; i >= 0; i--) {
                assertTrue(e.reverseStep().moved(), "reverseStep landing " + i);
                assertEquals(landings[i], e.currentIndex(), "reverseStep landing " + i);
            }
            StepResult start = e.reverseStep();
            assertFalse(start.moved());
            assertEquals(Engine.NO_EARLIER_STEP, start.message());
        }
    }

    /**
     * §17.1 stepOver: the next exec record of the CURRENT frame or an ancestor — callee
     * bodies are skipped whole, a frame's own EXIT is a landing, and an ENTER never is.
     */
    @Test
    void stepOverSkipsCalleesAndStopsOnOwnExit(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            // From the call-site line, stepOver skips the whole helper invocation
            // (ENTER, body lines, EXIT) and lands on the caller's next line.
            stepTo(e, IDX_LINE12);
            assertTrue(e.stepOver().moved());
            assertEquals(IDX_LINE13, e.currentIndex());

            // From a cursor ON the callee's ENTER the current frame IS the callee, so
            // stepOver lands on its first line.
            stepTo(e, IDX_ENTER_HELPER);
            assertTrue(e.stepOver().moved());
            assertEquals(IDX_LINE20, e.currentIndex());

            // From the frame's last line, the frame's own EXIT is the step-over landing.
            stepTo(e, IDX_LINE21);
            assertTrue(e.stepOver().moved());
            assertEquals(IDX_EXIT_HELPER, e.currentIndex());

            // From the EXIT, the next landing is back in the caller.
            assertTrue(e.stepOver().moved());
            assertEquals(IDX_LINE13, e.currentIndex());

            // At the very end of the thread there is nothing left to land on.
            stepTo(e, IDX_EXIT_MAIN);
            StepResult end = e.stepOver();
            assertFalse(end.moved());
            assertEquals(Engine.NO_NEXT_STEP, end.message());
        }
    }

    /** §17.1 reverseStepOver mirrors stepOver: callees skipped whole, ENTERs never landings. */
    @Test
    void reverseStepOverMirrorsStepOver(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            // From the line after the call, reverseStepOver skips the whole helper
            // invocation and lands back on the call-site line.
            stepTo(e, IDX_LINE13);
            assertTrue(e.reverseStepOver().moved());
            assertEquals(IDX_LINE12, e.currentIndex());

            // From the callee's first line the frame's own ENTER is skipped (an ENTER is
            // never a step-over landing) and the landing is the caller's call-site line.
            stepTo(e, IDX_LINE20);
            assertTrue(e.reverseStepOver().moved());
            assertEquals(IDX_LINE12, e.currentIndex());

            // From a cursor ON the callee's EXIT the landing is the frame's last line.
            stepTo(e, IDX_EXIT_HELPER);
            assertTrue(e.reverseStepOver().moved());
            assertEquals(IDX_LINE21, e.currentIndex());

            // From a cursor ON the callee's ENTER the landing is the caller's line.
            stepTo(e, IDX_ENTER_HELPER);
            assertTrue(e.reverseStepOver().moved());
            assertEquals(IDX_LINE12, e.currentIndex());
        }
    }

    @Test
    void stepIntoAtLastExecRecordDoesNotMove(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_EXIT_MAIN); // last exec record (main's EXIT)
            StepResult res = e.stepInto();
            assertFalse(res.moved());
            assertEquals(Engine.NO_NEXT_STEP, res.message());
        }
    }

    @Test
    void reverseStepAtFirstExecRecordDoesNotMove(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            StepResult res = e.reverseStep(); // already on main's ENTER (the first exec record)
            assertFalse(res.moved());
            assertEquals(Engine.NO_EARLIER_STEP, res.message());
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
            // On the initial ENTER cursor there is no current line — a safe no-op (§17.1).
            StepResult onEnter = e.runToCurrentLine(true);
            assertFalse(onEnter.moved());

            stepTo(e, IDX_LINE10); // line 10 appears exactly once.
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

    /**
     * §17.4: a METHOD site carrying the §5 {@code #p<slot>=<name>} suffix names the ARG-seeded
     * slots; recordings without it keep the {@code argN} fallback (pinned above).
     */
    @Test
    void localsUseRecordedParameterNames(@TempDir final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int fileFoo = r.sym("FILE", "Foo.java");
        int classFoo = r.sym("CLASS", "com.ex.Foo");
        int methHelper = r.sym("METHOD", "helper");
        int siteHelper = r.methodSite(classFoo, fileFoo, 20, methHelper, "(I)I#p1=count", false);
        int l20 = r.lineSite(classFoo, fileFoo, 20);
        int afterInline = SyntheticRecording.flags(Format.KIND_NONE, Format.KIND_INLINE, 0);
        r.threadStart(1, "worker-1", 42L, 1000L);
        r.firstSeen(1, 200L, "com.ex.Foo");
        r.rec(1, Format.METHOD_ENTER, 0, 0, 1, siteHelper, 200L, 0, 0);
        r.rec(1, Format.ARG, afterInline, Format.TAG_INT, 0, siteHelper, 0, 0, 7);
        r.rec(1, Format.LINE, 0, 0, 0, l20, 0, 0, 0);
        try (Engine e = Engine.open(r.write())) {
            List<Local> locals = e.locals(0);
            assertEquals(2, locals.size());
            assertEquals("this", locals.get(0).name());
            Local arg = locals.get(1);
            assertEquals(1, arg.slot());
            assertEquals("count", arg.name(), "named from the site's #p map, not argN");
            assertEquals("7", arg.value().display());
            assertEquals("(I)I", e.stack().get(0).descriptor(), "suffix stripped from desc");
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

            // After the field write: the next exec landing is helper's EXIT (§17.1), whose
            // seq is past the write — the object tree reconstructs the override there.
            assertTrue(e.stepInto().moved());
            assertEquals(IDX_EXIT_HELPER, e.currentIndex());
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

    // ---- §17.1: cursor resting ON METHOD_ENTER / METHOD_EXIT ----

    @Test
    void cursorOnEnterShowsEnteredFrameWithSeededArgs(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_ENTER_HELPER);

            // The entered frame is on the stack (depth includes it) and is the selected frame.
            List<Frame> stack = e.stack();
            assertEquals(2, stack.size());
            assertEquals(1, e.selectedFrame());
            assertEquals(2, e.position().depth());

            // No LINE has executed yet: the frame's line falls back to the method site's line.
            Frame helper = stack.get(1);
            assertEquals("helper", helper.methodName());
            assertEquals(20, helper.currentLine());
            assertEquals(12, stack.get(0).currentLine()); // the caller still shows its call site

            // Locals are exactly the seeded args (this + arg0); the later x write is invisible.
            List<Local> locals = e.locals(1);
            assertEquals(2, locals.size());
            assertEquals("this", locals.get(0).name());
            assertEquals(200L, ((Value.Ref) locals.get(0).value()).objectId());
            assertEquals("arg0", locals.get(1).name());
            assertEquals("7", locals.get(1).value().display());

            // recordInfo/position reflect the ENTER record itself.
            RecordInfo info = e.recordInfo();
            assertEquals("METHOD_ENTER", info.typeName());
            assertEquals(2, info.depth());
            assertEquals(IDX_ENTER_HELPER, info.index());
            assertTrue(info.siteText().contains("helper"), info.siteText());
        }
    }

    @Test
    void cursorOnExitShowsFrameAboutToPopAsOfExit(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_EXIT_HELPER);

            // The exiting frame is still on the stack; its line is the frame's last LINE.
            List<Frame> stack = e.stack();
            assertEquals(2, stack.size());
            assertEquals(1, e.selectedFrame());
            Frame helper = stack.get(1);
            assertEquals("helper", helper.methodName());
            assertEquals(21, helper.currentLine());

            // Locals as of the exit: the seeded args plus the x written inside the frame.
            List<Local> locals = e.locals(1);
            assertEquals(3, locals.size());
            assertEquals("x", locals.get(2).name());
            assertEquals("42", locals.get(2).value().display());

            RecordInfo info = e.recordInfo();
            assertEquals("METHOD_EXIT", info.typeName());
            assertEquals(2, info.depth());

            // Watches resolve as-of the exit: the balance write just before it is visible.
            WatchResult w = e.resolveWatch("#100");
            assertNull(w.error());
            assertEquals("balance = 900", e.children(w.root()).get(0).label());
        }
    }

    // ---- §17.1: callsOnly-scope thread (ENTER/EXIT only, zero LINE records) ----

    /**
     * A callsOnly-style thread: {@code main} enters {@code helper(int)} and both frames
     * close — no LINE records anywhere, so the §17.1 ENTER/EXIT stops are the only landings.
     */
    private static Path buildCallsOnly(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int file = r.sym("FILE", "Foo.java");
        int cls = r.sym("CLASS", "com.ex.Foo");
        int mMain = r.sym("METHOD", "main");
        int mHelper = r.sym("METHOD", "helper");
        int siteMain = r.methodSite(cls, file, 10, mMain, "()V", true);
        int siteHelper = r.methodSite(cls, file, 20, mHelper, "(I)I", false);
        int afterInline = SyntheticRecording.flags(Format.KIND_NONE, Format.KIND_INLINE, 0);

        r.threadStart(1, "main", 1L, 0L);                                        // idx 0,1
        r.firstSeen(1, 200L, "com.ex.Foo");                                       // idx 2
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteMain, 0, 0, 0);                // idx 3
        r.rec(1, Format.METHOD_ENTER, 0, 0, 1, siteHelper, 200L, 0, 0);           // idx 4
        r.rec(1, Format.ARG, afterInline, Format.TAG_INT, 0, siteHelper, 0, 0, 7); // idx 5
        r.rec(1, Format.METHOD_EXIT, afterInline, Format.TAG_INT, 0, siteHelper, 0, 0, 99); // idx 6
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteMain, 0, 0, 0);   // idx 7
        return r.write();
    }

    @Test
    void callsOnlyThreadStepsThroughEnterExitPairs(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildCallsOnly(dir))) {
            // Initial cursor = the first exec record (main's ENTER); the frame is on the stack
            // and shows the method site's line — no LINE ever executes in this thread.
            assertEquals(3, e.currentIndex());
            assertEquals(1, e.stack().size());
            assertEquals(10, e.stack().get(0).currentLine());

            // stepInto walks ENTER main → ENTER helper → EXIT helper → EXIT main, then stops.
            assertTrue(e.stepInto().moved());
            assertEquals(4, e.currentIndex());
            assertEquals(2, e.stack().size());
            List<Local> seeded = e.locals(1); // on helper's ENTER: exactly the seeded args
            assertEquals(2, seeded.size());
            assertEquals("this", seeded.get(0).name());
            assertEquals("arg0", seeded.get(1).name());
            assertEquals("7", seeded.get(1).value().display());

            assertTrue(e.stepInto().moved());
            assertEquals(6, e.currentIndex()); // helper's EXIT: the frame is about to pop
            assertEquals(2, e.stack().size());
            assertTrue(e.stepInto().moved());
            assertEquals(7, e.currentIndex()); // main's EXIT
            assertEquals(1, e.stack().size());
            assertFalse(e.stepInto().moved());

            // Reverse mirrors the same landings.
            assertTrue(e.reverseStep().moved());
            assertEquals(6, e.currentIndex());
            assertTrue(e.reverseStep().moved());
            assertEquals(4, e.currentIndex());
            assertTrue(e.reverseStep().moved());
            assertEquals(3, e.currentIndex());
            assertFalse(e.reverseStep().moved());

            // stepOver skips the callee pair whole: from main's ENTER straight to main's EXIT.
            assertTrue(e.stepOver().moved());
            assertEquals(7, e.currentIndex());
            // reverseStepOver from main's EXIT has nowhere to land: main has no LINEs and
            // its own ENTER is never a step-over landing.
            StepResult back = e.reverseStepOver();
            assertFalse(back.moved());
            assertEquals(Engine.NO_EARLIER_STEP, back.message());

            // From helper's ENTER, stepOver lands on helper's own EXIT.
            stepTo(e, 4);
            assertTrue(e.stepOver().moved());
            assertEquals(6, e.currentIndex());

            // The LINE-oriented commands are safe no-ops without LINEs (§17.1).
            assertFalse(e.runToCurrentLine(true).moved());
            assertFalse(e.runToCurrentLine(false).moved());
            StepResult exitF = e.exitFrameForward();
            assertFalse(exitF.moved());
            assertEquals(Engine.ALREADY_AT_FRAME_RETURN, exitF.message());
            StepResult exitB = e.exitFrameBackward();
            assertFalse(exitB.moved());
            assertEquals(Engine.ALREADY_AT_FRAME_ENTRY, exitB.message());

            // §17.5 with no LINEs: the innermost as-of record is the cursor (helper's EXIT),
            // the outer frame's is its ENTER — nothing else was ever recorded for it.
            assertEquals(new SeqLocation(true, 1, 6), e.frameAsOf(1));
            assertEquals(new SeqLocation(true, 1, 3), e.frameAsOf(0));
        }
    }

    /**
     * §17.1 sibling frames: a thread re-entered from uninstrumented code holds thousands of
     * top-level frames back-to-back. Step-over must FLOW across the boundary — from a frame's
     * EXIT the landing is the next sibling's ENTER (same depth), never a dead-end — and the
     * reverse walk lands on the previous sibling's EXIT.
     */
    @Test
    void stepOverFlowsAcrossSiblingTopLevelFrames(@TempDir final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int file = r.sym("FILE", "Foo.java");
        int cls = r.sym("CLASS", "com.ex.Foo");
        int mF = r.sym("METHOD", "f");
        int mG = r.sym("METHOD", "g");
        int siteF = r.methodSite(cls, file, 10, mF, "()V", true);
        int siteG = r.methodSite(cls, file, 20, mG, "()V", true);
        r.threadStart(1, "worker", 1L, 0L);                            // idx 0,1
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteF, 0, 0, 0);        // idx 2
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteF, 0, 0, 0); // idx 3
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteG, 0, 0, 0);        // idx 4
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteG, 0, 0, 0); // idx 5
        try (Engine e = Engine.open(r.write())) {
            // From f's EXIT, step-over lands on the SIBLING g's ENTER (the pre-fix dead-end).
            stepTo(e, 3);
            assertTrue(e.stepOver().moved());
            assertEquals(4, e.currentIndex());

            // And onward to g's EXIT; the very end is still a no-move.
            assertTrue(e.stepOver().moved());
            assertEquals(5, e.currentIndex());
            assertFalse(e.stepOver().moved());

            // Reverse from g's ENTER: own ENTER excluded, landing = sibling f's EXIT.
            stepTo(e, 4);
            assertTrue(e.reverseStepOver().moved());
            assertEquals(3, e.currentIndex());
        }
    }

    // ---- §17.5: frameAsOf ----

    @Test
    void frameAsOfReturnsEachFramesReconstructionRecord(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_LINE21);
            // Innermost frame: the cursor itself; outer frame: its call-site LINE.
            assertEquals(new SeqLocation(true, 1, IDX_LINE21), e.frameAsOf(1));
            assertEquals(new SeqLocation(true, 1, IDX_LINE12), e.frameAsOf(0));
            // Out-of-range indices are not found.
            assertEquals(new SeqLocation(false, 0, 0L), e.frameAsOf(-1));
            assertEquals(new SeqLocation(false, 0, 0L), e.frameAsOf(2));

            // On an ENTER cursor the innermost frame's as-of record is the ENTER itself.
            stepTo(e, IDX_ENTER_HELPER);
            assertEquals(new SeqLocation(true, 1, IDX_ENTER_HELPER), e.frameAsOf(1));
            assertEquals(new SeqLocation(true, 1, IDX_LINE12), e.frameAsOf(0));

            // On an EXIT cursor the innermost frame's as-of record is the EXIT itself.
            stepTo(e, IDX_EXIT_HELPER);
            assertEquals(new SeqLocation(true, 1, IDX_EXIT_HELPER), e.frameAsOf(1));
        }
    }

    // ---- §17.3: explorable map keys and values ----

    /**
     * A recording with three snapshotted containers: {@code HashMap#500} holding
     * {@code Bar#100 -> 7} and {@code "k2" -> #500} (2 of 3 entries captured),
     * {@code ArrayList#600} holding {@code [42, Bar#100]}, and a FIELDS snapshot for
     * {@code Bar#100} so the map's Ref key is explorable.
     */
    private static Path buildContainers(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int file = r.sym("FILE", "Foo.java");
        int cls = r.sym("CLASS", "com.ex.Foo");
        int mMain = r.sym("METHOD", "main");
        int siteMain = r.methodSite(cls, file, 10, mMain, "()V", true);
        int l10 = r.lineSite(cls, file, 10);

        int rtMap = r.rtSym("RTCLASS", "java.util.HashMap");
        int rtBar = r.rtSym("RTCLASS", "com.ex.Bar");
        int rtList = r.rtSym("RTCLASS", "java.util.ArrayList");
        int rtBalance = r.rtSym("FIELDNAME", "balance");
        int blobAfter = SyntheticRecording.flags(Format.KIND_NONE, Format.KIND_BLOB, 0);

        r.threadStart(1, "main", 1L, 0L);                                  // idx 0,1
        r.firstSeen(1, 100L, "com.ex.Bar");                                 // idx 2
        r.firstSeen(1, 500L, "java.util.HashMap");                          // idx 3
        r.firstSeen(1, 600L, "java.util.ArrayList");                        // idx 4

        long barSnap = r.snapshotFields(1, rtBar, SyntheticRecording.fieldList(
                new SyntheticRecording.Field(rtBalance, Format.TAG_INT, Format.KIND_INLINE, 1000L)));
        r.rec(1, Format.SNAPSHOT, blobAfter, 0, 0, 0, 100L, 0, barSnap);    // idx 5

        long k2 = r.blobUtf8(1, "k2");
        long mapSnap = r.snapshotContainer(1, rtMap, Format.FORM_MAP, 3, 2, List.of(
                new SyntheticRecording.Entry(Format.TAG_REF, Format.KIND_OBJECT_ID, 100L),
                new SyntheticRecording.Entry(Format.TAG_INT, Format.KIND_INLINE, 7L),
                new SyntheticRecording.Entry(Format.TAG_STRING, Format.KIND_BLOB, k2),
                new SyntheticRecording.Entry(Format.TAG_REF, Format.KIND_OBJECT_ID, 500L)));
        r.rec(1, Format.SNAPSHOT, blobAfter, 0, 0, 0, 500L, 0, mapSnap);    // idx 6

        long listSnap = r.snapshotContainer(1, rtList, Format.FORM_LIST, 2, 2, List.of(
                new SyntheticRecording.Entry(Format.TAG_INT, Format.KIND_INLINE, 42L),
                new SyntheticRecording.Entry(Format.TAG_REF, Format.KIND_OBJECT_ID, 100L)));
        r.rec(1, Format.SNAPSHOT, blobAfter, 0, 0, 0, 600L, 0, listSnap);   // idx 7

        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteMain, 0, 0, 0);          // idx 8
        r.rec(1, Format.LINE, 0, 0, 0, l10, 0, 0, 0);                       // idx 9
        return r.write();
    }

    @Test
    void mapEntriesExpandToExplorableKeyAndValue(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildContainers(dir))) {
            List<ObjectNode> entries = e.children(e.objectTree(500L));
            assertEquals(3, entries.size(), "2 captured entries + the …+1 more sentinel");

            ObjectNode first = entries.get(0);
            assertEquals("Bar#100 -> 7", first.label());
            assertTrue(first.expandable(), "a map entry is always expandable (§17.3)");
            assertTrue(first.isMapEntry());

            // Exactly two children, each carrying its own Value.
            List<ObjectNode> kv = e.children(first);
            assertEquals(2, kv.size());
            ObjectNode key = kv.get(0);
            assertEquals("key = Bar#100", key.label());
            assertInstanceOf(Value.Ref.class, key.value());
            assertTrue(key.expandable(), "a Ref key is independently explorable");
            ObjectNode val = kv.get(1);
            assertEquals("value = 7", val.label());
            assertInstanceOf(Value.Primitive.class, val.value());
            assertFalse(val.expandable());

            // The Ref key explores like any other object node.
            List<ObjectNode> barFields = e.children(key);
            assertEquals(1, barFields.size());
            assertEquals("balance = 1000", barFields.get(0).label());

            // The …+N more sentinel stays a plain leaf.
            ObjectNode more = entries.get(2);
            assertEquals("…+1 more", more.label());
            assertFalse(more.expandable());
            assertFalse(more.isMapEntry());
        }
    }

    @Test
    void mapEntryChildrenHonourTheAncestorCycleGuard(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildContainers(dir))) {
            List<ObjectNode> entries = e.children(e.objectTree(500L));
            ObjectNode second = entries.get(1);
            assertEquals("\"k2\" -> HashMap#500", second.label());
            assertTrue(second.isMapEntry());

            // Expanded as the TUI would, with the map itself on the ancestor path: the
            // self-referential value renders as a cycle leaf instead of recursing.
            List<ObjectNode> kv = e.children(second, Set.of(500L));
            assertEquals(2, kv.size());
            assertEquals("key = \"k2\"", kv.get(0).label());
            assertFalse(kv.get(0).expandable());
            assertEquals("value = ↺ #500 (cycle)", kv.get(1).label());
            assertFalse(kv.get(1).expandable());
        }
    }

    @Test
    void listChildrenAreUnchangedByMapEntryExpansion(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildContainers(dir))) {
            List<ObjectNode> elems = e.children(e.objectTree(600L));
            assertEquals(2, elems.size());
            assertEquals("[0] = 42", elems.get(0).label());
            assertFalse(elems.get(0).expandable());
            assertFalse(elems.get(0).isMapEntry());
            assertEquals("[1] = Bar#100", elems.get(1).label());
            assertTrue(elems.get(1).expandable(), "a Ref element expands exactly as before");
            assertFalse(elems.get(1).isMapEntry(), "list elements are not map entries (§17.3)");
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

    // ---- §13.6: classSites / instancesOf / firstMentionOf / fieldWrites ----

    @Test
    void classSitesDistinctCountsSorted(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildInstances(dir))) {
            List<ClassSite> sites = e.classSites();
            assertEquals(2, sites.size()); // distinct runtime classes, not one entry per instance
            assertEquals(new ClassSite("com.ex.Bar", 1L), sites.get(0)); // sorted by classBinary
            assertEquals(new ClassSite("com.ex.Foo", 3L), sites.get(1));
            assertEquals(sites, e.classSites()); // cached: repeated calls return a stable list
        }
    }

    @Test
    void instancesOfAscendingSeqAndCapped(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildInstances(dir))) {
            List<Instance> foos = e.instancesOf("com.ex.Foo", 10);
            assertEquals(
                    List.of(
                            new Instance(10L, 5L, 1, 2),
                            new Instance(30L, 7L, 1, 3),
                            new Instance(40L, 8L, 2, 3)),
                    foos,
                    "ascending by seq across threads");

            assertEquals(foos.subList(0, 2), e.instancesOf("com.ex.Foo", 2)); // capped
            assertTrue(e.instancesOf("com.ex.Nope", 10).isEmpty()); // unknown class
            assertTrue(e.instancesOf("com.ex.Foo", 0).isEmpty()); // cap <= 0
        }
    }

    @Test
    void firstMentionOfRoundTripsToFirstSeenAndNullForUnknown(@TempDir final Path dir) throws IOException {
        Path built = buildInstances(dir);
        try (Engine e = Engine.open(built);
                Recording probe = Recording.open(built)) {
            Instance c = e.firstMentionOf(30L);
            assertEquals(new Instance(30L, 7L, 1, 3), c);

            // Round-trip: the position really is that object's OBJECT_FIRST_SEEN record.
            Rec first = probe.recordAt(c.tid(), c.index());
            assertEquals(Format.OBJECT_FIRST_SEEN, first.type());
            assertEquals(30L, first.target());
            assertEquals(c.seq(), first.seq());

            assertNull(e.firstMentionOf(999L)); // unknown id
        }
    }

    @Test
    void fieldWritesRenderTheSyntheticScenarioWrites(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            // Static write Foo.COUNT: 0 -> 5 (idx 8, seq 9); owner com.ex.Foo → Foo.java.
            List<FieldWriteHit> count = e.fieldWrites("Foo.java", "COUNT", 10);
            assertEquals(1, count.size());
            FieldWriteHit c = count.get(0);
            assertEquals(9L, c.seq());
            assertEquals(1, c.tid());
            assertEquals(IDX_WRITE_COUNT, c.index());
            assertEquals(0L, c.ownerId()); // statics carry no owner object
            assertEquals("com.ex.Foo", c.ownerClass());
            assertEquals("COUNT", c.fieldName());
            assertTrue(c.isStatic());
            assertEquals("0", c.before());
            assertEquals("5", c.after());
            assertEquals("Foo.java", c.fileName());
            assertEquals(11, c.line());

            // Instance write Bar#100.balance: 1000 -> 900 (idx 15, seq 16); the owner class
            // com.ex.Bar belongs to Bar.java even though the write SITE's file is Foo.java.
            List<FieldWriteHit> bal = e.fieldWrites("Bar.java", "balance", 10);
            assertEquals(1, bal.size());
            FieldWriteHit b = bal.get(0);
            assertEquals(16L, b.seq());
            assertEquals(1, b.tid());
            assertEquals(IDX_WRITE_BALANCE, b.index());
            assertEquals(100L, b.ownerId());
            assertEquals("com.ex.Bar", b.ownerClass());
            assertEquals("balance", b.fieldName());
            assertFalse(b.isStatic());
            assertEquals("1000", b.before());
            assertEquals("900", b.after());
            assertEquals("Foo.java", b.fileName()); // fileName/line come from the write site
            assertEquals(21, b.line());

            // Owner-file rule: balance's owner does NOT belong to the queried Foo.java.
            assertTrue(e.fieldWrites("Foo.java", "balance", 10).isEmpty());
            assertTrue(e.fieldWrites("Foo.java", "nope", 10).isEmpty()); // unknown field
            assertTrue(e.fieldWrites("Foo.java", "COUNT", 0).isEmpty()); // cap <= 0

            // countFieldWrites applies the same owner-file rule, cap-free (§13.5 "of N").
            assertEquals(1L, e.countFieldWrites("Foo.java", "COUNT"));
            assertEquals(1L, e.countFieldWrites("Bar.java", "balance"));
            assertEquals(0L, e.countFieldWrites("Foo.java", "balance"));
            assertEquals(0L, e.countFieldWrites("Foo.java", "nope"));
            assertEquals(0L, e.countFieldWrites(null, "COUNT"));
        }
    }

    @Test
    void fieldWritesInnerClassOwnerMatchesEnclosingFile(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildInstances(dir))) {
            // Both Foo$Inner.y writes match Foo.java; the same-named Zed.y write is excluded.
            List<FieldWriteHit> ys = e.fieldWrites("Foo.java", "y", 10);
            assertEquals(2, ys.size());
            assertEquals(9L, ys.get(0).seq());
            assertEquals(1, ys.get(0).tid());
            assertEquals("com.ex.Foo$Inner", ys.get(0).ownerClass());
            assertEquals(30L, ys.get(0).ownerId());
            assertEquals(11L, ys.get(1).seq()); // ascending by seq across threads
            assertEquals(2, ys.get(1).tid());

            // The excluded foreign-owner write is reachable under its own file.
            List<FieldWriteHit> zed = e.fieldWrites("Zed.java", "y", 10);
            assertEquals(1, zed.size());
            assertEquals(10L, zed.get(0).seq());
            assertNull(zed.get(0).fileName()); // its site carries no file symbol

            // The cap stops the walk.
            assertEquals(ys.subList(0, 1), e.fieldWrites("Foo.java", "y", 1));

            // A reference-typed String write renders via the §2.3 runtime tags + blob path.
            List<FieldWriteHit> names = e.fieldWrites("Foo.java", "name", 10);
            assertEquals(1, names.size());
            assertEquals("null", names.get(0).before());
            assertEquals("\"hi\"", names.get(0).after());
            assertEquals(10L, names.get(0).ownerId());
            assertFalse(names.get(0).isStatic());
        }
    }

    /**
     * A recording with three {@code com.ex.Foo} instances (two on t1, one on t2, interleaved)
     * and one {@code com.ex.Bar} instance, plus field writes exercising the §13.5 owner-file
     * rule: two inner-class writes (owner {@code com.ex.Foo$Inner} → {@code Foo.java}), one
     * foreign-owner write with the same field name (owner {@code com.ex.Zed}), and a
     * reference-typed String write (owner {@code com.ex.Foo}) exercising the §2.3 per-slot
     * runtime tags and blob-string rendering.
     */
    private static Path buildInstances(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int file = r.sym("FILE", "Foo.java");
        int clsInner = r.sym("CLASS", "com.ex.Foo$Inner");
        int clsZed = r.sym("CLASS", "com.ex.Zed");
        int clsFoo = r.sym("CLASS", "com.ex.Foo");
        int fieldY = r.sym("FIELD", "y");
        int fieldName = r.sym("FIELD", "name");
        int siteInnerY = r.fieldSite(clsInner, file, 33, fieldY, "I", false);
        int siteZedY = r.fieldSite(clsZed, 0, 7, fieldY, "I", false);
        int siteFooName = r.fieldSite(clsFoo, file, 12, fieldName, "Ljava/lang/String;", false);

        int inline = SyntheticRecording.flags(Format.KIND_INLINE, Format.KIND_INLINE, 0);

        r.threadStart(1, "t1", 1L, 0L); // seq 1,2  t1 idx 0,1
        r.threadStart(2, "t2", 2L, 0L); // seq 3,4  t2 idx 0,1
        r.firstSeen(1, 10L, "com.ex.Foo"); // seq 5,  t1 idx 2
        r.firstSeen(2, 20L, "com.ex.Bar"); // seq 6,  t2 idx 2
        r.firstSeen(1, 30L, "com.ex.Foo"); // seq 7,  t1 idx 3
        r.firstSeen(2, 40L, "com.ex.Foo"); // seq 8,  t2 idx 3
        r.rec(1, Format.FIELD_WRITE, inline, Format.TAG_INT, 0, siteInnerY, 30L, 1, 2); // seq 9,  t1 idx 4
        r.rec(2, Format.FIELD_WRITE, inline, Format.TAG_INT, 0, siteZedY, 99L, 5, 6); //   seq 10, t2 idx 4
        r.rec(2, Format.FIELD_WRITE, inline, Format.TAG_INT, 0, siteInnerY, 30L, 2, 3); // seq 11, t2 idx 5
        long hi = r.blobUtf8(1, "hi");
        // Ref-typed field write, null -> "hi": before runtime tag NULL in the aux idx byte,
        // after runtime tag STRING in the tag byte (FORMAT.md §2.3).
        r.rec(1, Format.FIELD_WRITE, SyntheticRecording.flags(Format.KIND_NONE, Format.KIND_BLOB, 0),
                Format.TAG_STRING, Format.TAG_NULL, siteFooName, 10L, 0, hi); //           seq 12, t1 idx 5
        return r.write();
    }

    // ---- §16.7: owner-filtered field writes ----

    @Test
    void fieldWritesOwnerFilterAndDelegation(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildOwnerWrites(dir))) {
            // ownerId 0 — the existing (unfiltered) behaviour: every balance write ascending by
            // seq (two on #100, one on #200, one static), and the 3-arg overload delegates to it.
            List<FieldWriteHit> all = e.fieldWrites("Acct.java", "balance", 0L, 10);
            assertEquals(List.of(5L, 6L, 7L, 8L), all.stream().map(FieldWriteHit::seq).toList());
            assertEquals(all, e.fieldWrites("Acct.java", "balance", 10),
                    "the 3-arg overload must delegate to ownerId 0 exactly");

            // ownerId #100 — only that object's two writes, ascending seq; the static (target 0)
            // is excluded when ownerId != 0.
            List<FieldWriteHit> a = e.fieldWrites("Acct.java", "balance", 100L, 10);
            assertEquals(List.of(5L, 8L), a.stream().map(FieldWriteHit::seq).toList());
            for (FieldWriteHit h : a) {
                assertEquals(100L, h.ownerId());
                assertFalse(h.isStatic(), "the static write must be excluded from a #100 query");
            }
            assertEquals("0", a.get(0).before());
            assertEquals("10", a.get(0).after());
            assertEquals("10", a.get(1).before());
            assertEquals("20", a.get(1).after());

            // ownerId #200 — its single write.
            List<FieldWriteHit> b = e.fieldWrites("Acct.java", "balance", 200L, 10);
            assertEquals(1, b.size());
            assertEquals(6L, b.get(0).seq());
            assertEquals(200L, b.get(0).ownerId());

            // A never-written owner id yields nothing.
            assertTrue(e.fieldWrites("Acct.java", "balance", 999L, 10).isEmpty());

            // The cap still bounds the owner-filtered walk.
            assertEquals(a.subList(0, 1), e.fieldWrites("Acct.java", "balance", 100L, 1));

            // countFieldWrites mirrors the same filtering (cap-free), and its 2-arg overload
            // delegates to ownerId 0.
            assertEquals(4L, e.countFieldWrites("Acct.java", "balance", 0L));
            assertEquals(4L, e.countFieldWrites("Acct.java", "balance"));
            assertEquals(2L, e.countFieldWrites("Acct.java", "balance", 100L));
            assertEquals(1L, e.countFieldWrites("Acct.java", "balance", 200L));
            assertEquals(0L, e.countFieldWrites("Acct.java", "balance", 999L));
        }
    }

    // ---- §16.7: frameThisId ----

    @Test
    void frameThisIdMatchesLocalsThisAndZeroForStaticOrOutOfRange(@TempDir final Path dir)
            throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_LINE21); // stack: [0] main (static), [1] helper (instance, this=#200)

            // Instance frame → its this id, identical to the slot-0 `this` Ref locals() surfaces.
            long innerThis = e.frameThisId(1);
            assertEquals(200L, innerThis);
            Local self = e.locals(1).get(0);
            assertEquals("this", self.name());
            assertEquals(innerThis, ((Value.Ref) self.value()).objectId(),
                    "frameThisId must match the slot-0 this id from locals()");

            // Static frame → 0.
            assertTrue(e.stack().get(0).isStatic());
            assertEquals(0L, e.frameThisId(0));

            // Out-of-range frame indices → 0.
            assertEquals(0L, e.frameThisId(e.stack().size()));
            assertEquals(0L, e.frameThisId(-1));
        }
    }

    @Test
    void frameThisIdRecoversConstructorThis(@TempDir final Path dir) throws IOException {
        // A constructor's ENTER carries target 0; the live id is on the matching EXIT.
        // frameThisId must recover it via the SAME derivation locals() uses (§2.4/§16.7).
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
            int inner = e.stack().size() - 1;
            assertEquals(777L, e.frameThisId(inner), "constructor this must be recovered from the EXIT");
            // And it agrees with locals()'s recovered `this`.
            assertEquals(777L, ((Value.Ref) e.locals(inner).get(0).value()).objectId());
        }
    }

    // ---- §16.7: findSeq ----

    @Test
    void findSeqLocatesRecordsAcrossThreadsAndReportsMisses(@TempDir final Path dir)
            throws IOException {
        Path built = buildCalls(dir); // t1 seqs {1,2,5,6,9,10,11,12}, t2 seqs {3,4,7,8}
        try (Engine e = Engine.open(built);
                Recording probe = Recording.open(built)) {
            // Exact hits inside a thread.
            assertFound(e, probe, 5L, 1, 2);
            assertFound(e, probe, 11L, 1, 6);

            // First and last record of thread t1.
            assertFound(e, probe, 1L, 1, 0);
            assertFound(e, probe, 12L, 1, 7);

            // Cross-thread: seq 7 lives in t2 (and is a gap WITHIN t1), so the t1 binary search
            // must miss and the scan continue into t2 and find it there.
            assertFound(e, probe, 7L, 2, 2);
            // First and last record of thread t2.
            assertFound(e, probe, 3L, 2, 0);
            assertFound(e, probe, 8L, 2, 3);

            // Below the minimum and above the maximum seq → not found (the recording's global
            // seq is a contiguous AtomicLong, so there is no interior gap to miss).
            assertNotFound(e, 0L);
            assertNotFound(e, 13L);
            assertNotFound(e, Long.MAX_VALUE);

            // findSeq never moves the cursor, on a hit or a miss.
            int tid = e.currentTid();
            long idx = e.currentIndex();
            e.findSeq(7L);
            e.findSeq(999L);
            assertEquals(tid, e.currentTid());
            assertEquals(idx, e.currentIndex());
        }
    }

    /**
     * A recording where the field {@code balance} is written on two distinct owner ids
     * ({@code #100} twice, {@code #200} once, interleaved across threads so global seq order
     * differs from per-thread order) plus one static write (target 0) — exercising the §16.7
     * owner filter and its exclusion of statics.
     */
    private static Path buildOwnerWrites(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int file = r.sym("FILE", "Acct.java");
        int clsAcct = r.sym("CLASS", "com.ex.Acct");
        int fieldBalance = r.sym("FIELD", "balance");
        int siteInstance = r.fieldSite(clsAcct, file, 21, fieldBalance, "I", false);
        int siteStatic = r.fieldSite(clsAcct, file, 5, fieldBalance, "I", true);

        int inline = SyntheticRecording.flags(Format.KIND_INLINE, Format.KIND_INLINE, 0);
        int staticInline =
                SyntheticRecording.flags(Format.KIND_INLINE, Format.KIND_INLINE, Format.FLAG_STATIC);

        r.threadStart(1, "t1", 1L, 0L); // seq 1,2  t1 idx 0,1
        r.threadStart(2, "t2", 2L, 0L); // seq 3,4  t2 idx 0,1
        r.rec(1, Format.FIELD_WRITE, inline, Format.TAG_INT, 0, siteInstance, 100L, 0, 10);   // seq 5  #100
        r.rec(2, Format.FIELD_WRITE, inline, Format.TAG_INT, 0, siteInstance, 200L, 0, 99);   // seq 6  #200
        r.rec(1, Format.FIELD_WRITE, staticInline, Format.TAG_INT, 0, siteStatic, 0L, 0, 7);  // seq 7  static
        r.rec(2, Format.FIELD_WRITE, inline, Format.TAG_INT, 0, siteInstance, 100L, 10, 20);  // seq 8  #100
        return r.write();
    }

    private static void assertFound(final Engine e, final Recording probe, final long seq,
            final int expectTid, final long expectIndex) throws IOException {
        SeqLocation loc = e.findSeq(seq);
        assertTrue(loc.found(), "seq " + seq + " should be found");
        assertEquals(expectTid, loc.tid(), "tid for seq " + seq);
        assertEquals(expectIndex, loc.index(), "index for seq " + seq);
        assertEquals(seq, probe.recordAt(loc.tid(), loc.index()).seq(),
                "the record at the returned location must carry that seq");
    }

    private static void assertNotFound(final Engine e, final long seq) {
        SeqLocation loc = e.findSeq(seq);
        assertFalse(loc.found(), "seq " + seq + " should not be found");
        assertEquals(0, loc.tid());
        assertEquals(0L, loc.index());
    }

    // ---- helpers ----

    /** Single-steps (in either direction) until the cursor sits on {@code targetIndex}. */
    private static void stepTo(final Engine e, final int targetIndex) {
        int guard = 0;
        while (e.currentIndex() != targetIndex) {
            StepResult res = e.currentIndex() < targetIndex ? e.stepInto() : e.reverseStep();
            if (!res.moved() || ++guard > 1000) {
                throw new IllegalStateException("could not step to " + targetIndex
                        + " (stuck at " + e.currentIndex() + ")");
            }
        }
    }

    private static boolean isExecType(final int type) {
        return type == Format.LINE || type == Format.METHOD_ENTER || type == Format.METHOD_EXIT;
    }

    private static void assertArrayEqualsLong(final long[] expected, final long[] actual) {
        assertEquals(expected.length, actual.length, "index-array length");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "index " + i);
        }
    }
}
