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
 * White-box tests of the §19.2 {@link Engine#toLineTrace(boolean)} jump — the {@code j}/{@code J}
 * navigation used in a green (callsOnly) context — against a synthetic recording assembled
 * byte-by-byte from FORMAT.md. Thread 1 has two {@code LINE} records inside {@code worker}
 * separated by a callsOnly gap ({@code leaf}'s ENTER/EXIT, no lines of its own); thread 2 is a
 * pure callsOnly thread with no {@code LINE} records at all.
 */
final class EngineLineTraceTest {

    // Thread-1 record indices in build() (thread prologue = idx 0,1).
    private static final int IDX_ENTER_MAIN = 2;
    private static final int IDX_ENTER_WORKER = 3;
    private static final int IDX_LINE20 = 4; // first LINE, stack [main, worker]
    private static final int IDX_ENTER_LEAF = 5; // callsOnly gap start
    private static final int IDX_EXIT_LEAF = 6; // callsOnly gap end
    private static final int IDX_LINE21 = 7; // second LINE, stack [main, worker]

    /**
     * Thread 1: {@code main} → {@code worker} which executes LINE 20, calls the line-less
     * {@code leaf} (ENTER/EXIT only — the callsOnly gap), then executes LINE 21. So
     * {@code lineIndices == [4, 7]} with a non-line gap between them, and both lines sit under a
     * two-frame stack (used to prove the selected-frame reset).
     * Thread 2: a callsOnly frame — ENTER/EXIT only, no LINE records at all.
     */
    private static Path build(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int file = r.sym("FILE", "Foo.java");
        int cls = r.sym("CLASS", "com.ex.Foo");
        int mMain = r.sym("METHOD", "main");
        int mWorker = r.sym("METHOD", "worker");
        int mLeaf = r.sym("METHOD", "leaf");
        int mBg = r.sym("METHOD", "background");
        int siteMain = r.methodSite(cls, file, 10, mMain, "([Ljava/lang/String;)V", true);
        int siteWorker = r.methodSite(cls, file, 20, mWorker, "()V", false);
        int siteLeaf = r.methodSite(cls, file, 30, mLeaf, "()V", false);
        int siteBg = r.methodSite(cls, file, 40, mBg, "()V", true);
        int l20 = r.lineSite(cls, file, 20);
        int l21 = r.lineSite(cls, file, 21);

        r.threadStart(1, "worker-1", 42L, 1000L);                                 // idx 0,1
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteMain, 0, 0, 0);                // idx 2
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteWorker, 100L, 0, 0);           // idx 3
        r.rec(1, Format.LINE, 0, 0, 0, l20, 0, 0, 0);                             // idx 4
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteLeaf, 0, 0, 0);               // idx 5
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteLeaf, 0, 0, 0);  // idx 6
        r.rec(1, Format.LINE, 0, 0, 0, l21, 0, 0, 0);                             // idx 7
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteWorker, 0, 0, 0); // idx 8
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteMain, 0, 0, 0);   // idx 9

        r.threadStart(2, "calls-only", 43L, 1000L);                               // idx 0,1
        r.rec(2, Format.METHOD_ENTER, 0, 0, 0, siteBg, 0, 0, 0);                  // idx 2
        r.rec(2, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteBg, 0, 0, 0);     // idx 3
        return r.write();
    }

    @Test
    void forwardHopsOverTheCallsOnlyGapToTheNextLine(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_LINE20);
            StepResult res = e.toLineTrace(true);
            assertTrue(res.moved(), "forward from LINE 20 must land on the next LINE");
            assertEquals(IDX_LINE21, e.currentIndex(), "skips the leaf ENTER/EXIT gap");
        }
    }

    @Test
    void backwardHopsOverTheCallsOnlyGapToThePreviousLine(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_LINE21);
            StepResult res = e.toLineTrace(false);
            assertTrue(res.moved());
            assertEquals(IDX_LINE20, e.currentIndex());
        }
    }

    @Test
    void fromWithinTheGapJumpsToTheNearestLineEitherWay(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_ENTER_LEAF); // cursor on a non-LINE record inside the gap
            assertTrue(e.toLineTrace(true).moved());
            assertEquals(IDX_LINE21, e.currentIndex(), "forward from the gap -> the next LINE");
        }
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_EXIT_LEAF);
            assertTrue(e.toLineTrace(false).moved());
            assertEquals(IDX_LINE20, e.currentIndex(), "reverse from the gap -> the previous LINE");
        }
    }

    @Test
    void noOpAtTheThreadsLastAndFirstLine(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_LINE21); // the last LINE in the thread
            StepResult fwd = e.toLineTrace(true);
            assertFalse(fwd.moved(), "no LINE after the last one");
            assertEquals("no further line-trace record in this thread", fwd.message());
            assertEquals(IDX_LINE21, e.currentIndex(), "the cursor did not move");

            stepTo(e, IDX_LINE20); // the first LINE in the thread
            StepResult back = e.toLineTrace(false);
            assertFalse(back.moved(), "no LINE before the first one");
            assertEquals("no further line-trace record in this thread", back.message());
            assertEquals(IDX_LINE20, e.currentIndex());
        }
    }

    @Test
    void aCallsOnlyThreadHasNoLineTraceEitherWay(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            assertTrue(e.selectThread(2).moved());
            assertTrue(e.stepInto().moved()); // background's ENTER (idx 2)
            assertFalse(e.threadHasLines(2), "precondition: thread 2 carries no LINE records");
            assertFalse(e.toLineTrace(true).moved(), "no LINE to jump forward to");
            assertFalse(e.toLineTrace(false).moved(), "no LINE to jump back to");
        }
    }

    @Test
    void aMoveResetsTheSelectedFrameToInnermost(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_LINE21);
            assertEquals(2, e.stack().size(), "stack is [main, worker] at LINE 21");
            assertTrue(e.selectFrame(0), "select the OUTER (non-innermost) frame main");
            assertEquals(0, e.selectedFrame());

            assertTrue(e.toLineTrace(false).moved());
            assertEquals(IDX_LINE20, e.currentIndex());
            assertEquals(2, e.stack().size(), "stack is still [main, worker] at LINE 20");
            assertEquals(e.stack().size() - 1, e.selectedFrame(),
                    "toLineTrace reuses moveTo, which resets the selected frame to innermost");
        }
    }

    @Test
    void enterCursorIsHandledByStrictlyForwardBackwardSearch(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            // Initial cursor = main's ENTER (idx 2), before any LINE: forward finds the first LINE,
            // reverse finds nothing (binary-search insertion point on a non-LINE index).
            assertEquals(IDX_ENTER_MAIN, e.currentIndex());
            assertFalse(e.toLineTrace(false).moved(), "no LINE before main's ENTER");
            assertTrue(e.toLineTrace(true).moved());
            assertEquals(IDX_LINE20, e.currentIndex(), "forward from the ENTER -> the first LINE");
        }
    }

    // ---- helpers ----

    private static void stepTo(final Engine e, final int targetIndex) {
        if (e.currentIndex() == targetIndex) {
            return;
        }
        // Reset to the thread start of the current thread by seeking forward/backward via stepping.
        int guard = 0;
        // Walk forward from wherever we are; if we overshot, the caller ordered indices ascending.
        while (e.currentIndex() != targetIndex) {
            StepResult res = e.currentIndex() < targetIndex ? e.stepInto() : e.reverseStep();
            if (!res.moved() || ++guard > 1000) {
                throw new IllegalStateException("could not step to " + targetIndex
                        + " (stuck at " + e.currentIndex() + ")");
            }
        }
    }
}
