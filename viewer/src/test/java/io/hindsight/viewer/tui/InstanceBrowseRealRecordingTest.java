// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.hindsight.viewer.engine.ClassSite;
import io.hindsight.viewer.engine.Engine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Self-skipping integration test of the §15.1 simple-name match rule against real
 * recordings (transfer-11 = schema 3, transfer-12 = schema 4), run only when they are
 * present on this machine. For every class in the recording that has inner-class
 * siblings, filtering by the class's SIMPLE name must return only instances whose
 * class's simple name actually contains it — an outer class's name must never drag in
 * {@code Outer$Inner} rows whose own simple name does not contain the query.
 */
final class InstanceBrowseRealRecordingTest {

    private static final Path BASE = Path.of(
            "/Users/ruslan.velkov/IdeaProjects/cloned/hiero-consensus-node/"
                    + "hedera-node/test-clients/build/hindsight/recordings");

    @Test
    void simpleNameQueriesReturnOnlySimpleNameMatchesSchema3() throws IOException {
        checkRecording(BASE.resolve("transfer-11"));
    }

    @Test
    void simpleNameQueriesReturnOnlySimpleNameMatchesSchema4() throws IOException {
        checkRecording(BASE.resolve("transfer-12"));
    }

    private static void checkRecording(final Path dir) throws IOException {
        assumeTrue(Files.isDirectory(dir), "real recording not present: " + dir);
        try (Engine e = Engine.open(dir)) {
            var sites = e.classSites();
            assumeTrue(!sites.isEmpty(), "recording has no first-seen classes");
            int checked = 0;
            for (ClassSite site : sites) {
                String binary = site.classBinary();
                if (binary == null || binary.indexOf('$') < 0 || checked >= 25) {
                    continue;
                }
                // Query by the OUTER class's simple name; identifiers only (safe as a regex).
                String outerSimple = InstanceBrowse.simpleSegment(binary.substring(0, binary.lastIndexOf('$')));
                if (outerSimple.isEmpty() || !outerSimple.matches("[A-Za-z0-9_]+")) {
                    continue;
                }
                checked++;
                InstanceBrowse.Result res = InstanceBrowse.filter(
                        sites, cls -> e.instancesOf(cls, InstanceBrowse.CAP), outerSimple, InstanceBrowse.CAP);
                String needle = outerSimple.toLowerCase(Locale.ROOT);
                for (InstanceBrowse.Row row : res.rows()) {
                    String simple = InstanceBrowse.simpleSegment(
                            row.classBinary() == null ? "" : row.classBinary());
                    assertTrue(simple.toLowerCase(Locale.ROOT).contains(needle),
                            "query '" + outerSimple + "' returned " + row.classBinary()
                                    + " whose simple name '" + simple + "' does not contain it (§15.1)");
                }
            }
            assumeTrue(checked > 0, "recording has no inner classes to exercise §15.1 against");
        }
    }
}
