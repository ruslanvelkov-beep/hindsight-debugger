// SPDX-License-Identifier: Apache-2.0
package io.hindsight.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.hooks.FieldWriteHook;
import io.hindsight.hooks.LocalWriteHook;
import io.hindsight.hooks.MethodHooks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Value encoding per FORMAT.md §2.4, verified against the bytes on disk. */
class ValueEncodingTest {

    private static final int MAX_STRING = 8;

    @TempDir
    Path tmp;

    private Path dir;

    @BeforeEach
    void boot() {
        final RuntimeConfig cfg = Recordings.config(tmp);
        cfg.maxString = MAX_STRING;
        HindsightRuntime.boot(cfg);
        dir = HindsightRuntime.recordingDir();
        assertEquals(tmp, dir, "empty requested dir must be used as-is");
    }

    @AfterEach
    void shutdown() {
        HindsightRuntime.shutdownForTests();
    }

    private List<Recordings.Rec> stopAndParse() throws IOException {
        HindsightRuntime.shutdownForTests();
        return Recordings.parseAll(dir);
    }

    private static Recordings.Rec only(List<Recordings.Rec> recs, int type, int location) {
        Recordings.Rec found = null;
        for (final Recordings.Rec r : recs) {
            if (r.type() == type && r.location() == location) {
                if (found != null) {
                    throw new AssertionError("more than one record of type " + type + " at site " + location);
                }
                found = r;
            }
        }
        if (found == null) {
            throw new AssertionError("no record of type " + type + " at site " + location);
        }
        return found;
    }

    private Path blobFile() {
        return dir.resolve("blob-00001.bin");
    }

    @Test
    void nullEncodesAsNoneWithNullTag() throws IOException {
        LocalWriteHook.writeObject(11, 3, null);
        final Recordings.Rec r = only(stopAndParse(), Wire.TYPE_LOCAL_WRITE, 11);
        assertEquals(Wire.KIND_NONE, r.afterKind());
        assertEquals(Wire.TAG_NULL, r.tag());
        assertEquals(0L, r.after());
        assertEquals(3, r.idx());
    }

    @Test
    void primitivesEncodeInline() throws IOException {
        LocalWriteHook.writeIntLike(21, 0, -5, Wire.TAG_BYTE);
        LocalWriteHook.writeIntLike(22, 0, 'Ω', Wire.TAG_CHAR);
        LocalWriteHook.writeLong(23, 0, Long.MIN_VALUE);
        LocalWriteHook.writeFloat(24, 0, 1.5f);
        LocalWriteHook.writeDouble(25, 0, -2.25);
        final List<Recordings.Rec> recs = stopAndParse();

        final Recordings.Rec b = only(recs, Wire.TYPE_LOCAL_WRITE, 21);
        assertEquals(Wire.KIND_INLINE, b.afterKind());
        assertEquals(Wire.TAG_BYTE, b.tag());
        assertEquals(-5L, b.after(), "int-likes are sign-extended");

        final Recordings.Rec c = only(recs, Wire.TYPE_LOCAL_WRITE, 22);
        assertEquals(Wire.TAG_CHAR, c.tag());
        assertEquals(0x03A9L, c.after(), "char is zero-extended");

        assertEquals(Long.MIN_VALUE, only(recs, Wire.TYPE_LOCAL_WRITE, 23).after());

        final Recordings.Rec f = only(recs, Wire.TYPE_LOCAL_WRITE, 24);
        assertEquals(Float.floatToRawIntBits(1.5f) & 0xFFFF_FFFFL, f.after(), "float bits zero-extended");

        assertEquals(Double.doubleToRawLongBits(-2.25), only(recs, Wire.TYPE_LOCAL_WRITE, 25).after());
    }

    @Test
    void boxedPrimitivesEncodeInlineWithBoxedFlag() throws IOException {
        LocalWriteHook.writeObject(31, 0, Integer.valueOf(-7));
        LocalWriteHook.writeObject(32, 0, Character.valueOf('x'));
        LocalWriteHook.writeObject(33, 0, Boolean.TRUE);
        final List<Recordings.Rec> recs = stopAndParse();

        final Recordings.Rec i = only(recs, Wire.TYPE_LOCAL_WRITE, 31);
        assertEquals(Wire.KIND_INLINE, i.afterKind());
        assertEquals(Wire.TAG_INT, i.tag());
        assertEquals(-7L, i.after());
        assertTrue((i.flags() & Wire.FLAG_BOXED) != 0, "BOXED flag must be set");

        final Recordings.Rec c = only(recs, Wire.TYPE_LOCAL_WRITE, 32);
        assertEquals(Wire.TAG_CHAR, c.tag());
        assertEquals('x', c.after());

        final Recordings.Rec z = only(recs, Wire.TYPE_LOCAL_WRITE, 33);
        assertEquals(Wire.TAG_BOOLEAN, z.tag());
        assertEquals(1L, z.after());
    }

    @Test
    void stringsGoToTheBlobWithTruncationAndMemo() throws IOException {
        final String shortStr = "hi";
        final String longStr = "0123456789ABCDEF"; // 16 chars > maxString 8
        LocalWriteHook.writeObject(41, 0, shortStr);
        LocalWriteHook.writeObject(42, 0, longStr);
        LocalWriteHook.writeObject(43, 0, shortStr); // memo: same value → same offset
        final List<Recordings.Rec> recs = stopAndParse();

        final Recordings.Rec s = only(recs, Wire.TYPE_LOCAL_WRITE, 41);
        assertEquals(Wire.KIND_BLOB, s.afterKind());
        assertEquals(Wire.TAG_STRING, s.tag());
        assertEquals(0, s.flags() & Wire.FLAG_TRUNCATED);
        final Recordings.Blob blob = Recordings.blobAt(blobFile(), s.after());
        assertEquals(Wire.BLOB_KIND_UTF8, blob.kind());
        assertEquals("hi", new String(blob.payload(), StandardCharsets.UTF_8));

        final Recordings.Rec t = only(recs, Wire.TYPE_LOCAL_WRITE, 42);
        assertTrue((t.flags() & Wire.FLAG_TRUNCATED) != 0, "TRUNCATED flag must be set");
        assertEquals("01234567",
                new String(Recordings.blobAt(blobFile(), t.after()).payload(), StandardCharsets.UTF_8));

        assertEquals(s.after(), only(recs, Wire.TYPE_LOCAL_WRITE, 43).after(), "memoised offset");
    }

    @Test
    void plainReferencesGetObjectIdsAndFirstSeen() throws IOException {
        final Object o = new Object();
        LocalWriteHook.writeObject(51, 0, o);
        final List<Recordings.Rec> recs = stopAndParse();

        final Recordings.Rec w = only(recs, Wire.TYPE_LOCAL_WRITE, 51);
        assertEquals(Wire.KIND_OBJECT_ID, w.afterKind());
        assertEquals(Wire.TAG_REF, w.tag());
        final long id = w.after();
        assertTrue(id > 0);

        Recordings.Rec firstSeen = null;
        for (final Recordings.Rec r : recs) {
            if (r.type() == Wire.TYPE_OBJECT_FIRST_SEEN && r.target() == id) {
                firstSeen = r;
            }
        }
        assertTrue(firstSeen != null, "OBJECT_FIRST_SEEN must exist for the id");
        assertTrue(firstSeen.seq() < w.seq(), "FIRST_SEEN must precede the referencing record");
        final String[] sym = Recordings.rtSymbols(dir).get(firstSeen.location());
        assertEquals("RTCLASS", sym[0]);
        assertEquals("java.lang.Object", sym[1]);
    }

    @Test
    void methodExitEncodings() throws IOException {
        MethodHooks.exit(61, null, Wire.TAG_VOID);
        MethodHooks.exit(62, Integer.valueOf(9), Wire.TAG_INT); // boxed by the shim, not the app
        MethodHooks.exit(63, "ret", Wire.TAG_REF);
        final Throwable boom = new IllegalStateException("boom");
        MethodHooks.exitExceptional(64, boom);
        final List<Recordings.Rec> recs = stopAndParse();

        final Recordings.Rec v = only(recs, Wire.TYPE_METHOD_EXIT, 61);
        assertEquals(Wire.TAG_VOID, v.tag());
        assertEquals(Wire.KIND_NONE, v.afterKind());

        final Recordings.Rec i = only(recs, Wire.TYPE_METHOD_EXIT, 62);
        assertEquals(Wire.TAG_INT, i.tag());
        assertEquals(Wire.KIND_INLINE, i.afterKind());
        assertEquals(9L, i.after());
        assertEquals(0, i.flags() & Wire.FLAG_BOXED, "shim boxing must not set BOXED");

        final Recordings.Rec s = only(recs, Wire.TYPE_METHOD_EXIT, 63);
        assertEquals(Wire.TAG_STRING, s.tag());
        assertEquals(Wire.KIND_BLOB, s.afterKind());

        final Recordings.Rec e = only(recs, Wire.TYPE_METHOD_EXIT, 64);
        assertTrue((e.flags() & Wire.FLAG_EXCEPTIONAL) != 0);
        assertEquals(Wire.KIND_OBJECT_ID, e.afterKind());
        assertTrue(e.after() > 0, "after must carry the throwable's object id");
    }

    @Test
    void referenceFieldWritesEncodeSidesIndependently() throws IOException {
        final Object owner = new Object();
        final Object newValue = new Object();
        FieldWriteHook.recordObject(71, owner, "old", newValue);
        FieldWriteHook.recordObject(72, null, null, Integer.valueOf(5)); // static field
        final List<Recordings.Rec> recs = stopAndParse();

        final Recordings.Rec r = only(recs, Wire.TYPE_FIELD_WRITE, 71);
        assertEquals(Wire.TAG_REF, r.tag());
        assertEquals(Wire.KIND_BLOB, r.beforeKind());
        assertEquals(Wire.KIND_OBJECT_ID, r.afterKind());
        assertTrue(r.target() > 0, "owner id");
        assertEquals(0, r.flags() & Wire.FLAG_STATIC);
        assertEquals("old",
                new String(Recordings.blobAt(blobFile(), r.before()).payload(), StandardCharsets.UTF_8));
        assertNotEquals(r.target(), r.after());

        final Recordings.Rec s = only(recs, Wire.TYPE_FIELD_WRITE, 72);
        assertTrue((s.flags() & Wire.FLAG_STATIC) != 0);
        assertEquals(0L, s.target());
        assertEquals(Wire.KIND_NONE, s.beforeKind());
        assertEquals(Wire.KIND_INLINE, s.afterKind());
        assertTrue((s.flags() & Wire.FLAG_BOXED) != 0);
        assertEquals(5L, s.after());
    }
}
