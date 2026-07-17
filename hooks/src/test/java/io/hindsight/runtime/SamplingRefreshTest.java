// SPDX-License-Identifier: Apache-2.0
package io.hindsight.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FORMAT.md §7 trigger 2 — the sampling refresh (recorder ≥ v1.3 semantics): a refresh dump
 * of a FIELDS-form object additionally re-dumps the CURRENT contents of its direct
 * container-valued fields even though those containers are already registered, checkpointing
 * mutations that ran inside uninstrumented {@code java.base} code. Objects newly discovered
 * inside the level-2 container re-dump get FIRST_SEEN but (at the default depth 2) no dump of
 * their own. The xorshift countdown cannot be forced naturally, so the tests prime it through
 * {@link ThreadWorker#forceSnapshotCountdownForTests}; {@code snapshotEvery} stays enormous
 * so no organic fire can race the assertions.
 */
class SamplingRefreshTest {

    /** Refresh target: one already-registered container field plus a primitive field. */
    static final class Repo {
        final ArrayList<Object> items = new ArrayList<>();
        int version;
    }

    /** Fresh object first discovered inside the container re-dump (level 3 at depth 2). */
    static final class Probe {
        int p = 3;
    }

    /** Host whose container field is a subList VIEW — drifts into a size()-throwing state. */
    static final class ViewHost {
        List<Integer> view;
    }

    /** Outer object whose level-2 nested baseline dump resolves a further plain reference. */
    static final class Holder {
        final Inner inner = new Inner();
    }

    static final class Inner {
        final Object o = new Probe();
    }

    @TempDir
    Path tmp;

    @AfterEach
    void shutdown() {
        HindsightRuntime.shutdownForTests();
    }

    @Test
    void refreshRedumpsRegisteredContainerFieldsWithCurrentContents() throws IOException {
        final RuntimeConfig cfg = Recordings.config(tmp);
        cfg.snapshotEvery = 1 << 30; // sampler armed, organically all but unreachable
        HindsightRuntime.boot(cfg);
        final Path dir = HindsightRuntime.recordingDir();

        final Repo repo = new Repo();
        final ThreadWorker worker = HindsightRuntime.worker();
        worker.recordLocalWriteObject(1, 0, repo); // first sight: repo + items baselines

        // Container drift the recorder cannot see: java.base mutations fire no hooks.
        repo.items.add("x");
        repo.items.add(42);
        repo.items.add(new Probe());

        worker.forceSnapshotCountdownForTests(1); // next resolution fires the refresh
        worker.recordLocalWriteObject(2, 1, repo);
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        final long repoId = byType(recs, Wire.TYPE_LOCAL_WRITE).get(0).after();

        // Two FIELDS dumps of the repo: the first-sight baseline and the sampling refresh.
        final List<Recordings.Rec> repoSnaps = snapshotsOf(recs, repoId);
        assertEquals(2, repoSnaps.size(), "baseline + refresh dump of the refreshed object");
        final long itemsId = parseFields(dir, repoSnaps.get(0).after()).get("items")[2];
        assertTrue(itemsId > 0, "the container field is a registered object");

        // The container was already registered, yet the refresh re-dumps its CURRENT contents.
        final List<Recordings.Rec> itemsSnaps = snapshotsOf(recs, itemsId);
        assertEquals(2, itemsSnaps.size(), "baseline (empty) + container re-dump (drifted)");
        final Unfolded before = parseUnfolded(dir, itemsSnaps.get(0).after());
        assertEquals(0, before.totalSize, "baseline saw the list before the drift");
        final Unfolded after = parseUnfolded(dir, itemsSnaps.get(1).after());
        assertEquals(3, after.totalSize, "re-dump captures the drifted size");
        assertEquals(3, after.entries.size());
        assertEquals(Wire.TAG_STRING, (int) after.entries.get(0)[0]);
        assertEquals(Wire.KIND_BLOB, (int) after.entries.get(0)[1]);
        assertEquals("x", blobText(dir, after.entries.get(0)[2]));
        assertEquals(Wire.TAG_INT, (int) after.entries.get(1)[0], "boxed element inline");
        assertEquals(Wire.KIND_INLINE, (int) after.entries.get(1)[1]);
        assertEquals(42L, after.entries.get(1)[2]);
        assertEquals(Wire.KIND_OBJECT_ID, (int) after.entries.get(2)[1], "fresh element becomes an id");
        final long probeId = after.entries.get(2)[2];

        // The re-dump follows the refresh dump that triggered it, both after the mutation.
        assertTrue(repoSnaps.get(1).seq() < itemsSnaps.get(1).seq(),
                "container re-dump follows the refreshed object's own dump");

        // Level rules on the refresh path: the probe was discovered inside a level-2 dump —
        // FIRST_SEEN always (before the payload referencing it), own dump only if 2 < depth.
        final Recordings.Rec probeFs = byType(recs, Wire.TYPE_OBJECT_FIRST_SEEN).stream()
                .filter(r -> r.target() == probeId)
                .findFirst()
                .orElseThrow();
        assertTrue(probeFs.seq() < itemsSnaps.get(1).seq(), "probe first-seen precedes the re-dump payload");
        assertTrue(snapshotsOf(recs, probeId).isEmpty(),
                "at depth 2 an object discovered inside the level-2 re-dump gets first-seen only");

        long prev = -1;
        for (final Recordings.Rec r : recs) {
            assertTrue(r.seq() > prev, "thread file seq-monotonic");
            prev = r.seq();
        }
    }

    @Test
    void refreshWithoutContainerFieldsEmitsOnlyTheTriggeringDump() throws IOException {
        final RuntimeConfig cfg = Recordings.config(tmp);
        cfg.snapshotEvery = 1 << 30;
        HindsightRuntime.boot(cfg);
        final Path dir = HindsightRuntime.recordingDir();

        final Probe probe = new Probe(); // FIELDS form, no container-valued fields
        final ThreadWorker worker = HindsightRuntime.worker();
        worker.recordLocalWriteObject(1, 0, probe);
        worker.forceSnapshotCountdownForTests(1);
        worker.recordLocalWriteObject(2, 1, probe);
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        final long probeId = byType(recs, Wire.TYPE_LOCAL_WRITE).get(0).after();
        assertEquals(2, snapshotsOf(recs, probeId).size(), "baseline + refresh, nothing else");
        assertEquals(2, byType(recs, Wire.TYPE_SNAPSHOT).size(), "no container re-dumps to add");
    }

    /**
     * Regression: a drifted {@code java.base} view (a stale {@code ArrayList$SubList} after
     * structural modification of its backing list) throws from {@code size()} before yielding a
     * single element — and the container refresh exists precisely to re-dump such long-lived
     * drifted containers. The re-dump must degrade to a WELL-FORMED empty §3 payload, not the
     * 5-byte truncated payload (classSym + form only) that hard-fails the reader's verify.
     */
    @Test
    void refreshOfDriftedViewKeepsPayloadWellFormed() throws IOException {
        final RuntimeConfig cfg = Recordings.config(tmp);
        cfg.snapshotEvery = 1 << 30;
        HindsightRuntime.boot(cfg);
        final Path dir = HindsightRuntime.recordingDir();

        final ArrayList<Integer> backing = new ArrayList<>(List.of(1, 2, 3));
        final ViewHost host = new ViewHost();
        host.view = backing.subList(0, 2);
        final ThreadWorker worker = HindsightRuntime.worker();
        worker.recordLocalWriteObject(1, 0, host); // baselines: host FIELDS + view LIST(2)

        backing.add(6); // structural drift: the subList view now throws from size()

        worker.forceSnapshotCountdownForTests(1);
        worker.recordLocalWriteObject(2, 1, host); // refresh + container re-dump of the view
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        final long hostId = byType(recs, Wire.TYPE_LOCAL_WRITE).get(0).after();
        final long viewId = parseFields(dir, snapshotsOf(recs, hostId).get(0).after()).get("view")[2];

        final List<Recordings.Rec> viewSnaps = snapshotsOf(recs, viewId);
        assertEquals(2, viewSnaps.size(), "baseline + re-dump of the drifted view");
        assertEquals(2, parseUnfolded(dir, viewSnaps.get(0).after()).totalSize, "pre-drift baseline");
        final Unfolded redump = parseUnfolded(dir, viewSnaps.get(1).after());
        assertEquals(0, redump.totalSize, "size() threw: nothing counted");
        assertEquals(0, redump.entries.size(), "size() threw: nothing collected — but well-formed");
    }

    /**
     * Regression: the just-baselined duplicate suppression must survive depth ≥ 2. The queued
     * level-2 dump of {@code holder.inner} resolves {@code inner.o} through
     * {@code resolveObjectId} BEFORE the outer resolution of {@code holder} returns; clearing
     * the marker unconditionally there wiped it, so a sampler countdown firing on the very
     * resolution that first registered the holder emitted an immediate duplicate refresh.
     */
    @Test
    void samplerFiringOnFirstSightStaysSuppressedAtDepthTwo() throws IOException {
        final RuntimeConfig cfg = Recordings.config(tmp);
        cfg.snapshotEvery = 1 << 30;
        HindsightRuntime.boot(cfg);
        final Path dir = HindsightRuntime.recordingDir();

        final Holder holder = new Holder();
        final ThreadWorker worker = HindsightRuntime.worker();
        worker.forceSnapshotCountdownForTests(1); // fire on the registering resolution itself
        worker.recordLocalWriteObject(1, 0, holder);
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        final long holderId = byType(recs, Wire.TYPE_LOCAL_WRITE).get(0).after();
        assertEquals(1, snapshotsOf(recs, holderId).size(),
                "first-sight baseline only — no duplicate sampler refresh at depth 2");
    }

    /**
     * A refresh firing directly ON a container re-dumps its current elements and does NOT walk
     * fields (its refresh dump already IS its contents — refreshContainerFields early-returns).
     */
    @Test
    void refreshFiringOnAContainerRedumpsItWithoutWalkingFields() throws IOException {
        final RuntimeConfig cfg = Recordings.config(tmp);
        cfg.snapshotEvery = 1 << 30;
        HindsightRuntime.boot(cfg);
        final Path dir = HindsightRuntime.recordingDir();

        final ArrayList<Object> list = new ArrayList<>();
        final ThreadWorker worker = HindsightRuntime.worker();
        worker.recordLocalWriteObject(1, 0, list); // baseline (empty)
        list.add("y"); // invisible java.base drift
        worker.forceSnapshotCountdownForTests(1);
        worker.recordLocalWriteObject(2, 1, list);
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        final long listId = byType(recs, Wire.TYPE_LOCAL_WRITE).get(0).after();
        final List<Recordings.Rec> listSnaps = snapshotsOf(recs, listId);
        assertEquals(2, listSnaps.size(), "baseline + refresh of the container itself");
        assertEquals(2, byType(recs, Wire.TYPE_SNAPSHOT).size(), "no field walk, no extra dumps");
        final Unfolded refreshed = parseUnfolded(dir, listSnaps.get(1).after());
        assertEquals(1, refreshed.totalSize, "the refresh captured the drifted element");
        assertEquals("y", blobText(dir, refreshed.entries.get(0)[2]));
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

    private static List<Recordings.Rec> snapshotsOf(List<Recordings.Rec> recs, long objectId) {
        final List<Recordings.Rec> out = new ArrayList<>();
        for (final Recordings.Rec r : byType(recs, Wire.TYPE_SNAPSHOT)) {
            if (r.target() == objectId) {
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

    private String blobText(Path dir, long offset) throws IOException {
        final Recordings.Blob blob = Recordings.blobAt(blobFile(dir), offset);
        assertEquals(Wire.BLOB_KIND_UTF8, blob.kind());
        return new String(blob.payload(), StandardCharsets.UTF_8);
    }

    /** Parses a §3 FIELDS payload into {@code fieldName -> [tag, kind, value]}. */
    private Map<String, long[]> parseFields(Path dir, long blobOffset) throws IOException {
        final Recordings.Blob blob = Recordings.blobAt(blobFile(dir), blobOffset);
        assertEquals(Wire.BLOB_KIND_SNAPSHOT, blob.kind());
        final Map<Integer, String[]> symbols = Recordings.rtSymbols(dir);
        final ByteBuffer buf = ByteBuffer.wrap(blob.payload()).order(ByteOrder.LITTLE_ENDIAN);
        buf.getInt(); // classSym
        assertEquals(Wire.SNAPSHOT_FORM_FIELDS, buf.get() & 0xFF, "plain object dumps as FIELDS");
        final int fieldCount = buf.getShort() & 0xFFFF;
        final Map<String, long[]> fields = new HashMap<>();
        for (int i = 0; i < fieldCount; i++) {
            final int nameSym = buf.getInt();
            final int tag = buf.get() & 0xFF;
            final int kind = buf.get() & 0xFF;
            final long value = buf.getLong();
            fields.put(symbols.get(nameSym)[1], new long[] {tag, kind, value});
        }
        return fields;
    }

    /** Parsed unfolded LIST payload: total container size + [tag, kind, value] entries. */
    private record Unfolded(long totalSize, List<long[]> entries) {}

    private Unfolded parseUnfolded(Path dir, long blobOffset) throws IOException {
        final Recordings.Blob blob = Recordings.blobAt(blobFile(dir), blobOffset);
        assertEquals(Wire.BLOB_KIND_SNAPSHOT, blob.kind());
        final ByteBuffer buf = ByteBuffer.wrap(blob.payload()).order(ByteOrder.LITTLE_ENDIAN);
        buf.getInt(); // classSym
        assertEquals(Wire.SNAPSHOT_FORM_LIST, buf.get() & 0xFF, "ArrayList unfolds as LIST");
        final long totalSize = buf.getInt() & 0xFFFF_FFFFL;
        final int count = buf.getShort() & 0xFFFF;
        final List<long[]> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(new long[] {buf.get() & 0xFF, buf.get() & 0xFF, buf.getLong()});
        }
        return new Unfolded(totalSize, out);
    }
}
