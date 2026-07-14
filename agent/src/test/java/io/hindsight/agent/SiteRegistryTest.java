// SPDX-License-Identifier: Apache-2.0
package io.hindsight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.hooks.MethodHooks;
import io.hindsight.runtime.HindsightRuntime;
import io.hindsight.runtime.RuntimeConfig;
import io.hindsight.runtime.TestRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FORMAT.md §4–§5: symbol/site TSV layout, dedup rules, escaping, trigger wiring. */
class SiteRegistryTest {

    @TempDir
    Path dir;

    @Test
    void methodSitesAreIdempotentAcrossEnterAndExitResolution() throws IOException {
        try (SiteRegistry registry = new SiteRegistry(dir, null, null, null)) {
            final int enter = registry.methodSite("com.example.Svc", "work", "(I)V", false);
            final int exit = registry.methodSite("com.example.Svc", "work", "(I)V", false);
            assertEquals(enter, exit, "enter and exit advice must resolve to one site id");

            final int overload = registry.methodSite("com.example.Svc", "work", "(J)V", false);
            assertNotEquals(enter, overload, "different descriptor, different site");
            final int staticVariant = registry.methodSite("com.example.Svc", "work", "(I)V", true);
            assertNotEquals(enter, staticVariant, "static bit distinguishes sites");
        }
    }

    @Test
    void fieldAndLocalSitesAreOnePerInstruction() throws IOException {
        try (SiteRegistry registry = new SiteRegistry(dir, null, null, null)) {
            final int first = registry.fieldSite("com.example.Owner", "count", "I", false, "Owner.java", 10);
            final int second = registry.fieldSite("com.example.Owner", "count", "I", false, "Owner.java", 10);
            assertNotEquals(first, second, "each write instruction gets its own FIELD site");

            final int localA = registry.localSite("com.example.Svc", "total", "I", 2, "Svc.java", 5);
            final int localB = registry.localSite("com.example.Svc", "total", "I", 2, "Svc.java", 9);
            assertNotEquals(localA, localB, "each store instruction gets its own LOCAL site");
        }
    }

    @Test
    void tsvRowsAreDenseEscapedAndSpecShaped() throws IOException {
        try (SiteRegistry registry = new SiteRegistry(dir, null, null, null)) {
            registry.beginClassScan("com.example.Svc", "Svc.java", Map.of("work(I)V", 42));
            registry.methodSite("com.example.Svc", "work", "(I)V", true);
            registry.endClassScan();
            registry.fieldSite("com.example.Owner", "count", "I", true, "Svc.java", 10);
            registry.localSite("com.example.Svc", "odd\tname\\x", "Ljava/lang/String;", 300, "Svc.java", 11);
        }

        final Recorded parsed = Recorded.parse(dir);
        // Dense ids from 1, in file order.
        final List<String> lines = Files.readAllLines(dir.resolve("sites.tsv"), StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        for (int i = 0; i < lines.size(); i++) {
            assertTrue(lines.get(i).startsWith((i + 1) + "\t"), "site ids dense from 1: " + lines.get(i));
        }

        final Recorded.Site method = parsed.sites.get(1);
        assertEquals("METHOD", method.kind());
        assertEquals("com.example.Svc", parsed.symbolText(method.classSym()));
        assertEquals("Svc.java", parsed.symbolText(method.fileSym()));
        assertEquals(42, method.line(), "first body line from the class scan");
        assertEquals("work", parsed.symbolText(method.memberSym()));
        assertEquals("(I)V#s", method.desc(), "static suffix on the descriptor");
        assertEquals(-1, method.slot());

        final Recorded.Site field = parsed.sites.get(2);
        assertEquals("FIELD", field.kind());
        assertEquals("com.example.Owner", parsed.symbolText(field.classSym()), "owner class, not instrumented class");
        assertEquals("I#s", field.desc());
        assertEquals(10, field.line());
        assertEquals(-1, field.slot());

        final Recorded.Site local = parsed.sites.get(3);
        assertEquals("LOCAL", local.kind());
        assertEquals("odd\tname\\x", parsed.symbolText(local.memberSym()), "escaping round-trips");
        assertEquals(300, local.slot(), "slots beyond the record idx cap live in the site table");

        // Raw bytes: the escaped var name must not contain a literal tab inside the field.
        boolean sawEscaped = false;
        for (final String line : Files.readAllLines(dir.resolve("symbols.tsv"), StandardCharsets.UTF_8)) {
            if (line.contains("odd\\tname\\\\x")) {
                sawEscaped = true;
            }
        }
        assertTrue(sawEscaped, "tab and backslash are escaped on disk per §4");
    }

    @Test
    void symbolsAreDeduplicatedAcrossSites() throws IOException {
        try (SiteRegistry registry = new SiteRegistry(dir, null, null, null)) {
            registry.methodSite("com.example.Svc", "a", "()V", false);
            registry.methodSite("com.example.Svc", "b", "()V", false);
            registry.fieldSite("com.example.Svc", "f", "I", false, null, 1);
        }
        int classSymbols = 0;
        for (final String line : Files.readAllLines(dir.resolve("symbols.tsv"), StandardCharsets.UTF_8)) {
            if (line.contains("\tCLASS\tcom.example.Svc")) {
                classSymbols++;
            }
        }
        assertEquals(1, classSymbols, "one CLASS symbol row regardless of how many sites use it");
    }

    @Test
    void triggerSiteBindingStartsRecordingOnEnter() throws IOException {
        final RuntimeConfig cfg = new RuntimeConfig();
        cfg.outDir = dir.resolve("recording");
        cfg.autostart = false;
        cfg.snapshotEvery = 0;
        HindsightRuntime.boot(cfg);
        try (SiteRegistry registry =
                new SiteRegistry(HindsightRuntime.recordingDir(), "com.example.App", "main", null)) {
            final int other = registry.methodSite("com.example.App", "helper", "()V", true);
            final int trigger = registry.methodSite("com.example.App", "main", "([Ljava/lang/String;)V", true);

            assertFalse(HindsightRuntime.isRecording());
            MethodHooks.enter(other, null, new Object[0]);
            assertFalse(HindsightRuntime.isRecording(), "non-trigger sites must not start recording");
            MethodHooks.enter(trigger, null, new Object[] {"argv"});
            assertTrue(HindsightRuntime.isRecording(), "the trigger site's ENTER opens the gate");
        } finally {
            TestRuntime.shutdown();
        }
    }
}
