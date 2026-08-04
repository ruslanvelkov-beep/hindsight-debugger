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
 * §28.9 branch-mode reconstruction against a synthetic recording: a method recorded WITHOUT
 * {@code LINE} records but WITH {@code BRANCH} records (whose sites carry line numbers) must
 * step onto its branches, show their lines, and count as line-bearing — while a classic
 * line-mode method in the SAME thread, where every BRANCH immediately follows its LINE, keeps
 * stepping over LINE records only (no double stops).
 */
final class EngineBranchModeTest {

    // Thread-1 record layout in build() (thread prologue = idx 0,1).
    private static final int IDX_ENTER_BMODE = 2; // branch-mode method enter
    private static final int IDX_BRANCH_21 = 3; // BRANCH at line 21
    private static final int IDX_LOCAL_22 = 4; // LOCAL_WRITE (line 22) — a value record, no landing
    private static final int IDX_BRANCH_23 = 5; // BRANCH at line 23
    private static final int IDX_EXIT_BMODE = 6;
    private static final int IDX_ENTER_LMODE = 7; // classic line-mode method
    private static final int IDX_LINE_40 = 8; // LINE at 40 …
    private static final int IDX_BRANCH_40 = 9; // … immediately followed by its BRANCH (same line)
    private static final int IDX_LINE_41 = 10;
    private static final int IDX_EXIT_LMODE = 11;

    /** One thread, two methods: {@code bmode} (branch mode) then {@code lmode} (line mode). */
    private static Path build(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int file = r.sym("FILE", "Foo.java");
        int cls = r.sym("CLASS", "com.ex.Foo");
        int mB = r.sym("METHOD", "bmode");
        int mL = r.sym("METHOD", "lmode");
        int vX = r.sym("VAR", "x");
        int siteB = r.methodSite(cls, file, 20, mB, "()V", true);
        int siteL = r.methodSite(cls, file, 39, mL, "()V", true);
        int b21 = r.branchSite(cls, file, 21, 0x9A); // IFNE
        int b23 = r.branchSite(cls, file, 23, 0x9F); // IF_ICMPEQ
        int loc22 = r.localSite(cls, file, 22, vX, "I", 1);
        int l40 = r.lineSite(cls, file, 40);
        int b40 = r.branchSite(cls, file, 40, 0x9A);
        int l41 = r.lineSite(cls, file, 41);
        int afterInline = SyntheticRecording.flags(Format.KIND_NONE, Format.KIND_INLINE, 0);

        r.threadStart(1, "main", 1L, 0L);                                        // idx 0,1
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteB, 0, 0, 0);                  // idx 2
        r.rec(1, Format.BRANCH, afterInline, Format.TAG_INT, 0, b21, 0, 0, 1);   // idx 3
        r.rec(1, Format.LOCAL_WRITE, afterInline, Format.TAG_INT, 1, loc22, 0, 0, 7); // idx 4
        r.rec(1, Format.BRANCH, afterInline, Format.TAG_INT, 0, b23, 0, 0, 0);   // idx 5
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteB, 0, 0, 0);     // idx 6
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteL, 0, 0, 0);                  // idx 7
        r.rec(1, Format.LINE, 0, 0, 0, l40, 0, 0, 0);                            // idx 8
        r.rec(1, Format.BRANCH, afterInline, Format.TAG_INT, 0, b40, 0, 0, 1);   // idx 9
        r.rec(1, Format.LINE, 0, 0, 0, l41, 0, 0, 0);                            // idx 10
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteL, 0, 0, 0);     // idx 11
        return r.write();
    }

    @Test
    void branchModeFrameStepsOntoItsBranchesAndShowsTheirLines(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            assertEquals(IDX_ENTER_BMODE, e.currentIndex(), "opens on the first exec record");
            assertTrue(e.stepInto().moved());
            assertEquals(IDX_BRANCH_21, e.currentIndex(), "F7 lands on the branch, not the local write");
            assertEquals(21, e.stack().get(e.stack().size() - 1).currentLine(),
                    "the branch site's line is the current line");
            assertTrue(e.stepInto().moved());
            assertEquals(IDX_BRANCH_23, e.currentIndex(), "the LOCAL_WRITE is never a landing");
            assertEquals(23, e.stack().get(e.stack().size() - 1).currentLine());
            assertTrue(e.reverseStep().moved());
            assertEquals(IDX_BRANCH_21, e.currentIndex(), "reverse is symmetric");
        }
    }

    @Test
    void lineModeFrameInTheSameThreadNeverStopsOnItsBranches(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            assertTrue(e.seek(1, IDX_ENTER_LMODE).moved());
            assertTrue(e.stepInto().moved());
            assertEquals(IDX_LINE_40, e.currentIndex());
            assertTrue(e.stepInto().moved());
            assertEquals(IDX_LINE_41, e.currentIndex(),
                    "the same-line BRANCH after LINE 40 is NOT a landing in a line-mode frame");
        }
    }

    @Test
    void lineInfoPredicatesAndLineTraceCoverBranchModeFrames(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            e.seek(1, IDX_BRANCH_21);
            int innermost = e.stack().size() - 1;
            assertFalse(e.frameHasLines(innermost), "no LINE records in the branch-mode frame");
            assertTrue(e.frameHasLineInfo(innermost), "…but it HAS line info via its branches");
            assertTrue(e.threadHasLineInfo(1));
            // j/J line-trace jumps hop across the branch-mode frame's branches too.
            assertTrue(e.toLineTrace(true).moved());
            assertEquals(IDX_BRANCH_23, e.currentIndex());
        }
    }

    @Test
    void peekExecSeesTheNeighbourWithoutMoving(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            e.seek(1, IDX_BRANCH_23);
            Engine.ExecPeek fwd = e.peekExec(true);
            assertTrue(fwd.found());
            assertEquals(Format.METHOD_EXIT, fwd.type());
            assertEquals(IDX_EXIT_BMODE, fwd.index());
            Engine.ExecPeek back = e.peekExec(false);
            assertTrue(back.found());
            assertEquals(Format.BRANCH, back.type());
            assertEquals(IDX_BRANCH_21, back.index());
            assertEquals(IDX_BRANCH_23, e.currentIndex(), "peeking never moves the cursor");
            // …and after the bmode EXIT the next exec is lmode's ENTER — the §28.10 two-step
            // F7 peeks exactly this to arm the call-site stop.
            e.seek(1, IDX_EXIT_BMODE);
            Engine.ExecPeek enter = e.peekExec(true);
            assertTrue(enter.found());
            assertEquals(Format.METHOD_ENTER, enter.type());
            assertEquals(IDX_ENTER_LMODE, enter.index());
        }
    }

    @Test
    void execLandingMembershipIsPerFrame(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            assertTrue(e.isExecLanding(1, IDX_BRANCH_21), "branch-mode BRANCH is a landing");
            assertFalse(e.isExecLanding(1, IDX_BRANCH_40), "line-mode BRANCH is NOT");
            assertFalse(e.isExecLanding(1, IDX_LOCAL_22), "value records never are");
            assertTrue(e.isExecLanding(1, IDX_LINE_40));
            assertTrue(e.isExecLanding(1, IDX_ENTER_BMODE));
            assertFalse(e.isExecLanding(99, 0), "unknown thread is never a landing");
        }
    }

    @Test
    void localsStillReconstructInBranchMode(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            e.seek(1, IDX_BRANCH_23); // after the x=7 write
            List<Local> locals = e.locals(e.stack().size() - 1);
            assertEquals(1, locals.size());
            assertEquals("x", locals.get(0).name());
            assertTrue(locals.get(0).value().display().startsWith("7"),
                    "local transitions stay fully visible in branch mode");
        }
    }
}
