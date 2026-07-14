// SPDX-License-Identifier: Apache-2.0
package io.hindsight.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.hooks.MethodHooks;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** §8 trigger semantics: the trigger-site ENTER opens the gate and is itself recorded. */
class TriggerTest {

    private static final int TRIGGER_SITE = 500;

    @TempDir
    Path dir;

    @AfterEach
    void shutdown() {
        HindsightRuntime.shutdownForTests();
    }

    @Test
    void triggerSiteStartsRecordingAndRecordsThatEnter() throws IOException {
        final RuntimeConfig cfg = Recordings.config(dir);
        cfg.autostart = false;
        HindsightRuntime.boot(cfg);
        HindsightRuntime.setTriggerSite(TRIGGER_SITE);
        assertFalse(HindsightRuntime.isRecording());

        MethodHooks.enter(1, null, new Object[0]); // not the trigger: must record nothing
        assertFalse(HindsightRuntime.isRecording());

        MethodHooks.enter(TRIGGER_SITE, null, new Object[] {"first"});
        assertTrue(HindsightRuntime.isRecording(), "trigger ENTER must open the gate");
        MethodHooks.exit(TRIGGER_SITE, null, Wire.TAG_VOID);

        HindsightRuntime.shutdownForTests();
        final List<Recordings.Rec> recs = Recordings.parseAll(dir);

        boolean sawTriggerEnter = false;
        for (final Recordings.Rec r : recs) {
            assertFalse(r.type() == Wire.TYPE_METHOD_ENTER && r.location() == 1,
                    "pre-trigger ENTER must not be recorded");
            if (r.type() == Wire.TYPE_METHOD_ENTER && r.location() == TRIGGER_SITE) {
                sawTriggerEnter = true;
                assertEquals(1, r.idx(), "arg count of the trigger ENTER");
            }
        }
        assertTrue(sawTriggerEnter, "the triggering ENTER is the first business record");
    }

    @Test
    void autostartFalseWithoutTriggerRecordsNothing() throws IOException {
        final RuntimeConfig cfg = Recordings.config(dir);
        cfg.autostart = false;
        HindsightRuntime.boot(cfg);

        MethodHooks.enter(1, null, new Object[0]);
        MethodHooks.exit(1, null, Wire.TAG_VOID);

        HindsightRuntime.shutdownForTests();
        assertTrue(Recordings.recordFiles(dir).isEmpty(), "no thread should have recorded anything");
    }
}
