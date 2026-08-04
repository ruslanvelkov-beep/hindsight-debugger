// SPDX-License-Identifier: GPL-3.0-or-later
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FORMAT.md §7 always-dump wrappers: the {@code java.base} single-value wrappers dump a
 * FIELDS payload with one synthetic {@code value} field (Atomics via {@code get()},
 * builders via {@code toString()}), are exempt from the {@code snapshotDepth} limit, and
 * are re-dumped by the sampling-refresh walk; {@code snapshotAlways} prefixes extend the
 * depth exemption to configured classes.
 */
class WrapperSnapshotTest {

    /** Host whose wrapper/plain fields sit at discovery level 2 when the host is level 1. */
    static final class Host {
        final AtomicLong counter = new AtomicLong(42);
        final AtomicInteger gauge = new AtomicInteger(7);
        final AtomicBoolean flag = new AtomicBoolean(true);
        final AtomicReference<Object> slot = new AtomicReference<>();
        final StringBuilder text = new StringBuilder("abc");
        final Plain plain = new Plain();
    }

    static final class Plain {
        int p = 1;
    }

    /** Class matched by the snapshotAlways prefix in the config test. */
    static final class Deep {
        int d = 9;
    }

    @TempDir
    Path tmp;

    @AfterEach
    void shutdown() {
        HindsightRuntime.shutdownForTests();
    }

    @Test
    void wrappersDumpSyntheticValueFieldAndAreDepthExempt() throws IOException {
        final RuntimeConfig cfg = Recordings.config(tmp);
        cfg.snapshotDepth = 1; // wrappers at level 2 must STILL dump; the plain object must not
        HindsightRuntime.boot(cfg);
        final Path dir = HindsightRuntime.recordingDir();

        final Host host = new Host();
        final Object referent = new Plain();
        host.slot.set(referent);
        HindsightRuntime.worker().recordLocalWriteObject(1, 0, host);
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        final long hostId = byType(recs, Wire.TYPE_LOCAL_WRITE).get(0).after();
        final Map<String, long[]> hostFields = parseFields(dir, onlySnapshotOf(recs, hostId).after());

        // Atomics: {value=<content>} read via get(), INLINE primitive slots.
        assertEquals(List.of(Wire.TAG_LONG, Wire.KIND_INLINE, 42L), entry(dir, recs, hostFields, "counter"));
        assertEquals(List.of(Wire.TAG_INT, Wire.KIND_INLINE, 7L), entry(dir, recs, hostFields, "gauge"));
        assertEquals(List.of(Wire.TAG_BOOLEAN, Wire.KIND_INLINE, 1L), entry(dir, recs, hostFields, "flag"));

        // AtomicReference: value routed through §2.4 — the referent becomes an object id
        // and, being a wrapper CONTENT (level 3 at depth 1), gets first-seen only.
        final long slotId = hostFields.get("slot")[2];
        final Map<String, long[]> slotValue = parseFields(dir, onlySnapshotOf(recs, slotId).after());
        final long referentId = slotValue.get("value")[2];
        assertEquals(Wire.KIND_OBJECT_ID, (int) slotValue.get("value")[1]);
        assertTrue(firstSeenIds(recs).contains(referentId), "referent registered");
        assertTrue(snapshotsOf(recs, referentId).isEmpty(), "referent beyond depth: first-seen only");

        // StringBuilder: {value="abc"} via toString().
        final long textId = hostFields.get("text")[2];
        final Map<String, long[]> textValue = parseFields(dir, onlySnapshotOf(recs, textId).after());
        assertEquals(Wire.TAG_STRING, (int) textValue.get("value")[0]);
        assertEquals("abc", blobText(dir, textValue.get("value")[2]));

        // The plain sibling at the same level is NOT depth-exempt.
        final long plainId = hostFields.get("plain")[2];
        assertTrue(snapshotsOf(recs, plainId).isEmpty(), "plain object at depth 1: first-seen only");
    }

    @Test
    void refreshWalkRedumpsDriftedWrapperFields() throws IOException {
        final RuntimeConfig cfg = Recordings.config(tmp);
        cfg.snapshotEvery = 1 << 30;
        HindsightRuntime.boot(cfg);
        final Path dir = HindsightRuntime.recordingDir();

        final Host host = new Host();
        final ThreadWorker worker = HindsightRuntime.worker();
        worker.recordLocalWriteObject(1, 0, host); // baselines: host + wrappers(42, "abc", ...)

        host.counter.set(99); // invisible java.base drift
        host.text.append("def");

        worker.forceSnapshotCountdownForTests(1);
        worker.recordLocalWriteObject(2, 1, host); // refresh + wrapper re-dumps
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        final long hostId = byType(recs, Wire.TYPE_LOCAL_WRITE).get(0).after();
        final List<Recordings.Rec> hostSnaps = snapshotsOf(recs, hostId);
        assertEquals(2, hostSnaps.size(), "baseline + refresh of the host");
        final Map<String, long[]> baseline = parseFields(dir, hostSnaps.get(0).after());

        final List<Recordings.Rec> counterSnaps = snapshotsOf(recs, baseline.get("counter")[2]);
        assertEquals(2, counterSnaps.size(), "baseline + refresh re-dump of the Atomic cell");
        assertEquals(42L, parseFields(dir, counterSnaps.get(0).after()).get("value")[2]);
        assertEquals(99L, parseFields(dir, counterSnaps.get(1).after()).get("value")[2], "drift captured");

        final List<Recordings.Rec> textSnaps = snapshotsOf(recs, baseline.get("text")[2]);
        assertEquals(2, textSnaps.size(), "baseline + refresh re-dump of the builder");
        assertEquals("abcdef", blobText(dir, parseFields(dir, textSnaps.get(1).after()).get("value")[2]));
    }

    @Test
    void snapshotAlwaysPrefixExtendsTheDepthExemption() throws IOException {
        final RuntimeConfig cfg = Recordings.config(tmp);
        cfg.snapshotDepth = 1;
        cfg.snapshotAlways = new String[] {Deep.class.getName()};
        HindsightRuntime.boot(cfg);
        final Path dir = HindsightRuntime.recordingDir();

        final Object holder = new Object() {
            final Deep deep = new Deep();
            final Plain plain = new Plain();
        };
        HindsightRuntime.worker().recordLocalWriteObject(1, 0, holder);
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        final long holderId = byType(recs, Wire.TYPE_LOCAL_WRITE).get(0).after();
        final Map<String, long[]> fields = parseFields(dir, onlySnapshotOf(recs, holderId).after());
        assertEquals(1, snapshotsOf(recs, fields.get("deep")[2]).size(),
                "snapshotAlways-matched class dumped past the depth limit");
        assertEquals(9L, parseFields(dir, snapshotsOf(recs, fields.get("deep")[2]).get(0).after()).get("d")[2]);
        assertTrue(snapshotsOf(recs, fields.get("plain")[2]).isEmpty(), "unmatched sibling: first-seen only");
    }

    // ---------------------------------------------------------------------------------

    /** {@code [tag, kind, slot]} of the synthetic {@code value} field of the wrapper behind {@code field}. */
    private List<Object> entry(Path dir, List<Recordings.Rec> recs, Map<String, long[]> hostFields, String field)
            throws IOException {
        final long id = hostFields.get(field)[2];
        final Map<String, long[]> v = parseFields(dir, onlySnapshotOf(recs, id).after());
        assertEquals(1, v.size(), field + " dumps exactly one synthetic field");
        return List.of((int) v.get("value")[0], (int) v.get("value")[1], v.get("value")[2]);
    }

    private static List<Recordings.Rec> byType(List<Recordings.Rec> recs, int type) {
        final List<Recordings.Rec> out = new ArrayList<>();
        for (final Recordings.Rec r : recs) {
            if (r.type() == type) {
                out.add(r);
            }
        }
        return out;
    }

    private static List<Long> firstSeenIds(List<Recordings.Rec> recs) {
        final List<Long> out = new ArrayList<>();
        for (final Recordings.Rec r : byType(recs, Wire.TYPE_OBJECT_FIRST_SEEN)) {
            out.add(r.target());
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

    private static Recordings.Rec onlySnapshotOf(List<Recordings.Rec> recs, long objectId) {
        final List<Recordings.Rec> all = snapshotsOf(recs, objectId);
        assertEquals(1, all.size(), "expected exactly one snapshot of #" + objectId);
        return all.get(0);
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
        assertEquals(Wire.SNAPSHOT_FORM_FIELDS, buf.get() & 0xFF, "FIELDS-form payload expected");
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
}
