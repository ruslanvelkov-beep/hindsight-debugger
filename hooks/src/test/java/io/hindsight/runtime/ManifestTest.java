// SPDX-License-Identifier: Apache-2.0
package io.hindsight.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code manifest.properties} contents (FORMAT.md §13): boot the runtime against a temp
 * directory, then read the manifest that {@code writeManifest} produced at boot and assert the
 * keys landed. Focused on the schema-4 {@code sourceCommit} key (VIEWER.md §21.4) — the target
 * project's git HEAD echoed for the viewer, empty when unknown.
 */
class ManifestTest {

    @TempDir
    Path dir;

    @AfterEach
    void shutdown() {
        HindsightRuntime.shutdownForTests();
    }

    @Test
    void sourceCommitFromConfigIsWrittenToManifest() throws IOException {
        final String hash = "216551e1f8da95d50548c9a1448f367eb00697b2";
        final RuntimeConfig cfg = Recordings.config(dir);
        cfg.sourceCommit = hash;
        HindsightRuntime.boot(cfg);

        final Properties p = readManifest();
        assertEquals("4", p.getProperty("schema"), "sanity: schema key present");
        assertEquals(hash, p.getProperty("sourceCommit"), "§13: git HEAD echoed to the manifest");
    }

    @Test
    void sourceCommitDefaultsToEmptyKeyWhenUnknown() throws IOException {
        // Recordings.config leaves the RuntimeConfig default (empty) — mirrors a target with no .git.
        HindsightRuntime.boot(Recordings.config(dir));

        final Properties p = readManifest();
        assertNotNull(p.getProperty("sourceCommit"), "§13: the sourceCommit key is always written");
        assertEquals("", p.getProperty("sourceCommit"), "§13: empty when the source commit is unknown");
    }

    /** Reads the manifest from the runtime's resolved recording dir (captured while still booted). */
    private static Properties readManifest() throws IOException {
        final Path recordingDir = HindsightRuntime.recordingDir();
        assertNotNull(recordingDir, "runtime must be booted before reading the manifest");
        final Properties p = new Properties();
        try (InputStream in = Files.newInputStream(recordingDir.resolve("manifest.properties"))) {
            p.load(in);
        }
        return p;
    }
}
