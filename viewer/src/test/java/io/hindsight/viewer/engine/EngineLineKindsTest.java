// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.hindsight.reader.Format;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * White-box tests of the §18.3 line-kind accessors — {@link Engine#frameHasLines(int)} and
 * {@link Engine#threadHasLines(int)} — and the §18.4 {@code viewer-thread-kinds.tsv} cache,
 * against synthetic recordings assembled byte-by-byte from FORMAT.md: thread 1 mixes framed
 * lines with a line-less (enter/exit-only) frame, thread 2 is a pure callsOnly thread.
 */
final class EngineLineKindsTest {

    private static final String CACHE_NAME = "viewer-thread-kinds.tsv";

    // Thread-1 record indices in build() (thread prologue = idx 0,1).
    private static final int IDX_ENTER_MAIN = 2;
    private static final int IDX_LINE10 = 3;
    private static final int IDX_ENTER_HELPER = 4;
    private static final int IDX_ENTER_INNER = 5;
    private static final int IDX_LINE30 = 6;

    /**
     * Thread 1: {@code main} (has LINE 10) → {@code helper} (NO lines of its own) →
     * {@code inner} (has LINE 30) — the line inside {@code helper}'s span belongs to
     * {@code inner}'s frame, so {@code helper} must still count as line-less.
     * Thread 2: a callsOnly frame — ENTER/EXIT only, no LINE records at all.
     */
    private static Path build(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int file = r.sym("FILE", "Foo.java");
        int cls = r.sym("CLASS", "com.ex.Foo");
        int mMain = r.sym("METHOD", "main");
        int mHelper = r.sym("METHOD", "helper");
        int mInner = r.sym("METHOD", "inner");
        int mBg = r.sym("METHOD", "background");
        int siteMain = r.methodSite(cls, file, 10, mMain, "([Ljava/lang/String;)V", true);
        int siteHelper = r.methodSite(cls, file, 20, mHelper, "(I)I", false);
        int siteInner = r.methodSite(cls, file, 30, mInner, "()V", true);
        int siteBg = r.methodSite(cls, file, 40, mBg, "()V", true);
        int l10 = r.lineSite(cls, file, 10);
        int l30 = r.lineSite(cls, file, 30);

        r.threadStart(1, "worker-1", 42L, 1000L);                                 // idx 0,1
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteMain, 0, 0, 0);                // idx 2
        r.rec(1, Format.LINE, 0, 0, 0, l10, 0, 0, 0);                             // idx 3
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteHelper, 200L, 0, 0);           // idx 4
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteInner, 0, 0, 0);               // idx 5
        r.rec(1, Format.LINE, 0, 0, 0, l30, 0, 0, 0);                             // idx 6
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteInner, 0, 0, 0);  // idx 7
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_INT, 0, siteHelper, 0, 0, 9);  // idx 8
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteMain, 0, 0, 0);   // idx 9

        r.threadStart(2, "calls-only", 43L, 1000L);                               // idx 0,1
        r.rec(2, Format.METHOD_ENTER, 0, 0, 0, siteBg, 0, 0, 0);                  // idx 2
        r.rec(2, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteBg, 0, 0, 0);     // idx 3
        return r.write();
    }

    // ---- §18.3 accessors ----

    @Test
    void frameHasLinesDistinguishesOwnLinesFromCalleeLines(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            stepTo(e, IDX_LINE30);
            List<Frame> stack = e.stack();
            assertEquals(3, stack.size());
            assertEquals("main", stack.get(0).methodName());
            assertEquals("helper", stack.get(1).methodName());
            assertEquals("inner", stack.get(2).methodName());

            assertTrue(e.frameHasLines(0), "main executed LINE 10 itself");
            assertFalse(e.frameHasLines(1),
                    "helper's span contains only inner's LINE — frameEnter differs, so line-less");
            assertTrue(e.frameHasLines(2), "inner executed LINE 30 itself");

            // Out-of-range indices are false, never a throw.
            assertFalse(e.frameHasLines(-1));
            assertFalse(e.frameHasLines(3));
        }
    }

    @Test
    void frameHasLinesOnAnEnterCursorSeesTheFrameLinesAhead(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            // Initial cursor = main's ENTER: the span [enter, exit] contains LINE 10, so true —
            // the classification is per-frame, not per-position.
            assertEquals(IDX_ENTER_MAIN, e.currentIndex());
            assertTrue(e.frameHasLines(0));

            stepTo(e, IDX_ENTER_HELPER);
            assertEquals(2, e.stack().size());
            assertFalse(e.frameHasLines(1), "helper is line-less from its ENTER already");
        }
    }

    @Test
    void callsOnlyThreadFramesAreAllLineless(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            assertTrue(e.selectThread(2).moved());
            assertTrue(e.stepInto().moved()); // land on background's ENTER (idx 2)
            List<Frame> stack = e.stack();
            assertEquals(1, stack.size());
            assertTrue(stack.get(0).recorded());
            assertFalse(e.frameHasLines(0), "a callsOnly thread's frames are all line-less");
        }
    }

    @Test
    void unrecordedCaptureFramesAreNeverLineful(@TempDir final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir, 4);
        int file = r.sym("FILE", "Foo.java");
        int cls = r.sym("CLASS", "com.ex.Foo");
        int mMain = r.sym("METHOD", "main");
        int siteMain = r.methodSite(cls, file, 10, mMain, "([Ljava/lang/String;)V", true);
        int l10 = r.lineSite(cls, file, 10);
        int rtFoo = r.rtSym("RTCLASS", "com.ex.Foo");
        int rtMain = r.rtSym("METHODNAME", "main");
        int rtApp = r.rtSym("RTCLASS", "com.ex.App");
        int rtRun = r.rtSym("METHODNAME", "run");

        r.threadStart(1, "main", 1L, 0L);                                        // idx 0,1
        r.stackSnapshot(1, List.of(                                              // idx 2
                new SyntheticRecording.CapFrame(rtFoo, rtMain, 0, 10),           // = recorded main
                new SyntheticRecording.CapFrame(rtApp, rtRun, 0, 33)));          // unrecorded caller
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteMain, 0, 0, 0);               // idx 3
        r.rec(1, Format.LINE, 0, 0, 0, l10, 0, 0, 0);                            // idx 4
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, siteMain, 0, 0, 0);  // idx 5

        try (Engine e = Engine.open(r.write())) {
            stepTo(e, 4); // the LINE
            List<Frame> stack = e.stack();
            assertEquals(2, stack.size(), "1 unrecorded caller + 1 recorded frame");
            assertFalse(stack.get(0).recorded());
            assertFalse(e.frameHasLines(0), "an unrecorded (capture-only) frame is never lineful");
            assertTrue(e.frameHasLines(1));
        }
    }

    @Test
    void threadHasLinesClassifiesThreads(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            assertTrue(e.threadHasLines(1));
            assertFalse(e.threadHasLines(2), "enter/exit-only thread carries no LINE records");
            assertFalse(e.threadHasLines(99), "unknown tid is false, never a throw");
        }
    }

    // ---- §18.4 thread-kind cache file ----

    @Test
    void openWritesTheThreadKindCache(@TempDir final Path dir) throws IOException {
        Path built = build(dir);
        try (Engine e = Engine.open(built)) {
            assertEquals(List.of(1, 2), e.threads());
        }
        Path cache = built.resolve(CACHE_NAME);
        assertTrue(Files.exists(cache), "open must write " + CACHE_NAME);
        assertEquals("1\tLINES\n2\tCALLS\n", Files.readString(cache),
                "one tid<TAB>LINES|CALLS row per thread, sorted by tid");
    }

    @Test
    void secondOpenLeavesAConsistentCacheUntouched(@TempDir final Path dir) throws IOException {
        Path built = build(dir);
        try (Engine e = Engine.open(built)) {
            assertEquals(2, e.threads().size());
        }
        Path cache = built.resolve(CACHE_NAME);
        String content = Files.readString(cache);
        // Stamp a sentinel mtime: a skipped rewrite must leave it exactly as stamped.
        Files.setLastModifiedTime(cache, FileTime.fromMillis(0));
        try (Engine e = Engine.open(built)) {
            assertEquals(2, e.threads().size());
        }
        assertEquals(FileTime.fromMillis(0), Files.getLastModifiedTime(cache),
                "an existing consistent cache is not rewritten");
        assertEquals(content, Files.readString(cache));
    }

    @Test
    void inconsistentCacheIsRepairedOnOpen(@TempDir final Path dir) throws IOException {
        Path built = build(dir);
        Path cache = built.resolve(CACHE_NAME);
        Files.writeString(cache, "1\tCALLS\n"); // stale/corrupt content
        try (Engine e = Engine.open(built)) {
            assertEquals(2, e.threads().size());
        }
        assertEquals("1\tLINES\n2\tCALLS\n", Files.readString(cache),
                "a cache that disagrees with the indices is rewritten");
    }

    @Test
    void readOnlyRecordingDirectoryStillOpens(@TempDir final Path dir) throws IOException {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "read-only-directory case needs POSIX permissions; skipped on this filesystem");
        Path built = build(dir);
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(built);
        Files.setPosixFilePermissions(built, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            try (Engine e = Engine.open(built)) {
                // Best-effort §18.4: the failed cache write is swallowed and the engine works.
                assertTrue(e.threadHasLines(1));
                assertTrue(e.stepInto().moved());
            }
            assertFalse(Files.exists(built.resolve(CACHE_NAME)),
                    "no cache file can appear in a read-only recording directory");
        } finally {
            Files.setPosixFilePermissions(built, original); // let @TempDir clean up
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
