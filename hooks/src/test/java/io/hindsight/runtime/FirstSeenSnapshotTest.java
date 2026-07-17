// SPDX-License-Identifier: Apache-2.0
package io.hindsight.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 * First-sight baseline snapshots per FORMAT.md §7: every object gets a shallow SNAPSHOT
 * right after its OBJECT_FIRST_SEEN; objects newly registered while a level-{@code L} dump
 * executes always get first-seen and, while {@code L < snapshotDepth}, a baseline dump of
 * their own at level {@code L + 1} ({@code snapshotDepth=1} pins the pre-v1.3 depth-1
 * containment); maps/sets/lists/arrays unfold into elements (§3 forms); enums carry their
 * constant name instead; the {@code snapshotFirstSeen} switch disables the whole behavior.
 */
class FirstSeenSnapshotTest {

    /** Fixture with one primitive and one reference field — the reference is the nesting probe. */
    static final class Holder {
        int x = 7;
        Object ref = new Nested();
    }

    static final class Nested {
        int y = 13;
    }

    /** Three-level chain probing the §7 depth rules: outer → mid → leaf. */
    static final class Outer {
        Object mid = new Mid();
    }

    static final class Mid {
        Object leaf = new Leaf();
    }

    static final class Leaf {
        int w = 21;
    }

    @TempDir
    Path tmp;

    @AfterEach
    void shutdown() {
        HindsightRuntime.shutdownForTests();
    }

    private Path bootWith(boolean firstSeen) {
        return bootWith(firstSeen, new RuntimeConfig().snapshotDepth); // spec default (2)
    }

    private Path bootWith(boolean firstSeen, int depth) {
        final RuntimeConfig cfg = Recordings.config(tmp);
        cfg.snapshotFirstSeen = firstSeen;
        cfg.snapshotDepth = depth;
        HindsightRuntime.boot(cfg);
        return HindsightRuntime.recordingDir();
    }

    @Test
    void baselineSnapshotFollowsFirstSeenWithDepthOneContainment() throws IOException {
        final Path dir = bootWith(true, 1); // §7: depth 1 pins the pre-v1.3 behaviour
        final Holder holder = new Holder();
        HindsightRuntime.worker().recordLocalWriteObject(1, 0, holder);
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        final List<Recordings.Rec> firstSeens = byType(recs, Wire.TYPE_OBJECT_FIRST_SEEN);
        final List<Recordings.Rec> snapshots = byType(recs, Wire.TYPE_SNAPSHOT);

        // Holder + Nested each get an id; ONLY Holder (directly referenced) gets a snapshot.
        assertEquals(2, firstSeens.size(), "holder and its nested field object are first-seen");
        assertEquals(1, snapshots.size(), "depth-1: only the directly-referenced object is dumped");

        final long holderId = byType(recs, Wire.TYPE_LOCAL_WRITE).get(0).after();
        final Recordings.Rec snapshot = snapshots.get(0);
        assertEquals(holderId, snapshot.target(), "the snapshot dumps the first-seen object");

        // FIRST_SEEN's reserved seq precedes the snapshot's, and the thread file stays
        // seq-monotonic through the reentrant drain (nested first-seen mid-snapshot).
        final Recordings.Rec holderFs = firstSeens.stream()
                .filter(r -> r.target() == holderId)
                .findFirst()
                .orElseThrow();
        assertTrue(holderFs.seq() < snapshot.seq(), "first-seen precedes its baseline snapshot");
        long prev = -1;
        for (final Recordings.Rec r : recs) {
            assertTrue(r.seq() > prev, "thread file seq-monotonic");
            prev = r.seq();
        }

        // Payload: {x=7 inline, ref=<nested id>}.
        final long nestedId = firstSeens.stream()
                .map(Recordings.Rec::target)
                .filter(id -> id != holderId)
                .findFirst()
                .orElseThrow();
        final Map<String, long[]> fields = parseSnapshotFields(dir, snapshot.after());
        assertEquals(2, fields.size(), "both instance fields dumped");
        assertNotNull(fields.get("x"));
        assertEquals(Wire.TAG_INT, (int) fields.get("x")[0]);
        assertEquals(Wire.KIND_INLINE, (int) fields.get("x")[1]);
        assertEquals(7L, fields.get("x")[2]);
        assertNotNull(fields.get("ref"));
        assertEquals(Wire.KIND_OBJECT_ID, (int) fields.get("ref")[1]);
        assertEquals(nestedId, fields.get("ref")[2], "nested ref recorded as the nested object's id");
    }

    @Test
    void defaultDepthTwoDumpsNestedObjectsAndStopsAtLevelThree() throws IOException {
        final Path dir = bootWith(true); // spec default snapshotDepth = 2
        HindsightRuntime.worker().recordLocalWriteObject(1, 0, new Outer());
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        final List<Recordings.Rec> firstSeens = byType(recs, Wire.TYPE_OBJECT_FIRST_SEEN);
        final List<Recordings.Rec> snapshots = byType(recs, Wire.TYPE_SNAPSHOT);
        assertEquals(3, firstSeens.size(), "outer, mid and leaf are all first-seen");
        assertEquals(2, snapshots.size(),
                "depth 2: the triggering dump and its level-2 nested baselines only — leaf gets no dump");

        // Level 1: the directly-referenced Outer; its payload names Mid's id.
        final long outerId = byType(recs, Wire.TYPE_LOCAL_WRITE).get(0).after();
        assertEquals(outerId, snapshots.get(0).target(), "the level-1 dump comes first");
        final Map<String, long[]> outerFields = parseSnapshotFields(dir, snapshots.get(0).after());
        assertEquals(Wire.KIND_OBJECT_ID, (int) outerFields.get("mid")[1]);
        final long midId = outerFields.get("mid")[2];

        // Level 2: Mid gets a baseline of its own; its payload names Leaf's id.
        assertEquals(midId, snapshots.get(1).target(), "the nested object's own baseline dump");
        final Map<String, long[]> midFields = parseSnapshotFields(dir, snapshots.get(1).after());
        assertEquals(Wire.KIND_OBJECT_ID, (int) midFields.get("leaf")[1]);
        final long leafId = midFields.get("leaf")[2];

        // Level 3: Leaf was discovered inside a level-2 dump — first-seen only.
        final Recordings.Rec leafFs = firstSeens.stream()
                .filter(r -> r.target() == leafId)
                .findFirst()
                .orElseThrow();
        assertTrue(snapshots.stream().noneMatch(r -> r.target() == leafId),
                "an object discovered at level 3 gets no dump at depth 2");

        // §2.1: every FIRST_SEEN strictly precedes the payloads referencing its id, and the
        // thread file stays seq-monotonic through the queued (never recursive) nested dumps.
        final Recordings.Rec midFs = firstSeens.stream()
                .filter(r -> r.target() == midId)
                .findFirst()
                .orElseThrow();
        assertTrue(midFs.seq() < snapshots.get(0).seq(), "Mid first-seen before Outer's payload names it");
        assertTrue(leafFs.seq() < snapshots.get(1).seq(), "Leaf first-seen before Mid's payload names it");
        long prev = -1;
        for (final Recordings.Rec r : recs) {
            assertTrue(r.seq() > prev, "thread file seq-monotonic");
            prev = r.seq();
        }
    }

    @Test
    void objectArraysUnfoldAndFreshElementsGetTheirOwnBaselineAtDepthTwo() throws IOException {
        final Path dir = bootWith(true); // spec default snapshotDepth = 2
        HindsightRuntime.worker().recordLocalWriteObject(1, 0, new Object[] {new Nested(), null, 5});
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        final List<Recordings.Rec> snapshots = byType(recs, Wire.TYPE_SNAPSHOT);
        assertEquals(2, snapshots.size(), "the array's ARRAY-form dump plus the fresh element's baseline");

        final long arrayId = byType(recs, Wire.TYPE_LOCAL_WRITE).get(0).after();
        assertEquals(arrayId, snapshots.get(0).target());
        final Unfolded u = parseUnfolded(dir, snapshots.get(0).after(), Wire.SNAPSHOT_FORM_ARRAY);
        assertEquals(3, u.totalSize, "totalSize is the array length");
        assertEquals(3, u.entries.size());
        assertEquals(Wire.TAG_REF, (int) u.entries.get(0)[0], "fresh element recorded as an object id");
        assertEquals(Wire.KIND_OBJECT_ID, (int) u.entries.get(0)[1]);
        final long elementId = u.entries.get(0)[2];
        assertEquals(Wire.TAG_NULL, (int) u.entries.get(1)[0], "null element");
        assertEquals(Wire.KIND_NONE, (int) u.entries.get(1)[1]);
        assertEquals(Wire.TAG_INT, (int) u.entries.get(2)[0], "boxed element inline with its primitive tag");
        assertEquals(Wire.KIND_INLINE, (int) u.entries.get(2)[1]);
        assertEquals(5L, u.entries.get(2)[2]);

        // The fresh element: FIRST_SEEN before the array payload names it, own dump at level 2.
        final Recordings.Rec elementFs = byType(recs, Wire.TYPE_OBJECT_FIRST_SEEN).stream()
                .filter(r -> r.target() == elementId)
                .findFirst()
                .orElseThrow();
        assertTrue(elementFs.seq() < snapshots.get(0).seq(), "element first-seen precedes the array dump");
        assertEquals(elementId, snapshots.get(1).target(), "the element's own level-2 baseline");
        final Map<String, long[]> elementFields = parseSnapshotFields(dir, snapshots.get(1).after());
        assertEquals(13L, elementFields.get("y")[2]);
    }

    @Test
    void arraysUnfoldIntoElements() throws IOException {
        final Path dir = bootWith(true);
        HindsightRuntime.worker().recordLocalWriteObject(1, 0, new int[] {1, 2, 3});
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        assertEquals(1, byType(recs, Wire.TYPE_OBJECT_FIRST_SEEN).size());
        final List<Recordings.Rec> snapshots = byType(recs, Wire.TYPE_SNAPSHOT);
        assertEquals(1, snapshots.size());
        final Unfolded u = parseUnfolded(dir, snapshots.get(0).after(), Wire.SNAPSHOT_FORM_ARRAY);
        assertEquals(3, u.totalSize);
        assertEquals(3, u.entries.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(Wire.TAG_INT, (int) u.entries.get(i)[0], "primitive elements inline");
            assertEquals(Wire.KIND_INLINE, (int) u.entries.get(i)[1]);
            assertEquals(i + 1L, u.entries.get(i)[2]);
        }
    }

    @Test
    void mapsUnfoldIntoKeyValuePairs() throws IOException {
        final Path dir = bootWith(true);
        final java.util.LinkedHashMap<String, Integer> map = new java.util.LinkedHashMap<>();
        map.put("a", 1);
        map.put("b", 2);
        HindsightRuntime.worker().recordLocalWriteObject(1, 0, map);
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> snapshots = byType(Recordings.parseAll(dir), Wire.TYPE_SNAPSHOT);
        assertEquals(1, snapshots.size(), "the map itself; strings/boxed are by-value");
        final Unfolded u = parseUnfolded(dir, snapshots.get(0).after(), Wire.SNAPSHOT_FORM_MAP);
        assertEquals(2, u.totalSize);
        assertEquals(4, u.entries.size(), "two key entries + two value entries");
        assertEquals(Wire.TAG_STRING, (int) u.entries.get(0)[0], "String key by value");
        assertEquals(Wire.KIND_BLOB, (int) u.entries.get(0)[1]);
        assertEquals(Wire.TAG_INT, (int) u.entries.get(1)[0], "boxed Integer value inline");
        assertEquals(1L, u.entries.get(1)[2]);
        assertEquals(2L, u.entries.get(3)[2]);
    }

    @Test
    void listsAndSetsUnfoldWithTruncationTotal() throws IOException {
        final Path dir = bootWith(true);
        final java.util.ArrayList<Integer> big = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            big.add(i);
        }
        HindsightRuntime.worker().recordLocalWriteObject(1, 0, big);
        HindsightRuntime.worker().recordLocalWriteObject(2, 1, new java.util.LinkedHashSet<>(java.util.List.of(5)));
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> snapshots = byType(Recordings.parseAll(dir), Wire.TYPE_SNAPSHOT);
        assertEquals(2, snapshots.size());
        final Unfolded list = parseUnfolded(dir, snapshots.get(0).after(), Wire.SNAPSHOT_FORM_LIST);
        assertEquals(100, list.totalSize, "true container size survives the cap");
        assertEquals(Wire.SNAPSHOT_MAX_FIELDS, list.entries.size(), "captured entries capped");
        final Unfolded set = parseUnfolded(dir, snapshots.get(1).after(), Wire.SNAPSHOT_FORM_SET);
        assertEquals(1, set.totalSize);
        assertEquals(5L, set.entries.get(0)[2]);
    }

    /** Depth-1 probe enum: constant name travels in FIRST_SEEN, never a snapshot. */
    enum Suit {
        HEARTS,
        SPADES
    }

    @Test
    void enumsCarryConstantNameAndAreNeverSnapshotted() throws IOException {
        final Path dir = bootWith(true);
        HindsightRuntime.worker().recordLocalWriteObject(1, 0, Suit.SPADES);
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        final List<Recordings.Rec> firstSeens = byType(recs, Wire.TYPE_OBJECT_FIRST_SEEN);
        assertEquals(1, firstSeens.size());
        final Recordings.Rec fs = firstSeens.get(0);
        assertEquals(Wire.KIND_BLOB, fs.afterKind(), "display name blob in the after slot");
        Path blobFile = null;
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "blob-*.bin")) {
            for (final Path b : ds) {
                blobFile = b;
            }
        }
        assertNotNull(blobFile);
        final Recordings.Blob nameBlob = Recordings.blobAt(blobFile, fs.after());
        assertEquals(Wire.BLOB_KIND_UTF8, nameBlob.kind());
        assertEquals("SPADES", new String(nameBlob.payload(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(0, byType(recs, Wire.TYPE_SNAPSHOT).size(), "enums are never snapshotted");
    }

    /** Parsed unfolded payload: total container size + [tag, kind, value] entries. */
    private record Unfolded(long totalSize, List<long[]> entries) {}

    private Unfolded parseUnfolded(Path dir, long blobOffset, int expectedForm) throws IOException {
        Path blobFile = null;
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "blob-*.bin")) {
            for (final Path b : ds) {
                blobFile = b;
            }
        }
        assertNotNull(blobFile);
        final Recordings.Blob blob = Recordings.blobAt(blobFile, blobOffset);
        assertEquals(Wire.BLOB_KIND_SNAPSHOT, blob.kind());
        final ByteBuffer buf = ByteBuffer.wrap(blob.payload()).order(ByteOrder.LITTLE_ENDIAN);
        buf.getInt(); // classSym
        assertEquals(expectedForm, buf.get() & 0xFF);
        final long totalSize = buf.getInt() & 0xFFFF_FFFFL;
        final int count = buf.getShort() & 0xFFFF;
        final int entries = expectedForm == Wire.SNAPSHOT_FORM_MAP ? count * 2 : count;
        final List<long[]> out = new ArrayList<>(entries);
        for (int i = 0; i < entries; i++) {
            out.add(new long[] {buf.get() & 0xFF, buf.get() & 0xFF, buf.getLong()});
        }
        return new Unfolded(totalSize, out);
    }

    @Test
    void offSwitchDisablesBaselineSnapshots() throws IOException {
        final Path dir = bootWith(false);
        HindsightRuntime.worker().recordLocalWriteObject(1, 0, new Holder());
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        // Only the directly-referenced Holder gets an id: without the baseline snapshot,
        // nothing ever reaches the nested field object.
        assertEquals(1, byType(recs, Wire.TYPE_OBJECT_FIRST_SEEN).size(), "direct ref still gets an id");
        assertEquals(0, byType(recs, Wire.TYPE_SNAPSHOT).size(), "no baselines when disabled");
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

    /** Parses a §3 SNAPSHOT payload into {@code fieldName -> [tag, kind, value]}. */
    private Map<String, long[]> parseSnapshotFields(Path dir, long blobOffset) throws IOException {
        Path blobFile = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "blob-*.bin")) {
            for (final Path p : ds) {
                blobFile = p;
            }
        }
        assertNotNull(blobFile, "snapshot payload requires a blob file");
        final Recordings.Blob blob = Recordings.blobAt(blobFile, blobOffset);
        assertEquals(Wire.BLOB_KIND_SNAPSHOT, blob.kind());

        final Map<Integer, String> symbols = rtSymbols(dir);
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
            fields.put(symbols.get(nameSym), new long[] {tag, kind, value});
        }
        return fields;
    }

    private static Map<Integer, String> rtSymbols(Path dir) throws IOException {
        final Map<Integer, String> out = new HashMap<>();
        for (final String line : Files.readAllLines(dir.resolve("rt-symbols.tsv"))) {
            final String[] parts = line.split("\t", 3);
            if (parts.length == 3) {
                out.put(Integer.parseInt(parts[0]), parts[2]);
            }
        }
        return out;
    }
}
