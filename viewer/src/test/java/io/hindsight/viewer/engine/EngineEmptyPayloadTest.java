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
 * Pins the §15.8 empty-payload contract at the engine level: a FIELDS snapshot with
 * {@code fieldCount = 0} (recorder ≥ v1.5 emits these for genuinely field-less classes,
 * FORMAT.md §3) must reconstruct as PRESENT with zero children — an empty expansion —
 * while {@code <No info available>} stays reserved for "no snapshot observed at/before
 * this seq". The engine's {@code reconstruct} already distinguishes a parsed-but-empty
 * snapshot from an absent one, so this test only pins that behaviour against a synthetic
 * recording built byte-by-byte from the spec.
 */
final class EngineEmptyPayloadTest {

    private static Path build(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int fileFoo = r.sym("FILE", "Foo.java");
        int classFoo = r.sym("CLASS", "com.ex.Foo");
        int methMain = r.sym("METHOD", "main");
        int rtEmpty = r.rtSym("RTCLASS", "com.ex.Empty");
        int siteMain = r.methodSite(classFoo, fileFoo, 10, methMain, "()V", true);
        int l10 = r.lineSite(classFoo, fileFoo, 10);
        int l11 = r.lineSite(classFoo, fileFoo, 11);
        int blobAfter = SyntheticRecording.flags(Format.KIND_NONE, Format.KIND_BLOB, 0);
        int voidAfter = SyntheticRecording.flags(Format.KIND_NONE, Format.KIND_NONE, 0);

        r.threadStart(1, "main", 1L, 1000L);                                  // idx 0,1
        r.firstSeen(1, 300L, "com.ex.Empty");                                 // idx 2
        long emptySnap = r.snapshotFields(1, rtEmpty, SyntheticRecording.fieldList());
        r.rec(1, Format.SNAPSHOT, blobAfter, 0, 0, 0, 300L, 0, emptySnap);    // idx 3
        r.firstSeen(1, 400L, "com.ex.Naked");                                 // idx 4 — never snapshotted
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, siteMain, 0, 0, 0);            // idx 5
        r.rec(1, Format.LINE, 0, 0, 0, l10, 0, 0, 0);                         // idx 6 (initial cursor)
        r.rec(1, Format.LINE, 0, 0, 0, l11, 0, 0, 0);                         // idx 7
        r.rec(1, Format.METHOD_EXIT, voidAfter, Format.TAG_VOID, 0, siteMain, 0, 0, 0); // idx 8
        return r.write();
    }

    @Test
    void emptyFieldsSnapshotIsPresentWithZeroChildren(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            ObjectNode root = e.objectTree(300L);
            List<ObjectNode> children = e.children(root);
            // §15.8: present-with-zero-children — the node expands to NOTHING, and in
            // particular not to a <No info available> leaf.
            assertTrue(children.isEmpty(),
                    "an empty FIELDS payload must reconstruct as an empty expansion, got " + children);
        }
    }

    @Test
    void noSnapshotAtOrBeforeSeqStillRendersNoInfoAvailable(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(build(dir))) {
            // §15.8: <No info available> now specifically means "no snapshot observed
            // at/before this seq" — pinned here for an object that was first-seen but
            // never snapshotted.
            List<ObjectNode> children = e.children(e.objectTree(400L));
            assertEquals(1, children.size());
            assertEquals("<No info available>", children.get(0).label());
            assertFalse(children.get(0).expandable());
        }
    }
}
