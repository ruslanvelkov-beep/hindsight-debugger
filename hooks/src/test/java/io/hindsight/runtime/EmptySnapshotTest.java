// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FORMAT.md §3 empty FIELDS payloads (recorder ≥ v1.5): a class that declares no instance
 * fields anywhere in its hierarchy — and is neither hidden nor synthetic — dumps an EMPTY
 * FIELDS payload ({@code fieldCount = 0}) so readers can render a genuinely stateless object
 * as {@code {}}; captureless lambdas (hidden/synthetic classes) stay silent; and a class
 * whose declared fields are all inaccessible still produces NO snapshot record — unreadable
 * must not masquerade as stateless.
 */
class EmptySnapshotTest {

    /** Genuinely stateless named class: no instance fields anywhere in its hierarchy. */
    static final class Stateless {}

    /** Statics are excluded from §3 dumps and don't make a class stateful. */
    static final class StaticOnly {
        static int shared = 5;
    }

    @TempDir
    Path tmp;

    @AfterEach
    void shutdown() {
        HindsightRuntime.shutdownForTests();
    }

    private Path boot() {
        HindsightRuntime.boot(Recordings.config(tmp));
        return HindsightRuntime.recordingDir();
    }

    @Test
    void fieldLessClassDumpsAnEmptyFieldsPayload() throws IOException {
        final Path dir = boot();
        HindsightRuntime.worker().recordLocalWriteObject(1, 0, new Stateless());
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        final long id = byType(recs, Wire.TYPE_LOCAL_WRITE).get(0).after();

        final List<Recordings.Rec> firstSeens = byType(recs, Wire.TYPE_OBJECT_FIRST_SEEN);
        assertEquals(1, firstSeens.size());
        assertEquals(id, firstSeens.get(0).target());
        final List<Recordings.Rec> snapshots = byType(recs, Wire.TYPE_SNAPSHOT);
        assertEquals(1, snapshots.size(), "field-less class gets its {} baseline");
        assertEquals(id, snapshots.get(0).target());

        // Payload is EXACTLY classSym(4) + form(1) + fieldCount(2) = 7 bytes.
        final Recordings.Blob blob = Recordings.blobAt(blobFile(dir), snapshots.get(0).after());
        assertEquals(Wire.BLOB_KIND_SNAPSHOT, blob.kind());
        assertEquals(7, blob.payload().length, "empty FIELDS payload is exactly 7 bytes");
        final ByteBuffer buf = ByteBuffer.wrap(blob.payload()).order(ByteOrder.LITTLE_ENDIAN);
        final int classSym = buf.getInt();
        assertEquals(Stateless.class.getName(), Recordings.rtSymbols(dir).get(classSym)[1]);
        assertEquals(Wire.SNAPSHOT_FORM_FIELDS, buf.get() & 0xFF, "FIELDS form");
        assertEquals(0, buf.getShort() & 0xFFFF, "fieldCount 0");
    }

    @Test
    void capturelessLambdaGetsNoSnapshotRecord() throws IOException {
        final Path dir = boot();
        final Runnable r = () -> {};
        assertTrue(r.getClass().isHidden() || r.getClass().isSynthetic(),
                "precondition: lambda classes are hidden/synthetic");
        HindsightRuntime.worker().recordLocalWriteObject(1, 0, r);
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        final long id = byType(recs, Wire.TYPE_LOCAL_WRITE).get(0).after();
        assertTrue(byType(recs, Wire.TYPE_OBJECT_FIRST_SEEN).stream().anyMatch(fs -> fs.target() == id),
                "the lambda is still object-registered");
        assertEquals(0, byType(recs, Wire.TYPE_SNAPSHOT).size(),
                "hidden/synthetic exemption: no {} record for captureless lambdas");
    }

    /**
     * §3 branch (b) — fields declared but none accessible. Everything in this module is open
     * to the tests, so the branch is fabricated with {@code java.time.Instant}: it declares
     * instance fields ({@code seconds}, {@code nanos}) but {@code java.base/java.time} is not
     * opened to the unnamed test module, so {@code trySetAccessible} fails for every one — the
     * exact pre-open state the eager §10 boot-layer opens exist to fix. An assumption guards
     * against unusual test JVMs launched with {@code --add-opens java.base/java.time}.
     */
    @Test
    void declaredButInaccessibleFieldsProduceNoRecord() throws IOException {
        final ThreadWorker.SnapshotPlan plan = ThreadWorker.snapshotPlanOf(java.time.Instant.class);
        Assumptions.assumeTrue(plan.fields.length == 0,
                "test JVM unexpectedly opens java.time to the unnamed module");
        assertFalse(plan.emitEmpty, "declared-but-unreadable must not read as field-less");

        final Path dir = boot();
        HindsightRuntime.worker().recordLocalWriteObject(1, 0, java.time.Instant.ofEpochSecond(123, 45));
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        final long id = byType(recs, Wire.TYPE_LOCAL_WRITE).get(0).after();
        assertTrue(byType(recs, Wire.TYPE_OBJECT_FIRST_SEEN).stream().anyMatch(fs -> fs.target() == id),
                "the unreadable object still gets an id");
        assertEquals(0, byType(recs, Wire.TYPE_SNAPSHOT).size(),
                "unreadable object: no snapshot record at all");
    }

    /** The plan verdict itself, for the branches the byte-level tests exercise indirectly. */
    @Test
    void planDistinguishesFieldLessFromUnreadable() {
        final ThreadWorker.SnapshotPlan stateless = ThreadWorker.snapshotPlanOf(Stateless.class);
        assertEquals(0, stateless.fields.length);
        assertTrue(stateless.emitEmpty, "no declared instance fields anywhere: {} is emitted");

        final ThreadWorker.SnapshotPlan staticOnly = ThreadWorker.snapshotPlanOf(StaticOnly.class);
        assertEquals(0, staticOnly.fields.length);
        assertTrue(staticOnly.emitEmpty, "statics don't make a class stateful");

        final Runnable lambda = () -> {};
        assertFalse(ThreadWorker.snapshotPlanOf(lambda.getClass()).emitEmpty,
                "hidden/synthetic classes stay silent");
    }

    // ---------------------------------------------------------------------------------

    private static List<Recordings.Rec> byType(List<Recordings.Rec> recs, int type) {
        final List<Recordings.Rec> out = new ArrayList<>();
        for (final Recordings.Rec r : recs) {
            if (r.type() == type) {
                out.add(r);
            }
        }
        return out;
    }

    private Path blobFile(Path dir) throws IOException {
        Path blobFile = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "blob-*.bin")) {
            for (final Path b : ds) {
                blobFile = b;
            }
        }
        assertNotNull(blobFile, "snapshot payloads require a blob file");
        return blobFile;
    }
}
