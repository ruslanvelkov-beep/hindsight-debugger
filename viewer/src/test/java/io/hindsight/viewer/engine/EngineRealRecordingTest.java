// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.hindsight.reader.Format;
import io.hindsight.reader.Rec;
import io.hindsight.reader.Recording;
import io.hindsight.reader.Site;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Integration smoke test against a real recording, run only when that recording is
 * present on this machine (otherwise skipped). It catches real-data edge cases the
 * synthetic tests cannot: it opens the engine, steps 100 times, and asserts that
 * {@code stack()}/{@code locals()}/{@code recordInfo()} never throw and the cursor
 * stays on execution records (LINE/METHOD_ENTER/METHOD_EXIT, §17.1) the whole time.
 */
final class EngineRealRecordingTest {

    private static final Path REAL = realRecording("transfer-11");

    private static final Path REAL2 = realRecording("transfer-12");

    /**
     * A named recording under the directory given by {@code -Dhindsight.recordingsDir=<dir>}
     * (the directory containing the {@code transfer-N} recordings), or a non-existent placeholder
     * when the property is unset — so the tests below self-skip via {@code assumeTrue}.
     */
    private static Path realRecording(final String name) {
        String base = System.getProperty("hindsight.recordingsDir");
        return Path.of(base != null ? base : "hindsight-recordings-not-configured", name);
    }

    @Test
    void stepsThroughRealRecordingWithoutThrowing() throws IOException {
        assumeTrue(Files.isDirectory(REAL), "real recording not present: " + REAL);

        try (Engine e = Engine.open(REAL);
                Recording probe = Recording.open(REAL)) {
            assertNotNull(e.threads());
            assertEquals(3, e.schemaVersion(), "transfer-11 is a schema-3 recording");
            assertTrue(cursorIsOnExec(e, probe), "initial cursor should sit on an exec record");

            for (int i = 0; i < 100; i++) {
                // These must never throw on real data.
                List<Frame> stack = e.stack();
                assertNotNull(stack);
                for (int f = 0; f < stack.size(); f++) {
                    assertTrue(stack.get(f).recorded(),
                            "a schema-3 recording must yield only recorded frames (§14.5)");
                    assertNotNull(e.locals(f));
                }
                RecordInfo info = e.recordInfo();
                assertNotNull(info);
                assertNotNull(e.position());

                StepResult res = e.stepInto();
                if (!res.moved()) {
                    break; // reached the end of the thread within 100 steps
                }
                assertTrue(cursorIsOnExec(e, probe),
                        "stepInto must land on an exec record — LINE/ENTER/EXIT (index "
                                + e.currentIndex() + ")");
            }
        }
    }

    /**
     * §17.1 on a real schema-4 recording (transfer-12): 100 stepInto landings must all be
     * members of the exec set — LINE, METHOD_ENTER or METHOD_EXIT, never a metadata or
     * value record — with stack/locals/recordInfo reconstructable at every landing
     * (including cursors resting on ENTER/EXIT).
     */
    @Test
    void execSteppingLandsOnlyOnExecRecords() throws IOException {
        assumeTrue(Files.isDirectory(REAL2), "real recording not present: " + REAL2);

        try (Engine e = Engine.open(REAL2);
                Recording probe = Recording.open(REAL2)) {
            assertTrue(cursorIsOnExec(e, probe), "initial cursor should sit on an exec record");
            int moved = 0;
            for (int i = 0; i < 100; i++) {
                if (!e.stepInto().moved()) {
                    break;
                }
                moved++;
                int type = probe.recordAt(e.currentTid(), e.currentIndex()).type();
                assertTrue(isExecType(type),
                        "landing " + i + " must be LINE/ENTER/EXIT, was type " + type
                                + " at index " + e.currentIndex());
                assertNotNull(e.stack());
                assertFalse(e.stack().isEmpty(), "an exec cursor always has a frame");
                assertNotNull(e.locals(e.selectedFrame()));
                assertNotNull(e.recordInfo());
            }
            assertTrue(moved > 0, "the recording must allow at least one step");
        }
    }

    /**
     * §17.1 in a LINE-less (callsOnly-scope) thread of the real recording: stepping works
     * for the first time — ENTER/EXIT are the stops. Skipped when every thread of the
     * recording carries LINE records.
     */
    @Test
    void steppingMovesInLineLessThread() throws IOException {
        assumeTrue(Files.isDirectory(REAL2), "real recording not present: " + REAL2);

        try (Engine e = Engine.open(REAL2);
                Recording probe = Recording.open(REAL2)) {
            int lineLessTid = -1;
            long firstExec = -1;
            long execCount = 0;
            for (int tid : e.threads()) {
                ThreadIndex ti = e.threadIndex(tid);
                if (ti.lineIndices.length == 0 && ti.execIndices.length >= 2) {
                    lineLessTid = tid;
                    firstExec = ti.execIndices[0];
                    execCount = ti.execIndices.length;
                    break;
                }
            }
            assumeTrue(lineLessTid >= 0, "recording has no LINE-less thread with 2+ exec records");

            // Position on the thread's first exec record, then step: the cursor must move
            // and land on ENTER/EXIT records inside the same thread.
            e.seek(lineLessTid, firstExec);
            assertEquals(lineLessTid, e.currentTid());
            assertEquals(firstExec, e.currentIndex());
            for (int i = 0; i < Math.min(10, execCount - 1); i++) {
                StepResult res = e.stepInto();
                assertTrue(res.moved(), "stepInto must move in a LINE-less thread (step " + i + ")");
                assertEquals(lineLessTid, e.currentTid());
                int type = probe.recordAt(e.currentTid(), e.currentIndex()).type();
                assertTrue(type == Format.METHOD_ENTER || type == Format.METHOD_EXIT,
                        "landings in a LINE-less thread are ENTER/EXIT, was type " + type);
            }
            // Reverse mirrors; the LINE-oriented commands no-op gracefully.
            assertTrue(e.reverseStep().moved(), "reverseStep must move back");
            assertEquals(lineLessTid, e.currentTid());
            assertFalse(e.runToCurrentLine(true).moved(), "runTo is a safe no-op without LINEs");
            assertFalse(e.exitFrameForward().moved(), "exitFrameForward no-ops without LINEs");
            assertFalse(e.exitFrameBackward().moved(), "exitFrameBackward no-ops without LINEs");
        }
    }

    /**
     * Exercises the v1.1 navigation API ({@code seek}, {@code exitFrameForward/Backward},
     * {@code methodSites}, {@code callsOf}) against the real recording: none may throw, seek must
     * round-trip and honour its already-here contract, exit-frame must stay on LINE records and be
     * idempotent, and the method index must be globally seq-ordered with real coordinates.
     */
    @Test
    void v11NavigationApiOnRealRecording() throws IOException {
        assumeTrue(Files.isDirectory(REAL), "real recording not present: " + REAL);

        try (Engine e = Engine.open(REAL);
                Recording probe = Recording.open(REAL)) {

            // --- methodSites() / callsOf(): distinct sites, invocations globally seq-ordered ---
            List<MethodSite> sites = e.methodSites();
            assertNotNull(sites);
            assertTrue(sites.size() > 1, "a real recording should enter many method sites");
            for (int i = 1; i < sites.size(); i++) {
                assertTrue(compareSites(sites.get(i - 1), sites.get(i)) <= 0,
                        "methodSites() must be sorted by class then method");
            }
            MethodSite busiest = sites.stream()
                    .max((a, b) -> Long.compare(a.invocationCount(), b.invocationCount()))
                    .orElseThrow();
            List<Call> calls = e.callsOf(busiest.siteId(), 500);
            assertNotNull(calls);
            assertFalse(calls.isEmpty(), "the busiest site must have at least one invocation");
            assertTrue(calls.size() <= 500, "callsOf must honour the cap");
            long prevSeq = Long.MIN_VALUE;
            for (Call c : calls) {
                assertTrue(c.seq() >= prevSeq, "callsOf must be ascending by seq");
                prevSeq = c.seq();
                // Every Call is a real METHOD_ENTER coordinate.
                assertEquals(Format.METHOD_ENTER, probe.recordAt(c.tid(), c.index()).type(),
                        "callsOf must point at METHOD_ENTER records");
            }
            assertEquals(List.of(), e.callsOf(busiest.siteId(), 0), "cap 0 yields nothing");
            assertEquals(List.of(), e.callsOf(-1, 500), "unknown site yields nothing");

            // --- seek(): round-trip and already-here contract ---
            int startTid = e.currentTid();
            long startIndex = e.currentIndex();
            assertFalse(e.seek(startTid, startIndex).moved(), "seek to the current spot is a no-op");
            boolean advanced = false;
            for (int i = 0; i < 20; i++) {
                if (!e.stepInto().moved()) {
                    break;
                }
                advanced = true;
            }
            if (advanced) {
                assertTrue(e.currentIndex() != startIndex || e.currentTid() != startTid);
                assertTrue(e.seek(startTid, startIndex).moved(), "seek back to the origin moves");
                assertEquals(startIndex, e.currentIndex());
                assertEquals(startTid, e.currentTid());
                assertTrue(cursorIsOnExec(e, probe), "seek lands back on the origin exec record");
            }

            // --- exitFrameForward/Backward(): stay on LINE, idempotent at the ends ---
            e.seek(startTid, startIndex);
            StepResult fwd = e.exitFrameForward();
            if (fwd.moved()) {
                assertTrue(cursorIsOnLine(e, probe), "exitFrameForward lands on a LINE record");
                assertFalse(e.exitFrameForward().moved(), "already at frame return is a no-op");
            }
            StepResult back = e.exitFrameBackward();
            if (back.moved()) {
                assertTrue(cursorIsOnLine(e, probe), "exitFrameBackward lands on a LINE record");
                assertFalse(e.exitFrameBackward().moved(), "already at frame entry is a no-op");
            }
        }
    }

    /**
     * Exercises the v1.3 instance/field-write API ({@code classSites}, {@code instancesOf},
     * {@code firstMentionOf}, {@code fieldWrites}) against the real recording: the class index
     * must be sorted and every instance must point at a real {@code OBJECT_FIRST_SEEN} record,
     * first-mention lookups must round-trip, and a field-write query seeded from a real
     * {@code FIELD_WRITE} record must find that exact record again, ascending by seq.
     */
    @Test
    void v13InstanceAndFieldWriteApiOnRealRecording() throws IOException {
        assumeTrue(Files.isDirectory(REAL), "real recording not present: " + REAL);

        try (Engine e = Engine.open(REAL);
                Recording probe = Recording.open(REAL)) {

            // --- classSites(): non-empty, sorted by classBinary ---
            List<ClassSite> sites = e.classSites();
            assertFalse(sites.isEmpty(), "a real recording should register instances");
            for (int i = 1; i < sites.size(); i++) {
                assertTrue(sites.get(i - 1).classBinary().compareTo(sites.get(i).classBinary()) <= 0,
                        "classSites() must be sorted by classBinary");
            }

            // --- instancesOf(): the class with most instances — ascending seq, capped,
            //     every Instance a real OBJECT_FIRST_SEEN coordinate ---
            ClassSite biggest = sites.stream()
                    .max((a, b) -> Long.compare(a.instanceCount(), b.instanceCount()))
                    .orElseThrow();
            List<Instance> instances = e.instancesOf(biggest.classBinary(), 500);
            assertFalse(instances.isEmpty());
            assertEquals(Math.min(500L, biggest.instanceCount()), instances.size(),
                    "instancesOf must honour the cap");
            long prevSeq = Long.MIN_VALUE;
            for (Instance inst : instances) {
                assertTrue(inst.seq() >= prevSeq, "instancesOf must be ascending by seq");
                prevSeq = inst.seq();
                Rec r = probe.recordAt(inst.tid(), inst.index());
                assertEquals(Format.OBJECT_FIRST_SEEN, r.type(),
                        "every Instance must point at an OBJECT_FIRST_SEEN record");
                assertEquals(inst.objectId(), r.target());
                assertEquals(inst.seq(), r.seq());
            }
            Instance first = instances.get(0);
            assertEquals(List.of(first), e.instancesOf(biggest.classBinary(), 1), "cap 1 truncates");

            // --- firstMentionOf(): round-trips the first instance ---
            assertEquals(first, e.firstMentionOf(first.objectId()));

            // --- fieldWrites(): seeded from the lowest-seq resolvable FIELD_WRITE, so the seed
            //     is by construction the first hit of its own (file, field) query ---
            Rec seed = null;
            String seedOwner = null;
            String seedField = null;
            for (int tid : probe.threadIds()) {
                long count = probe.recordCount(tid);
                for (long i = 0; i < count; i++) {
                    Rec r = probe.recordAt(tid, i);
                    if (r.type() != Format.FIELD_WRITE || (seed != null && r.seq() >= seed.seq())) {
                        continue;
                    }
                    Site s = probe.site(r.location());
                    String owner = s != null ? probe.symbolText(s.classSym()) : null;
                    String field = s != null && s.memberSym() != 0 ? probe.symbolText(s.memberSym()) : null;
                    if (owner != null && field != null) {
                        seed = r;
                        seedOwner = owner;
                        seedField = field;
                    }
                }
            }
            assumeTrue(seed != null, "recording has no resolvable FIELD_WRITE records");
            String file = Engine.outermostSimpleName(seedOwner) + ".java"; // §13.5 owner-file rule
            List<FieldWriteHit> hits = e.fieldWrites(file, seedField, 50);
            assertFalse(hits.isEmpty(), "fieldWrites must find the seed write");
            assertTrue(hits.size() <= 50, "fieldWrites must honour the cap");
            prevSeq = Long.MIN_VALUE;
            boolean containsSeed = false;
            for (FieldWriteHit h : hits) {
                assertTrue(h.seq() >= prevSeq, "fieldWrites must be ascending by seq");
                prevSeq = h.seq();
                assertEquals(seedField, h.fieldName());
                if (h.tid() == seed.tid() && h.index() == seed.index()) {
                    containsSeed = true;
                }
            }
            assertTrue(containsSeed, "fieldWrites must contain the seed write's exact (tid, index)");
        }
    }

    /**
     * Exercises the v1.6 {@code findSeq} (§16.7) against a real schema-4 recording (transfer-12):
     * a seq read from a real record via the probe must round-trip to that exact record's
     * coordinates, and the out-of-range sentinels {@code 0} and {@code Long.MAX_VALUE} must not
     * match. {@code findSeq} must never move the cursor.
     */
    @Test
    void findSeqOnRealRecording() throws IOException {
        assumeTrue(Files.isDirectory(REAL2), "real recording not present: " + REAL2);

        try (Engine e = Engine.open(REAL2);
                Recording probe = Recording.open(REAL2)) {
            assertEquals(4, e.schemaVersion(), "transfer-12 is a schema-4 recording");

            // Pick a known record from a middle thread/index via the probe, then locate its seq.
            List<Integer> tids = probe.threadIds();
            int someTid = tids.get(tids.size() / 2);
            long count = probe.recordCount(someTid);
            assumeTrue(count > 0, "chosen thread has records");
            long index = count / 2;
            long knownSeq = probe.recordAt(someTid, index).seq();

            SeqLocation loc = e.findSeq(knownSeq);
            assertTrue(loc.found(), "a real record's seq must be locatable");
            assertEquals(knownSeq, probe.recordAt(loc.tid(), loc.index()).seq(),
                    "findSeq must return coordinates whose record carries that seq");
            // seq is globally unique, so it round-trips to the very record we picked.
            assertEquals(someTid, loc.tid());
            assertEquals(index, loc.index());

            // Out-of-range sentinels never match (§16.7 test contract).
            assertFalse(e.findSeq(0L).found(), "seq 0 is below the first record");
            assertFalse(e.findSeq(Long.MAX_VALUE).found(), "Long.MAX_VALUE is above the last record");

            // findSeq must not move the cursor.
            int tid = e.currentTid();
            long ci = e.currentIndex();
            e.findSeq(knownSeq);
            e.findSeq(Long.MAX_VALUE);
            assertEquals(tid, e.currentTid());
            assertEquals(ci, e.currentIndex());
        }
    }

    private static int compareSites(final MethodSite a, final MethodSite b) {
        String ca = a.classBinary() == null ? "" : a.classBinary();
        String cb = b.classBinary() == null ? "" : b.classBinary();
        int c = ca.compareTo(cb);
        if (c != 0) {
            return c;
        }
        String ma = a.methodName() == null ? "" : a.methodName();
        String mb = b.methodName() == null ? "" : b.methodName();
        return ma.compareTo(mb);
    }

    private static boolean cursorIsOnLine(final Engine e, final Recording probe) throws IOException {
        return probe.recordAt(e.currentTid(), e.currentIndex()).type() == Format.LINE;
    }

    /** Whether the cursor rests on a §17.1 exec record — LINE, METHOD_ENTER or METHOD_EXIT. */
    private static boolean cursorIsOnExec(final Engine e, final Recording probe) throws IOException {
        return isExecType(probe.recordAt(e.currentTid(), e.currentIndex()).type());
    }

    private static boolean isExecType(final int type) {
        return type == Format.LINE || type == Format.METHOD_ENTER || type == Format.METHOD_EXIT;
    }
}
