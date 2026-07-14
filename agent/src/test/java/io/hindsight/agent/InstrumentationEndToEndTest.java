// SPDX-License-Identifier: Apache-2.0
package io.hindsight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.hindsight.runtime.Wire;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Transformer-level end-to-end test: fixture classes run through the exact production
 * composition, execute against the REAL hooks runtime, and the resulting bytes on disk are
 * parsed strictly per FORMAT.md §2–§5 and asserted value-for-value.
 *
 * <p>Every fixture class loads through a throwaway loader with linking forced, so a splice
 * that breaks stack-map frames or stack balance fails the test as a {@code VerifyError}
 * before any assertion runs.
 */
class InstrumentationEndToEndTest {

    private static final String FIXTURE = "io.hindsight.agent.fixtures.Fixture";
    private static final String FIELD_HOST = "io.hindsight.agent.fixtures.FieldHost";

    @TempDir
    Path dir;

    private TestBed bed;

    @AfterEach
    void cleanup() {
        if (bed != null) {
            bed.close();
        }
    }

    @Test
    void fullScopeRecordingMatchesSpec() throws Exception {
        bed = TestBed.start(dir, AgentConfig.Scope.FULL, FIXTURE, FIELD_HOST);

        // --- run the fixtures (also proves the instrumented classes verify and behave) ---
        assertEquals("hello3", bed.invokeStatic(FIXTURE, "locals"));
        assertEquals(41, bed.invokeStatic(FIXTURE, "lambda"));
        assertEquals(6, bed.invokeStatic(FIXTURE, "recurse", 3));
        assertEquals(7, bed.invokeStatic(FIXTURE, "catcher"));
        final Class<?> hostType = bed.load(FIELD_HOST);
        final Object host = hostType.getDeclaredConstructor(int.class).newInstance(5);
        hostType.getMethod("update").invoke(host);
        assertEquals(42, hostType.getMethod("count").invoke(host));
        assertEquals(true, hostType.getMethod("flag").invoke(host));

        final Recorded rec = bed.stopAndParse();

        assertThreadFilePreamble(rec);
        assertEnterExitPairing(rec);
        assertArgAdjacency(rec);
        assertLocalWrites(rec);
        assertIinc(rec);
        assertFieldWrites(rec);
        assertConstructorBrackets(rec);
        assertExceptionalExit(rec);
        assertLambdaBracketed(rec);
        assertRecursion(rec);
        assertMethodSiteMetadata(rec);
    }

    @Test
    void callsOnlyScopeRecordsBracketsButNoLocalsOrFields() throws Exception {
        bed = TestBed.start(dir, AgentConfig.Scope.CALLS_ONLY, FIXTURE);
        assertEquals("hello3", bed.invokeStatic(FIXTURE, "locals"));
        final Recorded rec = bed.stopAndParse();

        assertFalse(rec.ofType(Wire.TYPE_METHOD_ENTER).isEmpty(), "brackets must be present");
        assertTrue(rec.ofType(Wire.TYPE_LOCAL_WRITE).isEmpty(), "callsOnly must not record locals");
        assertTrue(rec.ofType(Wire.TYPE_FIELD_WRITE).isEmpty(), "callsOnly must not record fields");
        for (final Recorded.Site site : rec.sites.values()) {
            assertEquals("METHOD", site.kind(), "callsOnly must not assign LOCAL/FIELD sites");
        }
        // Line/file metadata still flows through the prescan in callsOnly scope.
        final Recorded.Site locals = rec.sites.get(rec.methodSiteId(FIXTURE, "locals"));
        assertTrue(locals.line() > 0, "method site line must come from the prescan");
        assertEquals("Fixture.java", rec.symbolText(locals.fileSym()));
    }

    // =====================================================================================
    // §2.1 stream shape
    // =====================================================================================

    private static void assertThreadFilePreamble(Recorded rec) {
        final List<Recorded.Rec> firstTwo = new ArrayList<>();
        for (final Recorded.Rec r : rec.records) {
            if (r.tid() == 1) {
                firstTwo.add(r);
                if (firstTwo.size() == 2) {
                    break;
                }
            }
        }
        assertEquals(Wire.TYPE_THREAD_START, firstTwo.get(0).type(), "THREAD_START first");
        assertEquals(Wire.TYPE_TIME_SYNC, firstTwo.get(1).type(), "TIME_SYNC second");
    }

    private static void assertEnterExitPairing(Recorded rec) {
        final Deque<Integer> stack = new ArrayDeque<>();
        for (final Recorded.Rec r : rec.records) {
            if (r.type() == Wire.TYPE_METHOD_ENTER) {
                stack.push(r.location());
            } else if (r.type() == Wire.TYPE_METHOD_EXIT) {
                assertFalse(stack.isEmpty(), "EXIT without a live frame at seq " + r.seq());
                assertEquals(stack.pop().intValue(), r.location(), "EXIT must pair the innermost ENTER");
            }
        }
        assertTrue(stack.isEmpty(), "all frames returned before shutdown, leftover: " + stack);
    }

    private void assertArgAdjacency(Recorded rec) {
        boolean sawArgs = false;
        for (int i = 0; i < rec.records.size(); i++) {
            final Recorded.Rec enter = rec.records.get(i);
            if (enter.type() != Wire.TYPE_METHOD_ENTER || enter.idx() == 0) {
                continue;
            }
            for (int a = 0; a < enter.idx(); a++) {
                final Recorded.Rec arg = rec.records.get(i + 1 + a);
                assertEquals(Wire.TYPE_ARG, arg.type(), "record " + (a + 1) + " after ENTER must be ARG");
                assertEquals(enter.location(), arg.location(), "ARG carries the ENTER's site");
                assertEquals(a, arg.idx(), "ARG records come in arg-index order");
                sawArgs = true;
            }
        }
        assertTrue(sawArgs, "fixtures must have produced at least one ARG sequence");
    }

    // =====================================================================================
    // Locals (§11 splices, §2 encodings)
    // =====================================================================================

    private void assertLocalWrites(Recorded rec) throws Exception {
        // int accumulator: declaration store + three loop-body stores.
        final List<Recorded.Rec> total = rec.at(rec.localSites("total"), Wire.TYPE_LOCAL_WRITE);
        assertEquals(List.of(0L, 0L, 1L, 3L), afters(total), "total = 0, 0+0, 0+1, 1+2");
        for (final Recorded.Rec r : total) {
            assertEquals(Wire.TAG_INT, r.tag());
            assertEquals(Wire.KIND_INLINE, r.afterKind());
        }

        final List<Recorded.Rec> big = rec.at(rec.localSites("big"), Wire.TYPE_LOCAL_WRITE);
        assertEquals(1, big.size());
        assertEquals(Wire.TAG_LONG, big.get(0).tag());
        assertEquals(1_234_567_890_123L, big.get(0).after());

        final List<Recorded.Rec> ratio = rec.at(rec.localSites("ratio"), Wire.TYPE_LOCAL_WRITE);
        assertEquals(1, ratio.size());
        assertEquals(Wire.TAG_FLOAT, ratio.get(0).tag());
        assertEquals(Float.floatToRawIntBits(2.5f) & 0xFFFF_FFFFL, ratio.get(0).after());

        final List<Recorded.Rec> precise = rec.at(rec.localSites("precise"), Wire.TYPE_LOCAL_WRITE);
        assertEquals(1, precise.size());
        assertEquals(Wire.TAG_DOUBLE, precise.get(0).tag());
        assertEquals(Double.doubleToRawLongBits(3.25), precise.get(0).after());

        final List<Recorded.Rec> label = rec.at(rec.localSites("label"), Wire.TYPE_LOCAL_WRITE);
        assertEquals(1, label.size());
        assertEquals(Wire.TAG_STRING, label.get(0).tag());
        assertEquals(Wire.KIND_BLOB, label.get(0).afterKind());
        assertEquals("hello", rec.blobString(label.get(0).tid(), label.get(0).after()));

        final List<Recorded.Rec> box = rec.at(rec.localSites("box"), Wire.TYPE_LOCAL_WRITE);
        assertEquals(1, box.size());
        assertEquals(Wire.TAG_REF, box.get(0).tag());
        assertEquals(Wire.KIND_OBJECT_ID, box.get(0).afterKind());
        final long boxId = box.get(0).after();
        assertTrue(boxId > 0, "reference locals resolve to positive object ids");
        assertFirstSeenPrecedes(rec, boxId, box.get(0).seq());

        // Site metadata: name -> slot/desc/line mapping per §5.
        for (final Recorded.Site site : rec.localSites("big")) {
            assertEquals("J", site.desc());
            assertTrue(site.slot() >= 0, "LOCAL sites carry the LVT slot");
            assertTrue(site.line() > 0, "LOCAL sites carry the store line");
        }
    }

    private void assertIinc(Recorded rec) {
        final List<Recorded.Rec> i = rec.at(rec.localSites("i"), Wire.TYPE_LOCAL_WRITE);
        assertEquals(List.of(0L, 1L, 2L, 3L), afters(i), "declaration store plus three IINCs");
    }

    // =====================================================================================
    // Fields (§11 splices, before → after capture)
    // =====================================================================================

    private void assertFieldWrites(Recorded rec) throws Exception {
        final List<Recorded.Rec> count = rec.at(rec.fieldSites("count"), Wire.TYPE_FIELD_WRITE);
        assertEquals(2, count.size(), "ctor init + update()");
        assertEquals(0L, count.get(0).before(), "fresh instance field reads as default");
        assertEquals(5L, count.get(0).after(), "ctor-init write");
        assertEquals(5L, count.get(1).before(), "old value captured before the write");
        assertEquals(42L, count.get(1).after());
        for (final Recorded.Rec r : count) {
            assertEquals(Wire.TAG_INT, r.tag());
            assertFalse(r.staticFlag());
            assertTrue(r.target() > 0, "instance writes carry the owner's object id");
        }

        assertSingleInlineFieldWrite(rec, "ticks", Wire.TAG_LONG, 0L, 99L);
        assertSingleInlineFieldWrite(
                rec, "fraction", Wire.TAG_FLOAT, 0L, Float.floatToRawIntBits(0.5f) & 0xFFFF_FFFFL);
        assertSingleInlineFieldWrite(rec, "ratio", Wire.TAG_DOUBLE, 0L, Double.doubleToRawLongBits(2.25));
        assertSingleInlineFieldWrite(rec, "flag", Wire.TAG_BOOLEAN, 0L, 1L);
        assertSingleInlineFieldWrite(rec, "letter", Wire.TAG_CHAR, 0L, 'x');
        assertSingleInlineFieldWrite(rec, "small", Wire.TAG_SHORT, 0L, 3L);
        assertSingleInlineFieldWrite(rec, "tiny", Wire.TAG_BYTE, 0L, 1L);

        // Reference field: null -> "init" (ctor), "init" -> "updated" (update()).
        // §2.3: ref-typed field writes carry per-slot RUNTIME tags — after's in the tag byte,
        // before's in the aux idx byte (so boxed values keep their primitive tag decodable).
        final List<Recorded.Rec> label = rec.at(rec.fieldSites("label"), Wire.TYPE_FIELD_WRITE);
        assertEquals(2, label.size());
        assertEquals(Wire.TAG_STRING, label.get(0).tag(), "after slot's runtime tag in the tag byte");
        assertEquals(Wire.TAG_NULL, label.get(0).idx(), "before slot's runtime tag in the idx byte");
        assertEquals(Wire.TAG_STRING, label.get(1).idx(), "string before -> STRING runtime tag");
        assertEquals(Wire.KIND_NONE, label.get(0).beforeKind(), "null before");
        assertEquals(Wire.KIND_BLOB, label.get(0).afterKind(), "string after");
        assertEquals("init", rec.blobString(label.get(0).tid(), label.get(0).after()));
        assertEquals(Wire.KIND_BLOB, label.get(1).beforeKind());
        assertEquals("init", rec.blobString(label.get(1).tid(), label.get(1).before()));
        assertEquals("updated", rec.blobString(label.get(1).tid(), label.get(1).after()));

        final List<Recorded.Rec> payload = rec.at(rec.fieldSites("payload"), Wire.TYPE_FIELD_WRITE);
        assertEquals(1, payload.size());
        assertEquals(Wire.KIND_OBJECT_ID, payload.get(0).afterKind(), "arbitrary objects become ids");

        // Statics: FLAG_STATIC + target 0 + '#s' descriptor suffix in the site table.
        final List<Recorded.Rec> staticCount = rec.at(rec.fieldSites("staticCount"), Wire.TYPE_FIELD_WRITE);
        assertEquals(1, staticCount.size());
        assertTrue(staticCount.get(0).staticFlag());
        assertEquals(0L, staticCount.get(0).target());
        assertEquals(0L, staticCount.get(0).before());
        assertEquals(7L, staticCount.get(0).after());
        assertEquals("I#s", rec.fieldSites("staticCount").get(0).desc());

        final List<Recorded.Rec> staticRef = rec.at(rec.fieldSites("staticRef"), Wire.TYPE_FIELD_WRITE);
        assertEquals(1, staticRef.size());
        assertTrue(staticRef.get(0).staticFlag());
        assertEquals(Wire.KIND_BLOB, staticRef.get(0).afterKind());
        assertEquals("sref", rec.blobString(staticRef.get(0).tid(), staticRef.get(0).after()));

        // FIELD site rows point at the owner class per §5.
        for (final Recorded.Site site : rec.fieldSites("count")) {
            assertEquals(FIELD_HOST, rec.symbolText(site.classSym()));
            assertTrue(site.line() > 0, "FIELD sites carry the write line");
        }
    }

    private void assertSingleInlineFieldWrite(Recorded rec, String field, int tag, long before, long after) {
        final List<Recorded.Rec> writes = rec.at(rec.fieldSites(field), Wire.TYPE_FIELD_WRITE);
        assertEquals(1, writes.size(), "expected exactly one write to " + field);
        final Recorded.Rec r = writes.get(0);
        assertEquals(tag, r.tag(), field + " tag");
        assertEquals(Wire.KIND_INLINE, r.beforeKind(), field + " before kind");
        assertEquals(Wire.KIND_INLINE, r.afterKind(), field + " after kind");
        assertEquals(before, r.before(), field + " before");
        assertEquals(after, r.after(), field + " after");
    }

    // =====================================================================================
    // Constructors, exceptions, lambdas, recursion (§2.1, §11)
    // =====================================================================================

    private void assertConstructorBrackets(Recorded rec) {
        final int ctorSite = rec.methodSiteId(FIELD_HOST, "<init>");
        Recorded.Rec enter = null;
        Recorded.Rec exit = null;
        for (final Recorded.Rec r : rec.records) {
            if (r.location() != ctorSite) {
                continue;
            }
            if (r.type() == Wire.TYPE_METHOD_ENTER) {
                enter = r;
            } else if (r.type() == Wire.TYPE_METHOD_EXIT) {
                exit = r;
            }
        }
        if (enter == null || exit == null) {
            fail("constructor ENTER/EXIT missing");
            return;
        }
        assertEquals(0L, enter.target(), "ctor ENTER target is 0: 'this' is uninitialized (§2.1)");
        assertEquals(1, enter.idx(), "ctor arg count");
        assertNotEquals(0L, exit.target(), "ctor EXIT carries the freshly initialized instance");
        assertFalse(exit.exceptional());

        // The instance method ENTER must reference the same object the ctor produced.
        final int updateSite = rec.methodSiteId(FIELD_HOST, "update");
        for (final Recorded.Rec r : rec.records) {
            if (r.type() == Wire.TYPE_METHOD_ENTER && r.location() == updateSite) {
                assertEquals(exit.target(), r.target(), "same object id across ctor exit and method enter");
            }
        }
    }

    private void assertExceptionalExit(Recorded rec) {
        final int throwingSite = rec.methodSiteId(FIXTURE, "throwing");
        final List<Recorded.Rec> exits = new ArrayList<>();
        for (final Recorded.Rec r : rec.records) {
            if (r.type() == Wire.TYPE_METHOD_EXIT && r.location() == throwingSite) {
                exits.add(r);
            }
        }
        assertEquals(1, exits.size());
        final Recorded.Rec exit = exits.get(0);
        assertTrue(exit.exceptional(), "unwinding frame must set EXCEPTIONAL");
        assertEquals(Wire.KIND_OBJECT_ID, exit.afterKind(), "after carries the throwable's object id");
        assertTrue(exit.after() > 0);

        final int catcherSite = rec.methodSiteId(FIXTURE, "catcher");
        for (final Recorded.Rec r : rec.records) {
            if (r.type() == Wire.TYPE_METHOD_EXIT && r.location() == catcherSite) {
                assertFalse(r.exceptional(), "the catching frame returns normally");
                assertEquals(Wire.TAG_INT, r.tag());
                assertEquals(7L, r.after());
            }
        }
    }

    private void assertLambdaBracketed(Recorded rec) {
        Integer lambdaSite = null;
        for (final Recorded.Site site : rec.sites.values()) {
            if ("METHOD".equals(site.kind())
                    && FIXTURE.equals(rec.symbolText(site.classSym()))
                    && rec.symbolText(site.memberSym()).startsWith("lambda$")) {
                lambdaSite = site.id();
            }
        }
        assertTrue(lambdaSite != null, "the synthetic lambda$ body must have a METHOD site");
        boolean entered = false;
        for (final Recorded.Rec r : rec.records) {
            if (r.location() == lambdaSite && r.type() == Wire.TYPE_METHOD_ENTER) {
                entered = true;
            }
            if (r.location() == lambdaSite && r.type() == Wire.TYPE_METHOD_EXIT) {
                assertEquals(41L, r.after(), "lambda return value");
            }
        }
        assertTrue(entered, "the lambda body must be bracketed");

        final List<Recorded.Rec> inner = rec.at(rec.localSites("inner"), Wire.TYPE_LOCAL_WRITE);
        assertEquals(1, inner.size(), "locals inside lambda bodies are recorded");
        assertEquals(41L, inner.get(0).after());
    }

    private void assertRecursion(Recorded rec) {
        final int site = rec.methodSiteId(FIXTURE, "recurse");
        int enters = 0;
        int depth = 0;
        int maxDepth = 0;
        for (final Recorded.Rec r : rec.records) {
            if (r.location() != site) {
                continue;
            }
            if (r.type() == Wire.TYPE_METHOD_ENTER) {
                enters++;
                depth++;
                maxDepth = Math.max(maxDepth, depth);
            } else if (r.type() == Wire.TYPE_METHOD_EXIT) {
                depth--;
            }
        }
        assertEquals(4, enters, "recurse(3) enters 4 times");
        assertEquals(4, maxDepth, "recursion nests on one site");
        assertEquals(0, depth);
    }

    private void assertMethodSiteMetadata(Recorded rec) {
        final Recorded.Site locals = rec.sites.get(rec.methodSiteId(FIXTURE, "locals"));
        assertEquals("()Ljava/lang/String;#s", locals.desc(), "static suffix per §5");
        assertEquals("Fixture.java", rec.symbolText(locals.fileSym()), "FILE symbol from SourceFile");
        assertTrue(locals.line() > 0, "first body line from the prescan");
        assertEquals(-1, locals.slot());

        // Return value of locals(): a String, so EXIT carries a blob.
        for (final Recorded.Rec r : rec.records) {
            if (r.type() == Wire.TYPE_METHOD_EXIT && r.location() == locals.id()) {
                assertEquals(Wire.TAG_STRING, r.tag());
                try {
                    assertEquals("hello3", rec.blobString(r.tid(), r.after()));
                } catch (Exception e) {
                    fail(e);
                }
            }
        }
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private static List<Long> afters(List<Recorded.Rec> records) {
        final List<Long> out = new ArrayList<>(records.size());
        for (final Recorded.Rec r : records) {
            out.add(r.after());
        }
        return out;
    }

    private static void assertFirstSeenPrecedes(Recorded rec, long objectId, long referencingSeq) {
        for (final Recorded.Rec r : rec.records) {
            if (r.type() == Wire.TYPE_OBJECT_FIRST_SEEN && r.target() == objectId) {
                assertTrue(r.seq() < referencingSeq, "OBJECT_FIRST_SEEN must precede the first reference");
                return;
            }
        }
        fail("no OBJECT_FIRST_SEEN for object id " + objectId);
    }
}
