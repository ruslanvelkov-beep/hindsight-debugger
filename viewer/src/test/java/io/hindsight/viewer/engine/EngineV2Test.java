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
 * White-box tests for the VIEWER.md §20 engine additions: inline wrapper scalars (§20.1), inline
 * {@code AtomicReference} (§20.2), lambda detection (§20.3) and the global variable-change list
 * (§20.9). Built against synthetic schema-3 recordings whose always-dump wrappers carry the FORMAT
 * §7 single {@code value} snapshot field.
 */
final class EngineV2Test {

    /**
     * A recording whose objects include each always-dump wrapper (its single {@code value} snapshot),
     * a {@code Holder} owning a scalar wrapper and an {@code AtomicReference} as fields, an ordinary
     * class and a lambda. The cursor opens on the trailing {@code METHOD_ENTER}, whose seq is past
     * every snapshot, so the wrappers resolve.
     */
    private static Path buildWrappers(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int file = r.sym("FILE", "Main.java");
        int cls = r.sym("CLASS", "com.ex.Main");
        int meth = r.sym("METHOD", "run");
        int site = r.methodSite(cls, file, 5, meth, "()V", true);
        int lineSite = r.lineSite(cls, file, 5);
        int rtValue = r.rtSym("FIELDNAME", "value");
        int rtCounter = r.rtSym("FIELDNAME", "counter");
        int rtRef = r.rtSym("FIELDNAME", "ref");
        int rtCls = r.rtSym("RTCLASS", "wrapper"); // snapshot classSym; reconstruct keys off fields
        int blobAfter = SyntheticRecording.flags(Format.KIND_NONE, Format.KIND_BLOB, 0);

        r.threadStart(1, "main", 1L, 0L);

        r.firstSeen(1, 10L, "java.util.concurrent.atomic.AtomicInteger");
        long s10 = r.snapshotFields(1, rtCls, SyntheticRecording.fieldList(
                new SyntheticRecording.Field(rtValue, Format.TAG_INT, Format.KIND_INLINE, 42L)));
        r.rec(1, Format.SNAPSHOT, blobAfter, 0, 0, 0, 10L, 0, s10);

        r.firstSeen(1, 11L, "java.util.concurrent.atomic.AtomicLong");
        long s11 = r.snapshotFields(1, rtCls, SyntheticRecording.fieldList(
                new SyntheticRecording.Field(rtValue, Format.TAG_LONG, Format.KIND_INLINE, 7L)));
        r.rec(1, Format.SNAPSHOT, blobAfter, 0, 0, 0, 11L, 0, s11);

        r.firstSeen(1, 12L, "java.util.concurrent.atomic.AtomicBoolean");
        long s12 = r.snapshotFields(1, rtCls, SyntheticRecording.fieldList(
                new SyntheticRecording.Field(rtValue, Format.TAG_BOOLEAN, Format.KIND_INLINE, 1L)));
        r.rec(1, Format.SNAPSHOT, blobAfter, 0, 0, 0, 12L, 0, s12);

        r.firstSeen(1, 13L, "java.lang.StringBuilder");
        long hello = r.blobUtf8(1, "hello");
        long s13 = r.snapshotFields(1, rtCls, SyntheticRecording.fieldList(
                new SyntheticRecording.Field(rtValue, Format.TAG_STRING, Format.KIND_BLOB, hello)));
        r.rec(1, Format.SNAPSHOT, blobAfter, 0, 0, 0, 13L, 0, s13);

        r.firstSeen(1, 20L, "com.ex.Foo"); // the referent held by the AtomicReference
        r.firstSeen(1, 14L, "java.util.concurrent.atomic.AtomicReference");
        long s14 = r.snapshotFields(1, rtCls, SyntheticRecording.fieldList(
                new SyntheticRecording.Field(rtValue, Format.TAG_REF, Format.KIND_OBJECT_ID, 20L)));
        r.rec(1, Format.SNAPSHOT, blobAfter, 0, 0, 0, 14L, 0, s14);

        r.firstSeen(1, 15L, "java.util.concurrent.atomic.AtomicReference");
        long s15 = r.snapshotFields(1, rtCls, SyntheticRecording.fieldList(
                new SyntheticRecording.Field(rtValue, Format.TAG_NULL, Format.KIND_NONE, 0L)));
        r.rec(1, Format.SNAPSHOT, blobAfter, 0, 0, 0, 15L, 0, s15);

        r.firstSeen(1, 40L, "com.ex.Holder"); // owns a scalar wrapper and an AtomicReference as fields
        long s40 = r.snapshotFields(1, rtCls, SyntheticRecording.fieldList(
                new SyntheticRecording.Field(rtCounter, Format.TAG_REF, Format.KIND_OBJECT_ID, 10L),
                new SyntheticRecording.Field(rtRef, Format.TAG_REF, Format.KIND_OBJECT_ID, 14L)));
        r.rec(1, Format.SNAPSHOT, blobAfter, 0, 0, 0, 40L, 0, s40);

        r.firstSeen(1, 30L, "com.ex.Foo$$Lambda/0x1000"); // a lambda

        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, site, 0, 0, 0); // cursor lands here (lowest-seq exec)
        r.rec(1, Format.LINE, 0, 0, 0, lineSite, 0, 0, 0);
        return r.write();
    }

    @Test
    void scalarWrappersRenderValueInlineAndAreNonExpandable(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildWrappers(dir))) {
            ObjectNode ai = e.objectTree(10L);
            assertEquals("AtomicInteger#10  [ 42  0x0000002A ]", ai.label());
            assertFalse(ai.expandable(), "a scalar wrapper shows its value inline and is non-expandable (§20.1)");
            assertTrue(e.isScalarWrapper(10L));

            assertEquals("AtomicLong#11  [ 7  0x0000000000000007 ]", e.objectTree(11L).label());
            assertEquals("AtomicBoolean#12  [ true ]", e.objectTree(12L).label());
            assertEquals("StringBuilder#13  [ \"hello\" ]", e.objectTree(13L).label());
            assertFalse(e.objectTree(11L).expandable());
            assertFalse(e.objectTree(12L).expandable());
            assertFalse(e.objectTree(13L).expandable());
            assertTrue(e.isScalarWrapper(13L));
        }
    }

    @Test
    void atomicReferenceInlinesHeldValueAndKeepsExpandableWhenNonNull(@TempDir final Path dir)
            throws IOException {
        try (Engine e = Engine.open(buildWrappers(dir))) {
            ObjectNode ref = e.objectTree(14L);
            assertEquals("AtomicReference#14  [ Foo#20 ]", ref.label());
            assertTrue(ref.expandable(), "a non-null AtomicReference stays expandable (§20.2)");
            assertFalse(e.isScalarWrapper(14L), "AtomicReference is not a scalar wrapper");

            ObjectNode nullRef = e.objectTree(15L);
            assertEquals("AtomicReference#15  [ null ]", nullRef.label());
            assertFalse(nullRef.expandable(), "a null-holding AtomicReference is non-expandable (§20.2)");
        }
    }

    @Test
    void wrapperFieldsInlineInObjectTreeChildren(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildWrappers(dir))) {
            List<ObjectNode> fields = e.children(e.objectTree(40L));
            assertEquals(2, fields.size());
            assertEquals("counter = AtomicInteger#10  [ 42  0x0000002A ]", fields.get(0).label());
            assertFalse(fields.get(0).expandable(), "a scalar-wrapper field is non-expandable");
            assertEquals("ref = AtomicReference#14  [ Foo#20 ]", fields.get(1).label());
            assertTrue(fields.get(1).expandable(), "an AtomicReference field stays expandable");
        }
    }

    @Test
    void isLambdaDetectsLambdaRuntimeClass(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildWrappers(dir))) {
            assertTrue(e.isLambda(30L), "a $$Lambda runtime class is a lambda");
            assertFalse(e.isLambda(20L), "an ordinary class is not a lambda");
            assertFalse(e.isLambda(999L), "an unknown id is not a lambda");
        }
    }

    /** A recording with mixed static + instance field writes, ascending seq, for §20.9. */
    private static Path buildFieldWrites(final Path dir) throws IOException {
        SyntheticRecording r = new SyntheticRecording(dir);
        int file = r.sym("FILE", "Acct.java");
        int clsAcct = r.sym("CLASS", "com.ex.Acct");
        int meth = r.sym("METHOD", "run");
        int fBalance = r.sym("FIELD", "balance");
        int fCount = r.sym("FIELD", "count");
        int fTotal = r.sym("FIELD", "TOTAL");
        int site = r.methodSite(clsAcct, file, 5, meth, "()V", true);
        int lineSite = r.lineSite(clsAcct, file, 5);
        int sBalance = r.fieldSite(clsAcct, file, 10, fBalance, "I", false);
        int sCount = r.fieldSite(clsAcct, file, 11, fCount, "I", false);
        int sTotal = r.fieldSite(clsAcct, file, 12, fTotal, "I", true);
        int inline = SyntheticRecording.flags(Format.KIND_INLINE, Format.KIND_INLINE, 0);
        int staticInline = SyntheticRecording.flags(Format.KIND_INLINE, Format.KIND_INLINE, Format.FLAG_STATIC);

        r.threadStart(1, "main", 1L, 0L);
        r.firstSeen(1, 100L, "com.ex.Acct");
        r.rec(1, Format.FIELD_WRITE, inline, Format.TAG_INT, 0, sBalance, 100L, 0, 10); // balance 0 -> 10
        r.rec(1, Format.FIELD_WRITE, inline, Format.TAG_INT, 0, sCount, 100L, 0, 1); // count 0 -> 1
        r.rec(1, Format.FIELD_WRITE, inline, Format.TAG_INT, 0, sBalance, 100L, 10, 20); // balance 10 -> 20
        r.rec(1, Format.FIELD_WRITE, staticInline, Format.TAG_INT, 0, sTotal, 0, 0, 5); // static TOTAL 0 -> 5
        r.rec(1, Format.METHOD_ENTER, 0, 0, 0, site, 0, 0, 0);
        r.rec(1, Format.LINE, 0, 0, 0, lineSite, 0, 0, 0);
        return r.write();
    }

    @Test
    void fieldWriteChangesListsAllMatchingWritesAscendingWithStaticsMixed(@TempDir final Path dir)
            throws IOException {
        try (Engine e = Engine.open(buildFieldWrites(dir))) {
            List<FieldWriteHit> all = e.fieldWriteChanges(null, 10);
            assertEquals(4, all.size());
            assertEquals("balance", all.get(0).fieldName());
            assertEquals("0  0x00000000", all.get(0).before());
            assertEquals("10  0x0000000A", all.get(0).after());
            assertEquals(100L, all.get(0).ownerId());
            assertFalse(all.get(0).isStatic());
            assertEquals("count", all.get(1).fieldName());
            assertEquals("balance", all.get(2).fieldName());
            assertEquals("20  0x00000014", all.get(2).after());
            // The static write is mixed in by seq, with owner id 0 and isStatic true.
            assertEquals("TOTAL", all.get(3).fieldName());
            assertTrue(all.get(3).isStatic());
            assertEquals(0L, all.get(3).ownerId());
            for (int i = 1; i < all.size(); i++) {
                assertTrue(all.get(i - 1).seq() < all.get(i).seq(), "ascending seq");
            }
        }
    }

    @Test
    void fieldWriteChangesHonoursSubstringCapAndCount(@TempDir final Path dir) throws IOException {
        try (Engine e = Engine.open(buildFieldWrites(dir))) {
            assertEquals(2, e.fieldWriteChanges("bal", 10).size(), "substring filter");
            assertEquals(2, e.fieldWriteChanges("BALANCE", 10).size(), "case-insensitive");
            assertEquals(4, e.fieldWriteChanges("", 10).size(), "empty = all");
            assertEquals(4, e.fieldWriteChanges(null, 10).size(), "null = all");

            List<FieldWriteHit> capped = e.fieldWriteChanges("bal", 1);
            assertEquals(1, capped.size(), "cap honoured");
            assertEquals("balance", capped.get(0).fieldName());
            assertEquals("10  0x0000000A", capped.get(0).after(), "the first (lowest-seq) balance write");
            assertEquals(0, e.fieldWriteChanges("bal", 0).size(), "non-positive cap yields nothing");

            assertEquals(2, e.countFieldWriteChanges("bal"));
            assertEquals(1, e.countFieldWriteChanges("count"));
            assertEquals(4, e.countFieldWriteChanges(""));
            assertEquals(4, e.countFieldWriteChanges(null));
            assertEquals(0, e.countFieldWriteChanges("nomatch"));
        }
    }
}
