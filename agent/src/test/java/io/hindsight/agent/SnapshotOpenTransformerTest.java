// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * FORMAT.md §10 eager boot-layer opens (recorder ≥ v1.5): the prefix-vs-package match rule
 * behind {@link SnapshotOpenTransformer#openBootLayerPackages()} —
 * {@code (pkg + ".").startsWith(prefix) || prefix.startsWith(pkg + ".")}. Only the rule is
 * unit-testable here: the actual {@code redefineModule} effect on {@code java.base} needs a
 * live {@code Instrumentation} from a real premain, which a plain unit-test JVM does not
 * have — the transfer-13 live recording validates that end to end.
 */
class SnapshotOpenTransformerTest {

    @Test
    void packagePrefixCoversThePackageItselfAndItsSubpackages() {
        final List<String> prefixes = List.of("java.time.");
        assertTrue(SnapshotOpenTransformer.packageMatches(prefixes, "java.time"),
                "java.time. covers the java.time package itself");
        assertTrue(SnapshotOpenTransformer.packageMatches(prefixes, "java.time.format"),
                "java.time. covers java.time.format");
    }

    @Test
    void classNamePrefixCoversItsOwnPackage() {
        assertTrue(SnapshotOpenTransformer.packageMatches(
                        List.of("com.example.proto.runtime.OneOf"), "com.example.proto.runtime"),
                "a class prefix covers the package the class lives in");
    }

    @Test
    void broadPrefixCoversNestedPackages() {
        assertTrue(SnapshotOpenTransformer.packageMatches(List.of("com.example."), "com.example.state.merkle"));
    }

    @Test
    void unrelatedPackagesStayOut() {
        final List<String> prefixes = List.of("java.time.", "com.example.proto.runtime.OneOf", "com.example.platform.");
        assertFalse(SnapshotOpenTransformer.packageMatches(prefixes, "java.util"));
        assertFalse(SnapshotOpenTransformer.packageMatches(prefixes, "java.timezone"),
                "a sibling package sharing a name prefix is NOT covered");
        assertFalse(SnapshotOpenTransformer.packageMatches(prefixes, "com.example.model.node"));
        assertFalse(SnapshotOpenTransformer.packageMatches(prefixes, "com.example.platformx"));
        assertFalse(SnapshotOpenTransformer.packageMatches(List.of(), "java.time"),
                "an empty prefix list matches nothing");
    }
}
