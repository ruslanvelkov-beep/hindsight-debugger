// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.reader.Format;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * White-box tests of the §14.5 stack merge: schema-4 recordings assembled
 * byte-by-byte from FORMAT.md carry {@code STACK_SNAPSHOT} captures whose
 * unrecorded caller frames {@code stack()} must prepend (grey, outermost-first)
 * to the recorded frames — the innermost recorded frame staying last, unrecorded
 * frames selectable but locals-free — while schema ≤ 3 recordings must never
 * grow unrecorded frames.
 */
final class EngineStackTest {

    // Record indices in buildCaptureAtStart(), referenced by assertions.
    private static final int IDX_ENTER_MAIN = 3;
    private static final int IDX_LINE10 = 4;
    private static final int IDX_ENTER_HELPER = 5;
    private static final int IDX_LINE20 = 6;

    /**
     * Scenario (a): one capture at thread start whose innermost frame is the outermost
     * recorded method ({@code Foo.main}) with two callers above it ({@code App.run},
     * then {@code Boot.start} outermost, no source file).
     */
    private static Path buildCaptureAtStart(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir, 4);
        int file = r.sym("FILE", "Foo.java");
        int cls = r.sym("CLASS", "com.ex.Foo");
        int mMain = r.sym("METHOD", "main");
        int mHelper = r.sym("METHOD", "helper");
        int siteMain = r.methodSite(cls, file, 10, mMain, "([Ljava/lang/String;)V", true);
        int siteHelper = r.methodSite(cls, file, 20, mHelper, "(I)I", false);
        int l10 = r.lineSite(cls, file, 10);
        int l20 = r.lineSite(cls, file, 20);

        int rtFoo = r.rtSym("RTCLASS", "com.ex.Foo");
        int rtMain = r.rtSym("METHODNAME", "main");
        int rtApp = r.rtSym("RTCLASS", "com.ex.App");
        int rtRun = r.rtSym("METHODNAME", "run");
        int rtAppFile = r.rtSym("FILENAME", "App.java");
        int rtBoot = r.rtSym("RTCLASS", "com.ex.Boot");
        int rtStart = r.rtSym("METHODNAME", "start");

        r.threadStart(1, "main", 1L, 0L);                                   // idx 0,1
        r.stackSnapshot(1, List.of(                                          // idx 2, innermost-first
                new SyntheticRecording.CapFrame(rtFoo, rtMain, 0, 10),       // = R_out
                new SyntheticRecording.CapFrame(rtApp, rtRun, rtAppFile, 33),
                new SyntheticRecording.CapFrame(rtBoot, rtStart, 0, 7)));    // outermost, no file
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteMain, 0, 0, 0);           // idx 3
        r.rec(1, Format.LINE, 0, 0, 0, l10, 0, 0, 0);                        // idx 4
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteHelper, 200L, 0, 0);      // idx 5
        r.rec(1, Format.LINE, 0, 0, 0, l20, 0, 0, 0);                        // idx 6
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteHelper, 0, 0, 0); // idx 7
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteMain, 0, 0, 0);   // idx 8
        return r.write();
    }

    @Test
    void captureCallersBecomeUnrecordedFrames(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildCaptureAtStart(dir))) {
            assertEquals(4, e.schemaVersion());
            stepTo(e, IDX_LINE20);
            List<Frame> stack = e.stack();
            assertEquals(4, stack.size(), "2 unrecorded callers + 2 recorded frames");

            // Outermost-first: exactly the two capture callers, grey, above the recorded frames.
            Frame boot = stack.get(0);
            assertFalse(boot.recorded());
            assertEquals(0, boot.index());
            assertEquals(-1, boot.enterIndex());
            assertEquals(0, boot.methodSiteId());
            assertEquals("com.ex.Boot", boot.classBinary());
            assertEquals("start", boot.methodName());
            assertEquals("", boot.descriptor());
            assertNull(boot.fileName(), "capture fileSym 0 must surface as a null fileName");
            assertEquals(7, boot.currentLine());
            assertFalse(boot.isStatic());

            Frame app = stack.get(1);
            assertFalse(app.recorded());
            assertEquals(1, app.index());
            assertEquals("com.ex.App", app.classBinary());
            assertEquals("run", app.methodName());
            assertEquals("App.java", app.fileName());
            assertEquals(33, app.currentLine());

            // The recorded frames are unchanged (call-site line for the outer one) — and last.
            Frame main = stack.get(2);
            assertTrue(main.recorded());
            assertEquals(2, main.index());
            assertEquals(IDX_ENTER_MAIN, main.enterIndex());
            assertEquals("com.ex.Foo", main.classBinary());
            assertEquals("main", main.methodName());
            assertTrue(main.isStatic());
            assertEquals(10, main.currentLine());

            Frame helper = stack.get(3);
            assertTrue(helper.recorded());
            assertEquals(3, helper.index());
            assertEquals("helper", helper.methodName());
            assertEquals(20, helper.currentLine());
            assertEquals(stack.size() - 1, e.selectedFrame(), "innermost recorded frame stays last");

            // Unrecorded frames have no locals; the recorded helper frame keeps its `this`.
            assertTrue(e.locals(0).isEmpty());
            assertTrue(e.locals(1).isEmpty());
            List<Local> helperLocals = e.locals(3);
            assertEquals(1, helperLocals.size());
            assertEquals("this", helperLocals.get(0).name());
            assertInstanceOf(Value.Ref.class, helperLocals.get(0).value());

            // selectFrame accepts unrecorded frames and honours the widened bounds.
            assertTrue(e.selectFrame(0));
            assertEquals(0, e.selectedFrame());
            assertTrue(e.selectFrame(1));
            assertFalse(e.selectFrame(4));
            assertFalse(e.selectFrame(-1));

            // §17.5: unrecorded frames have no as-of record; recorded ones do — the outer
            // recorded frame's is its call-site LINE, the innermost's the cursor itself.
            assertFalse(e.frameAsOf(0).found());
            assertFalse(e.frameAsOf(1).found());
            assertEquals(new SeqLocation(true, 1, IDX_LINE10), e.frameAsOf(2));
            assertEquals(new SeqLocation(true, 1, IDX_LINE20), e.frameAsOf(3));
            assertFalse(e.frameAsOf(4).found());

            // A reverse step lands on helper's ENTER first (§17.1): the entered frame is
            // still on the stack, its line falling back to the method site's line.
            assertTrue(e.reverseStep().moved());
            assertEquals(IDX_ENTER_HELPER, e.currentIndex());
            List<Frame> onEnter = e.stack();
            assertEquals(4, onEnter.size(), "[Boot, App, main, helper] on helper's ENTER");
            assertEquals(3, e.selectedFrame());
            assertEquals(20, onEnter.get(3).currentLine(), "site line: no LINE executed yet");

            // Another reverse step pops back into main; the selection resets to innermost.
            assertTrue(e.reverseStep().moved());
            assertEquals(IDX_LINE10, e.currentIndex());
            List<Frame> shallower = e.stack();
            assertEquals(3, shallower.size(), "[Boot, App, main] once helper is gone");
            assertEquals(2, e.selectedFrame());
            assertFalse(shallower.get(0).recorded());
            assertFalse(shallower.get(1).recorded());
            assertTrue(shallower.get(2).recorded());
            assertEquals("main", shallower.get(2).methodName());
        }
    }

    /**
     * Scenario (b): two captures — one before the first line, one between the two lines —
     * with distinguishable callers; the merge must use the nearest capture at/before the
     * cursor in both stepping directions.
     */
    @Test
    void mergeUsesNearestPrecedingCapture(@TempDir final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir, 4);
        int file = r.sym("FILE", "Foo.java");
        int cls = r.sym("CLASS", "com.ex.Foo");
        int mMain = r.sym("METHOD", "main");
        int siteMain = r.methodSite(cls, file, 10, mMain, "()V", true);
        int l10 = r.lineSite(cls, file, 10);
        int l11 = r.lineSite(cls, file, 11);

        int rtFoo = r.rtSym("RTCLASS", "com.ex.Foo");
        int rtMain = r.rtSym("METHODNAME", "main");
        int rtApp = r.rtSym("RTCLASS", "com.ex.App");
        int rtRun = r.rtSym("METHODNAME", "run");
        int rtBoot = r.rtSym("RTCLASS", "com.ex.Boot");
        int rtStart = r.rtSym("METHODNAME", "start");

        r.threadStart(1, "main", 1L, 0L);                                    // idx 0,1
        r.stackSnapshot(1, List.of(                                           // idx 2: caller App.run
                new SyntheticRecording.CapFrame(rtFoo, rtMain, 0, 10),
                new SyntheticRecording.CapFrame(rtApp, rtRun, 0, 33)));
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteMain, 0, 0, 0);            // idx 3
        r.rec(1, Format.LINE, 0, 0, 0, l10, 0, 0, 0);                         // idx 4
        r.stackSnapshot(1, List.of(                                           // idx 5: caller Boot.start
                new SyntheticRecording.CapFrame(rtFoo, rtMain, 0, 11),
                new SyntheticRecording.CapFrame(rtBoot, rtStart, 0, 7)));
        r.rec(1, Format.LINE, 0, 0, 0, l11, 0, 0, 0);                         // idx 6
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteMain, 0, 0, 0); // idx 7

        try (Engine e = Engine.open(r.write())) {
            // Initial cursor = the first exec record (main's ENTER at idx 3, §17.1), already
            // after capture 1; one step reaches the first LINE, still before capture 2.
            assertEquals(3, e.currentIndex());
            assertTrue(e.stepInto().moved());
            assertEquals(4, e.currentIndex());
            List<Frame> before = e.stack();
            assertEquals(2, before.size());
            assertFalse(before.get(0).recorded());
            assertEquals("com.ex.App", before.get(0).classBinary());
            assertEquals("run", before.get(0).methodName());
            assertEquals(33, before.get(0).currentLine());
            assertTrue(before.get(1).recorded());
            assertEquals("main", before.get(1).methodName());

            // Past the second capture the nearest one wins.
            assertTrue(e.stepInto().moved());
            assertEquals(6, e.currentIndex());
            List<Frame> after = e.stack();
            assertEquals(2, after.size());
            assertFalse(after.get(0).recorded());
            assertEquals("com.ex.Boot", after.get(0).classBinary());
            assertEquals("start", after.get(0).methodName());
            assertEquals(7, after.get(0).currentLine());

            // Stepping back re-selects the earlier capture.
            assertTrue(e.reverseStep().moved());
            assertEquals("run", e.stack().get(0).methodName());
        }
    }

    /**
     * Scenario (c): a capture with no frame matching the outermost recorded frame by
     * {@code (classBinary, methodName)} — the whole capture is appended as unrecorded
     * context below (outermore than) the recorded frames.
     */
    @Test
    void noMatchAppendsWholeCaptureAsUnrecorded(@TempDir final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir, 4);
        int file = r.sym("FILE", "Foo.java");
        int cls = r.sym("CLASS", "com.ex.Foo");
        int mMain = r.sym("METHOD", "main");
        int siteMain = r.methodSite(cls, file, 10, mMain, "()V", true);
        int l10 = r.lineSite(cls, file, 10);

        int rtOther = r.rtSym("RTCLASS", "com.ex.Other");
        int rtMeth = r.rtSym("METHODNAME", "meth");
        int rtDeep = r.rtSym("RTCLASS", "com.ex.Deep");
        int rtRoot = r.rtSym("METHODNAME", "root");

        r.threadStart(1, "main", 1L, 0L);                                    // idx 0,1
        r.stackSnapshot(1, List.of(                                           // idx 2, nothing matches main
                new SyntheticRecording.CapFrame(rtOther, rtMeth, 0, 5),
                new SyntheticRecording.CapFrame(rtDeep, rtRoot, 0, 1)));
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteMain, 0, 0, 0);            // idx 3
        r.rec(1, Format.LINE, 0, 0, 0, l10, 0, 0, 0);                         // idx 4
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteMain, 0, 0, 0); // idx 5

        try (Engine e = Engine.open(r.write())) {
            // Initial cursor = main's ENTER (§17.1); step once onto the LINE.
            assertEquals(3, e.currentIndex());
            assertTrue(e.stepInto().moved());
            assertEquals(4, e.currentIndex());
            List<Frame> stack = e.stack();
            assertEquals(3, stack.size(), "whole 2-frame capture + 1 recorded frame");
            assertFalse(stack.get(0).recorded());
            assertEquals("com.ex.Deep", stack.get(0).classBinary()); // capture outermost first
            assertEquals("root", stack.get(0).methodName());
            assertFalse(stack.get(1).recorded());
            assertEquals("com.ex.Other", stack.get(1).classBinary());
            assertEquals("meth", stack.get(1).methodName());
            assertTrue(stack.get(2).recorded(), "innermost recorded frame stays last");
            assertEquals("main", stack.get(2).methodName());
            assertEquals(2, e.selectedFrame());
        }
    }

    /** Empty recorded stack (cursor outside any frame): ALL capture frames are unrecorded. */
    @Test
    void emptyRecordedStackMakesWholeCaptureUnrecorded(@TempDir final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir, 4);
        int file = r.sym("FILE", "Foo.java");
        int cls = r.sym("CLASS", "com.ex.Foo");
        int l5 = r.lineSite(cls, file, 5);

        int rtA = r.rtSym("RTCLASS", "com.ex.A");
        int rtM1 = r.rtSym("METHODNAME", "inner");
        int rtB = r.rtSym("RTCLASS", "com.ex.B");
        int rtM2 = r.rtSym("METHODNAME", "outer");

        r.threadStart(1, "main", 1L, 0L);                                    // idx 0,1
        r.stackSnapshot(1, List.of(                                           // idx 2
                new SyntheticRecording.CapFrame(rtA, rtM1, 0, 5),
                new SyntheticRecording.CapFrame(rtB, rtM2, 0, 9)));
        r.rec(1, Format.LINE, 0, 0, 0, l5, 0, 0, 0);                          // idx 3, outside any frame

        try (Engine e = Engine.open(r.write())) {
            assertEquals(3, e.currentIndex());
            List<Frame> stack = e.stack();
            assertEquals(2, stack.size());
            assertFalse(stack.get(0).recorded());
            assertEquals("com.ex.B", stack.get(0).classBinary()); // outermost first
            assertFalse(stack.get(1).recorded());
            assertEquals("com.ex.A", stack.get(1).classBinary());
            assertEquals(1, e.selectedFrame(), "reset lands on the innermost (unrecorded) frame");
            assertTrue(e.locals(0).isEmpty());
            assertTrue(e.locals(1).isEmpty());
            assertTrue(e.selectFrame(0));
            assertFalse(e.selectFrame(2));
        }
    }

    /** Schema 4, but no capture at/before the cursor yet: no unrecorded frames. */
    @Test
    void noCaptureAtOrBeforeCursorYieldsNoUnrecordedFrames(@TempDir final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir, 4);
        int file = r.sym("FILE", "Foo.java");
        int cls = r.sym("CLASS", "com.ex.Foo");
        int mMain = r.sym("METHOD", "main");
        int siteMain = r.methodSite(cls, file, 10, mMain, "()V", true);
        int l10 = r.lineSite(cls, file, 10);
        int l11 = r.lineSite(cls, file, 11);

        int rtFoo = r.rtSym("RTCLASS", "com.ex.Foo");
        int rtMain = r.rtSym("METHODNAME", "main");
        int rtApp = r.rtSym("RTCLASS", "com.ex.App");
        int rtRun = r.rtSym("METHODNAME", "run");

        r.threadStart(1, "main", 1L, 0L);                                    // idx 0,1
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteMain, 0, 0, 0);            // idx 2
        r.rec(1, Format.LINE, 0, 0, 0, l10, 0, 0, 0);                         // idx 3, before any capture
        r.stackSnapshot(1, List.of(                                           // idx 4
                new SyntheticRecording.CapFrame(rtFoo, rtMain, 0, 11),
                new SyntheticRecording.CapFrame(rtApp, rtRun, 0, 33)));
        r.rec(1, Format.LINE, 0, 0, 0, l11, 0, 0, 0);                         // idx 5
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteMain, 0, 0, 0); // idx 6

        try (Engine e = Engine.open(r.write())) {
            // Initial cursor = main's ENTER (idx 2, §17.1), still before the capture.
            assertEquals(2, e.currentIndex());
            List<Frame> atEnter = e.stack();
            assertEquals(1, atEnter.size(), "no capture at/before the cursor → recorded only");
            assertTrue(atEnter.get(0).recorded());

            assertTrue(e.stepInto().moved()); // idx 3: the first LINE, still before the capture
            assertEquals(3, e.currentIndex());
            List<Frame> before = e.stack();
            assertEquals(1, before.size(), "no capture at/before the cursor → recorded only");
            assertTrue(before.get(0).recorded());

            assertTrue(e.stepInto().moved()); // idx 5, now past the capture
            assertEquals(5, e.currentIndex());
            List<Frame> after = e.stack();
            assertEquals(2, after.size());
            assertFalse(after.get(0).recorded());
            assertEquals("run", after.get(0).methodName());
        }
    }

    /** Scenario (d): a schema-3 recording never grows unrecorded frames anywhere. */
    @Test
    void schema3RecordingHasNoUnrecordedFrames(@TempDir final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir); // default schema 3, no captures
        int file = r.sym("FILE", "Foo.java");
        int cls = r.sym("CLASS", "com.ex.Foo");
        int mMain = r.sym("METHOD", "main");
        int mHelper = r.sym("METHOD", "helper");
        int siteMain = r.methodSite(cls, file, 10, mMain, "()V", true);
        int siteHelper = r.methodSite(cls, file, 20, mHelper, "()V", false);
        int l10 = r.lineSite(cls, file, 10);
        int l20 = r.lineSite(cls, file, 20);
        int l11 = r.lineSite(cls, file, 11);

        r.threadStart(1, "main", 1L, 0L);                                    // idx 0,1
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteMain, 0, 0, 0);            // idx 2
        r.rec(1, Format.LINE, 0, 0, 0, l10, 0, 0, 0);                         // idx 3
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteHelper, 200L, 0, 0);       // idx 4
        r.rec(1, Format.LINE, 0, 0, 0, l20, 0, 0, 0);                         // idx 5
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteHelper, 0, 0, 0); // idx 6
        r.rec(1, Format.LINE, 0, 0, 0, l11, 0, 0, 0);                         // idx 7
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteMain, 0, 0, 0);   // idx 8

        try (Engine e = Engine.open(r.write())) {
            assertEquals(3, e.schemaVersion());
            int maxDepth = 0;
            while (true) {
                for (Frame f : e.stack()) {
                    assertTrue(f.recorded(), "schema-3 frames are always recorded");
                }
                assertEquals(e.stack().size() - 1, e.selectedFrame());
                maxDepth = Math.max(maxDepth, e.stack().size());
                if (!e.stepInto().moved()) {
                    break;
                }
            }
            assertEquals(2, maxDepth); // sanity: the walk really saw nested frames
        }
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
}
