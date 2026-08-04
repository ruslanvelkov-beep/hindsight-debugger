// SPDX-License-Identifier: Apache-2.0
package io.hindsight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.runtime.Wire;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;

/**
 * End-to-end flow instrumentation (§7): LINE records at every source line and BRANCH records
 * at conditional jumps, asserted from the bytes of a recording produced by running the real
 * fixture through the production visitor pass. The fixtures load through a throwaway
 * classloader, so any stack-map-frame damage from the splices surfaces here as a VerifyError.
 */
final class FlowInstrumentationTest {

    private static final String FIXTURE = "io.hindsight.agent.fixtures.Fixture";

    @TempDir
    Path tmp;

    @Test
    void singleOperandBranchRecordsOperandAndDerivesResult() throws Exception {
        final Recorded rec;
        try (TestBed bed = TestBed.start(tmp, AgentConfig.Scope.FULL, FIXTURE)) {
            // recurse: `if (n <= 0)` compiles to IFGT n (jump to the recursive body when n > 0).
            bed.invokeStatic(FIXTURE, "recurse", 3);
            rec = bed.stopAndParse();
        }

        final List<Recorded.Rec> branches = branchesWithOpcode(rec, Opcodes.IFGT);
        assertFalse(branches.isEmpty(), "recurse's `n <= 0` test should emit BRANCH records");
        final List<Long> operands = new ArrayList<>();
        for (final Recorded.Rec b : branches) {
            assertEquals(Wire.TAG_INT, b.tag(), "single int-operand branch carries the value");
            assertEquals(Wire.KIND_INLINE, b.afterKind());
            operands.add(b.after());
        }
        // recurse(3) descends 3,2,1,0; each level tests n against 0.
        assertTrue(operands.contains(3L) && operands.contains(0L), "operands: " + operands);
    }

    @Test
    void twoOperandBranchRecordsBothOperands() throws Exception {
        final Recorded rec;
        try (TestBed bed = TestBed.start(tmp, AgentConfig.Scope.FULL, FIXTURE)) {
            // locals(): `for (int i = 0; i < 3; i++)` compiles to a top-tested loop —
            // IF_ICMPGE i, 3 (jump to the loop exit once i >= 3).
            bed.invokeStatic(FIXTURE, "locals");
            rec = bed.stopAndParse();
        }

        final List<Recorded.Rec> cmp = branchesWithOpcode(rec, Opcodes.IF_ICMPGE);
        assertFalse(cmp.isEmpty(), "the for-loop condition should emit two-operand BRANCH records");
        boolean sawContinue = false;
        boolean sawExit = false;
        for (final Recorded.Rec b : cmp) {
            assertEquals(Wire.KIND_INLINE, b.beforeKind(), "operand a inline");
            assertEquals(Wire.KIND_INLINE, b.afterKind(), "operand b inline");
            assertEquals(3L, b.after(), "loop bound is 3");
            if (b.before() < 3) {
                sawContinue = true; // i < 3 held
            }
            if (b.before() == 3) {
                sawExit = true; // i == 3 ended the loop
            }
        }
        assertTrue(sawContinue && sawExit, "should see both continuing and terminating iterations");
    }

    @Test
    void lineRecordsReferenceLineSitesAndFollowFlow() throws Exception {
        final Recorded rec;
        try (TestBed bed = TestBed.start(tmp, AgentConfig.Scope.FULL, FIXTURE)) {
            bed.invokeStatic(FIXTURE, "recurse", 2);
            rec = bed.stopAndParse();
        }

        final List<Recorded.Rec> lineRecs = rec.ofType(Wire.TYPE_LINE);
        assertFalse(lineRecs.isEmpty(), "flow recording should contain LINE records");
        for (final Recorded.Rec r : lineRecs) {
            final Recorded.Site s = rec.sites.get(r.location());
            assertTrue(s != null && "LINE".equals(s.kind()), "LINE record must reference a LINE site");
            assertTrue(s.line() > 0, "LINE site carries a source line");
        }
        // recurse(2) re-executes the method body across 3 activations, so its lines repeat —
        // the flow signal the feature exists to provide.
        assertTrue(lineRecs.size() >= 3, "line executions should repeat across recursion; got " + lineRecs.size());
    }

    @Test
    void newWithBranchInCtorArgsSurvivesInstrumentation() throws Exception {
        // Regression: the LINE splice must never land between a NEW's label and the NEW —
        // frames' Uninitialized entries reference that offset, and the class fails to define
        // ("StackMapTable format error: bad offset for Uninitialized"). Loading the fixture
        // through the TestBed classloader surfaces exactly that error.
        try (TestBed bed = TestBed.start(tmp, AgentConfig.Scope.FULL, FIXTURE)) {
            assertEquals("yes", bed.invokeStatic(FIXTURE, "conditionalCtorArg", true));
            assertEquals("no", bed.invokeStatic(FIXTURE, "conditionalCtorArg", false));
            final Recorded rec = bed.stopAndParse();
            assertFalse(rec.ofType(Wire.TYPE_LINE).isEmpty(), "line flow still recorded around the NEW");
        }
    }

    @Test
    void branchScopeRecordsBranchesLocalsAndFieldsButNoLines() throws Exception {
        // §11 branchOnly: branch-resolution mode — everything FULL records except LINE.
        final Recorded rec;
        try (TestBed bed = TestBed.start(tmp, AgentConfig.Scope.BRANCH, FIXTURE)) {
            bed.invokeStatic(FIXTURE, "recurse", 2);
            bed.invokeStatic(FIXTURE, "locals");
            rec = bed.stopAndParse();
        }
        assertTrue(rec.ofType(Wire.TYPE_LINE).isEmpty(), "branch scope emits NO LINE records");
        assertFalse(rec.ofType(Wire.TYPE_BRANCH).isEmpty(), "branches stay recorded");
        assertFalse(rec.ofType(Wire.TYPE_LOCAL_WRITE).isEmpty(), "local transitions stay recorded");
        assertFalse(rec.ofType(Wire.TYPE_METHOD_ENTER).isEmpty(), "brackets stay recorded");
        // The BRANCH sites keep their correct source lines (the line bookkeeping stays live
        // even though LINE splicing is suppressed).
        for (final Recorded.Rec b : rec.ofType(Wire.TYPE_BRANCH)) {
            final Recorded.Site site = rec.sites.get(b.location());
            assertTrue(site != null && site.line() > 0, "BRANCH site carries its source line");
        }
    }

    @Test
    void branchesDisabledEmitsNoBranchRecords() throws Exception {
        final Recorded rec;
        try (TestBed bed = TestBed.startWithConfig(
                tmp, AgentConfig.Scope.FULL,
                "hooks=unused.jar,include=io.hindsight.agent.fixtures.,branches=false", FIXTURE)) {
            bed.invokeStatic(FIXTURE, "recurse", 2);
            rec = bed.stopAndParse();
        }
        assertTrue(rec.ofType(Wire.TYPE_BRANCH).isEmpty(), "branches=false suppresses BRANCH records");
        assertFalse(rec.ofType(Wire.TYPE_LINE).isEmpty(), "lines stay on by default");
    }

    private static List<Recorded.Rec> branchesWithOpcode(final Recorded rec, final int opcode) {
        final List<Recorded.Rec> out = new ArrayList<>();
        for (final Recorded.Rec r : rec.ofType(Wire.TYPE_BRANCH)) {
            final Recorded.Site s = rec.sites.get(r.location());
            assertTrue(s != null && "BRANCH".equals(s.kind()), "BRANCH record must reference a BRANCH site");
            if (s.slot() == opcode) {
                out.add(r);
            }
        }
        return out;
    }
}
