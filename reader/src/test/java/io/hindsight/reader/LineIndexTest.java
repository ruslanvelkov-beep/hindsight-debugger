// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The FORMAT.md §12.1 visited-line index against a synthetic recording: which record
 * types mark lines, the package-path file keys, run encoding, persistence + staleness
 * (corrupt or count-mismatched {@code lines.tsv} is rebuilt), and the {@code lines}
 * CLI subcommand.
 */
final class LineIndexTest {

    @TempDir
    Path tmp;

    /** Two classes in one file merge into one key; a default-package class keys by bare name. */
    private Path writeRecording() throws IOException {
        SyntheticRecording r = new SyntheticRecording(tmp.resolve("rec"));
        int clsBar = r.sym("CLASS", "com.foo.Bar");
        int clsBaz = r.sym("CLASS", "com.foo.Baz"); // same source file as Bar (nested/aux class)
        int clsTop = r.sym("CLASS", "Top"); // default package
        int fileBar = r.sym("FILE", "Bar.java");
        int fileTop = r.sym("FILE", "Top.java");
        int mSym = r.sym("METHOD", "m");
        int fSym = r.sym("FIELD", "f");
        int vSym = r.sym("VAR", "v");

        int enter = r.methodSite(clsBar, fileBar, 10, mSym, "()V", false);
        int line12 = r.lineSite(clsBar, fileBar, 12);
        int line13 = r.lineSite(clsBaz, fileBar, 13); // Baz shares Bar.java → same key
        int field14 = r.fieldSite(clsBar, fileBar, 14, fSym, "I", false);
        int local15 = r.localSite(clsBar, fileBar, 15, vSym, "I", 1);
        int topEnter = r.methodSite(clsTop, fileTop, 3, mSym, "()V", true);
        int noFile = r.lineSite(clsBar, 0, 99); // fileSym 0 → skipped
        int noLine = r.methodSite(clsBar, fileBar, 0, mSym, "()I", false); // line 0 → skipped

        r.threadStart(1, "main", 1, 1000);
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, enter, 7, 0, 0);
        r.rec(1, Format.LINE, 0, 0, 0, line12, 0, 0, 0);
        r.rec(1, Format.LINE, 0, 0, 0, line12, 0, 0, 0); // revisit — still one line
        r.rec(1, Format.LINE, 0, 0, 0, line13, 0, 0, 0);
        r.rec(1, Format.FIELD_WRITE, SyntheticRecording.flags(1, 1, 0), Format.TAG_INT, 0, field14, 7, 0, 1);
        r.rec(1, Format.LOCAL_WRITE, SyntheticRecording.flags(0, 1, 0), Format.TAG_INT, 1, local15, 0, 0, 2);
        r.firstSeen(1, 7, "com.foo.Bar"); // symbol-id location → must be skipped
        r.rec(1, Format.LINE, 0, 0, 0, noFile, 0, 0, 0);
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, noLine, 7, 0, 0);
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, noLine, 7, 0, 0);
        r.rec(1, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, enter, 7, 0, 0);
        r.rec(2, Format.THREAD_START, 0, 0, 0, 0, 0, 0, 2);
        r.rec(2, Format.METHOD_ENTER, 0, 0, 0, topEnter, 0, 0, 0);
        r.rec(2, Format.METHOD_EXIT, 0, Format.TAG_VOID, 0, topEnter, 0, 0, 0);
        return r.write();
    }

    @Test
    void buildCollectsSiteLinesUnderPackageRelativeKeys() throws IOException {
        try (Recording rec = Recording.open(writeRecording())) {
            LineIndex idx = LineIndex.build(rec);
            assertEquals(Set.of("com/foo/Bar.java", "Top.java"), idx.files());
            BitSet bar = idx.linesOf("com/foo/Bar.java");
            for (int line : new int[] {10, 12, 13, 14, 15}) {
                assertTrue(bar.get(line), "line " + line);
            }
            assertFalse(bar.get(99), "fileSym 0 must not mark a line");
            assertFalse(bar.get(0));
            assertTrue(idx.linesOf("Top.java").get(3), "default-package method decl line");
            assertTrue(idx.covers("com/foo/Bar.java"));
            assertFalse(idx.covers("com/foo/Missing.java"));
            assertEquals(0, idx.linesOf("com/foo/Missing.java").cardinality());
            assertEquals(6, idx.lineCount());
        }
    }

    @Test
    void openOrBuildPersistsThenLoadsAndRebuildsWhenStaleOrCorrupt() throws IOException {
        Path dir = writeRecording();
        try (Recording rec = Recording.open(dir)) {
            assertFalse(LineIndex.present(rec));
            LineIndex built = LineIndex.openOrBuild(rec);
            Path file = dir.resolve(LineIndex.FILE_NAME);
            assertTrue(Files.isRegularFile(file));
            assertTrue(LineIndex.present(rec));
            // Round-trip: a second open loads the persisted file and matches the built index.
            LineIndex loaded = LineIndex.openOrBuild(rec);
            assertEquals(built.files(), loaded.files());
            assertEquals(built.linesOf("com/foo/Bar.java"), loaded.linesOf("com/foo/Bar.java"));
            // Corrupt body → rebuilt (not an error), and the file is repaired on disk.
            Files.writeString(file, Files.readAllLines(file).get(0) + "\nBad.java\tnot-a-run\n",
                    StandardCharsets.UTF_8);
            assertFalse(LineIndex.present(rec));
            assertEquals(built.files(), LineIndex.openOrBuild(rec).files());
            assertTrue(LineIndex.present(rec));
            // Header record-count mismatch (stale) → rebuilt.
            Files.writeString(file, "# hindsight lines v1 records=1\nX.java\t1\n", StandardCharsets.UTF_8);
            assertEquals(built.files(), LineIndex.openOrBuild(rec).files());
        }
    }

    @Test
    void runEncodingRoundTripsAndRejectsMalformedText() {
        BitSet set = new BitSet();
        set.set(14);
        set.set(16, 20); // 16-19
        set.set(42);
        String runs = LineIndex.formatRuns(set);
        assertEquals("14,16-19,42", runs);
        assertEquals(set, LineIndex.parseRuns(runs));
        assertEquals(new BitSet(), LineIndex.parseRuns(""));
        assertNull(LineIndex.parseRuns("1,x"));
        assertNull(LineIndex.parseRuns("9-3"));
        // Lines are u2 in the class file — corrupt huge values must force a rebuild, not an
        // enormous BitSet allocation.
        assertNull(LineIndex.parseRuns("2000000000"));
        assertNull(LineIndex.parseRuns("1-2000000000"));
        assertEquals("7", LineIndex.formatRuns(LineIndex.parseRuns("7")));
    }

    @Test
    void fileKeyJoinsPackagePathAndFileName() {
        assertEquals("com/foo/Bar.java", LineIndex.fileKey("com.foo.Bar", "Bar.java"));
        assertEquals("com/foo/Bar.java", LineIndex.fileKey("com.foo.Bar$Inner", "Bar.java"));
        assertEquals("Top.java", LineIndex.fileKey("Top", "Top.java"));
        // No class → no package → no key (a bare-name key could false-match a default-package file).
        assertNull(LineIndex.fileKey(null, "Top.java"));
        assertNull(LineIndex.fileKey("com.foo.Bar", null));
        assertNull(LineIndex.fileKey("com.foo.Bar", ""));
    }

    @Test
    void openOrBuildOnUnwritableDirReturnsTheIndexWithoutPersisting() throws IOException {
        Path dir = writeRecording();
        assertTrue(dir.toFile().setWritable(false), "test needs a POSIX-writable-flag filesystem");
        try (Recording rec = Recording.open(dir)) {
            // §12.1: persisting is best-effort — the in-memory index must come back complete.
            LineIndex idx = LineIndex.openOrBuild(rec);
            assertEquals(Set.of("com/foo/Bar.java", "Top.java"), idx.files());
            assertFalse(Files.exists(dir.resolve(LineIndex.FILE_NAME)));
            // And no temp file leaked into the recording dir either.
            try (var listing = Files.list(dir)) {
                assertTrue(listing.noneMatch(p -> p.getFileName().toString().startsWith(LineIndex.FILE_NAME)));
            }
        } finally {
            assertTrue(dir.toFile().setWritable(true)); // let @TempDir clean up
        }
    }

    @Test
    void linesSubcommandBuildsReportsAndExitsZero() throws IOException {
        Path dir = writeRecording();
        TestCli.Result first = TestCli.run("lines", "--dir", dir.toString());
        assertEquals(0, first.code(), first.err());
        assertTrue(first.outContains("# built "), first.out());
        assertTrue(first.outContains("com/foo/Bar.java\t5"), first.out());
        assertTrue(first.outContains("Top.java\t1"), first.out());
        assertTrue(first.outContains("# files: 2  visited lines: 6"), first.out());
        TestCli.Result second = TestCli.run("lines", "--dir", dir.toString());
        assertEquals(0, second.code(), second.err());
        assertTrue(second.outContains("# read "), second.out());
    }
}
