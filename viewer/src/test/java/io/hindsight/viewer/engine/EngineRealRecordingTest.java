// SPDX-License-Identifier: Apache-2.0
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
 * stays on LINE records the whole time.
 */
final class EngineRealRecordingTest {

    private static final Path REAL = Path.of(
            "/Users/ruslan.velkov/IdeaProjects/cloned/hiero-consensus-node/"
                    + "hedera-node/test-clients/build/hindsight/recordings/transfer-11");

    @Test
    void stepsThroughRealRecordingWithoutThrowing() throws IOException {
        assumeTrue(Files.isDirectory(REAL), "real recording not present: " + REAL);

        try (Engine e = Engine.open(REAL);
                Recording probe = Recording.open(REAL)) {
            assertNotNull(e.threads());
            assertEquals(3, e.schemaVersion(), "transfer-11 is a schema-3 recording");
            assertTrue(cursorIsOnLine(e, probe), "initial cursor should sit on a LINE record");

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
                assertTrue(cursorIsOnLine(e, probe),
                        "stepInto must land on a LINE record (index " + e.currentIndex() + ")");
            }
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
                assertTrue(cursorIsOnLine(e, probe), "seek lands on a LINE record");
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
}
