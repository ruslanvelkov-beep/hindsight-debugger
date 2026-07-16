// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.hindsight.reader.Format;
import io.hindsight.reader.Recording;
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
                    + "hedera-node/test-clients/build/hindsight/recordings/transfer-7");

    @Test
    void stepsThroughRealRecordingWithoutThrowing() throws IOException {
        assumeTrue(Files.isDirectory(REAL), "real recording not present: " + REAL);

        try (Engine e = Engine.open(REAL);
                Recording probe = Recording.open(REAL)) {
            assertNotNull(e.threads());
            assertTrue(cursorIsOnLine(e, probe), "initial cursor should sit on a LINE record");

            for (int i = 0; i < 100; i++) {
                // These must never throw on real data.
                List<Frame> stack = e.stack();
                assertNotNull(stack);
                for (int f = 0; f < stack.size(); f++) {
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
