// SPDX-License-Identifier: Apache-2.0
package io.hindsight.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Full-stack integration: records {@code BankSim} under the real agent, then
 * opens the result with this reader and asserts SEMANTIC facts about the
 * recording. Assertions are existence/consistency style (never exact counts
 * beyond coarse floors) because thread scheduling varies run to run.
 *
 * <p>Skips itself unless the orchestrating build passes the four jar/JVM system
 * properties (see {@code reader/build.gradle.kts}) and those files exist — so
 * the unit-test suite stays runnable while the agent is still being built.
 */
final class EndToEndRecordingTest {

    @TempDir
    Path tmp;

    private static Path propPath(final String key) {
        String v = System.getProperty(key);
        return v == null || v.isBlank() ? null : Path.of(v);
    }

    @Test
    void recordedBankSimSurvivesVerifyAndContainsExpectedEvents() throws Exception {
        Path agentJar = propPath("hindsight.agentJar");
        Path hooksJar = propPath("hindsight.hooksJar");
        Path sampleJar = propPath("hindsight.sampleJar");
        Path javaHome = propPath("hindsight.javaHome");
        assumeTrue(agentJar != null && hooksJar != null && sampleJar != null && javaHome != null,
                "agent/hooks/sample jar system properties not set — skipping end-to-end test");
        assumeTrue(Files.exists(agentJar) && Files.exists(hooksJar) && Files.exists(sampleJar),
                "agent/hooks/sample jars not built yet — skipping end-to-end test");
        Path javaBin = javaHome.resolve("bin").resolve("java");
        assumeTrue(Files.exists(javaBin), "no java launcher under " + javaHome);

        Path outDir = tmp.resolve("rec");
        // Redirect to files rather than pumping pipes: no reader threads, no risk of a
        // blocked readAllBytes masking a hung JVM — waitFor(timeout) stays in control.
        Path outLog = tmp.resolve("sample-stdout.log");
        Path errLog = tmp.resolve("sample-stderr.log");
        Process p = new ProcessBuilder(
                        javaBin.toString(),
                        "-javaagent:" + agentJar + "=hooks=" + hooksJar + ",outDir=" + outDir
                                + ",include=io.hindsight.sample.,autostart=true,snapshotEvery=64,debug=true",
                        "-jar",
                        sampleJar.toString())
                .redirectOutput(outLog.toFile())
                .redirectError(errLog.toFile())
                .start();
        boolean finished = p.waitFor(180, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
        }
        String stdout = Files.exists(outLog) ? Files.readString(outLog, StandardCharsets.UTF_8) : "";
        String stderr = Files.exists(errLog) ? Files.readString(errLog, StandardCharsets.UTF_8) : "";
        String diag = "\n--- sample stdout ---\n" + stdout + "\n--- sample stderr ---\n" + stderr;
        assertTrue(finished, "sample JVM did not finish in time" + diag);
        assertEquals(0, p.exitValue(), "sample JVM failed" + diag);
        assertTrue(stdout.contains("BANKSIM OK"), "sample lost money" + diag);

        // The agent uses a "-<n>" sibling if outDir existed; it did not, so outDir is it.
        assertTrue(Files.isDirectory(outDir), "recording directory missing" + diag);

        TestCli.Result verify = TestCli.run("verify", "--dir", outDir.toString());
        assertEquals(0, verify.code(), "verify violations:" + verify.out() + diag);

        try (Recording rec = Recording.open(outDir)) {
            assertSemantics(rec, diag);
        }
    }

    private void assertSemantics(final Recording rec, final String diag) throws IOException {
        long enters = 0;
        long snapshots = 0;
        long stackSnapshots = 0;
        long parsedCaptures = 0;
        long exceptionalExits = 0;
        boolean transferArgsSeen = false;
        boolean balanceWriteConsistent = false;
        boolean localWithName = false;
        Set<String> strings = new HashSet<>();

        RecordCursor cur = rec.cursor();
        Rec prevEnter = null;
        int argsAfterTransferEnter = 0;
        List<Integer> transferArgKinds = new java.util.ArrayList<>();
        while (cur.hasNext()) {
            Rec r = cur.next();
            switch (r.type()) {
                case Format.METHOD_ENTER -> {
                    enters++;
                    Site s = rec.site(r.location());
                    if (s != null && "transfer".equals(rec.symbolText(s.memberSym()))
                            && !transferArgsSeen) {
                        prevEnter = r;
                        argsAfterTransferEnter = r.idx();
                        transferArgKinds.clear();
                    }
                }
                case Format.ARG -> {
                    if (prevEnter != null && r.tid() == prevEnter.tid()
                            && r.location() == prevEnter.location()
                            && transferArgKinds.size() < argsAfterTransferEnter) {
                        transferArgKinds.add(r.afterKind());
                        if (transferArgKinds.size() == argsAfterTransferEnter) {
                            // Bank.transfer(String, String, long): two strings (BLOB) + inline long
                            transferArgsSeen = argsAfterTransferEnter == 3
                                    && transferArgKinds.get(0) == Format.KIND_BLOB
                                    && transferArgKinds.get(1) == Format.KIND_BLOB
                                    && transferArgKinds.get(2) == Format.KIND_INLINE;
                            prevEnter = null;
                        }
                    }
                    if (r.afterKind() == Format.KIND_BLOB && r.tag() == Format.TAG_STRING) {
                        strings.add(rec.utf8At(r.tid(), r.after()));
                    }
                }
                case Format.METHOD_EXIT -> {
                    if (r.isExceptional()) {
                        exceptionalExits++;
                    }
                }
                case Format.FIELD_WRITE -> {
                    Site s = rec.site(r.location());
                    if (s != null && "balance".equals(rec.symbolText(s.memberSym()))
                            && r.beforeKind() == Format.KIND_INLINE
                            && r.afterKind() == Format.KIND_INLINE
                            && r.tag() == Format.TAG_LONG
                            && r.before() != r.after()
                            && r.target() != 0) {
                        balanceWriteConsistent = true;
                    }
                }
                case Format.LOCAL_WRITE -> {
                    Site s = rec.site(r.location());
                    if (s != null) {
                        String name = rec.symbolText(s.memberSym());
                        if (name != null && !name.isBlank()) {
                            localWithName = true;
                        }
                    }
                }
                case Format.SNAPSHOT -> snapshots++;
                case Format.STACK_SNAPSHOT -> {
                    stackSnapshots++;
                    if (parsedCaptures < 5) { // spot-check a few payloads end-to-end
                        rec.stackAt(r.tid(), r.after());
                        parsedCaptures++;
                    }
                }
                default -> {
                    // THREAD_START / TIME_SYNC / OBJECT_FIRST_SEEN — covered by verify
                }
            }
        }

        assertTrue(enters > 1000, "expected >1000 METHOD_ENTERs, got " + enters + diag);
        assertTrue(transferArgsSeen,
                "no transfer ENTER with (BLOB string, BLOB string, INLINE long) args" + diag);
        assertTrue(balanceWriteConsistent,
                "no balance FIELD_WRITE with consistent differing inline longs" + diag);
        assertTrue(localWithName, "no LOCAL_WRITE with a resolvable variable name" + diag);
        assertTrue(exceptionalExits >= 1, "expected at least one EXCEPTIONAL exit" + diag);
        assertTrue(snapshots >= 1, "expected at least one SNAPSHOT (snapshotEvery=64)" + diag);
        assertEquals(4, rec.schemaVersion(), "the recorder writes schema 4" + diag);
        assertTrue(stackSnapshots >= 1,
                "expected STACK_SNAPSHOT captures (stackEvery defaults to 1024)" + diag);
        assertTrue(parsedCaptures >= 1, "expected at least one parsable capture payload" + diag);
        assertTrue(strings.stream().anyMatch(s -> s.contains("alice") || s.contains("carol")),
                "expected account names among recorded strings, got " + strings + diag);
    }
}
