// SPDX-License-Identifier: Apache-2.0
package io.hindsight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.agent.AgentConfig.Scope;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FORMAT.md §10: the agent argument grammar and the longest-prefix scope matching. */
class AgentConfigTest {

    @Test
    void parsesFullGrammar() {
        final AgentConfig c = AgentConfig.parse(
                "hooks=/tmp/hooks.jar,outDir=/tmp/rec,include=com.hedera.|com.swirlds.merkle.,"
                        + "callsOnly=com.swirlds.,exclude=com.hedera.internal.,autostart=false,"
                        + "trigger=com.hedera.node.App#main,maxString=64,snapshotEvery=0,"
                        + "recordBufKB=8,blobBufKB=4,maxTotalMB=100,debug=true");
        assertEquals("/tmp/hooks.jar", c.hooksJar);
        assertEquals("/tmp/rec", c.outDir);
        assertEquals(List.of("com.hedera.", "com.swirlds.merkle."), c.include);
        assertEquals(List.of("com.swirlds."), c.callsOnly);
        assertEquals(List.of("com.hedera.internal."), c.exclude);
        assertFalse(c.autostart);
        assertEquals("com.hedera.node.App", c.triggerClass);
        assertEquals("main", c.triggerMethod);
        assertEquals(64, c.maxString);
        assertEquals(0, c.snapshotEvery);
        assertEquals(8, c.recordBufKB);
        assertEquals(4, c.blobBufKB);
        assertEquals(100L, c.maxTotalMB);
        assertTrue(c.debug);
    }

    @Test
    void defaultsMatchSpecTable() {
        final AgentConfig c = AgentConfig.parse("hooks=h.jar,include=com.example.");
        assertEquals(".hindsight", c.outDir);
        assertTrue(c.autostart);
        assertNull(c.triggerClass);
        assertEquals(512, c.maxString);
        assertEquals(8192, c.snapshotEvery);
        assertEquals(256, c.recordBufKB);
        assertEquals(128, c.blobBufKB);
        assertEquals(204_800L, c.maxTotalMB);
        assertFalse(c.debug);
        assertTrue(c.callsOnly.isEmpty());
        assertTrue(c.exclude.isEmpty());
    }

    @Test
    void triggerImpliesAutostartFalseUnlessExplicit() {
        final AgentConfig implied = AgentConfig.parse("hooks=h.jar,include=a.,trigger=a.B#m");
        assertFalse(implied.autostart, "trigger implies autostart=false");

        final AgentConfig explicit = AgentConfig.parse("hooks=h.jar,include=a.,trigger=a.B#m,autostart=true");
        assertTrue(explicit.autostart, "explicit autostart wins over the trigger implication");
    }

    @Test
    void rejectsBadConfigsWithClearMessages() {
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse(null));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse(""));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("include=a.")); // no hooks
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("hooks=h.jar")); // no include
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("hooks=h.jar,include=a.,bogus=1"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("hooks=h.jar,include=a.,maxString=abc"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("hooks=h.jar,include=a.,autostart=yes"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("hooks=h.jar,include=a.,trigger=noHash"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("hooks=h.jar,include=a.,trigger=a.B#"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("hooks=h.jar,include=a.|"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("hooks=h.jar,include=a.,"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("hooks=h.jar,include=a.,hooks=x.jar"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("hooks=h.jar,include=a.,recordBufKB=0"));
    }

    @Test
    void longestPrefixWinsAcrossTheThreeLists() {
        final AgentConfig c = AgentConfig.parse(
                "hooks=h.jar,include=com.hedera.|com.swirlds.merkle.,callsOnly=com.swirlds.,"
                        + "exclude=com.hedera.internal.");
        assertEquals(Scope.FULL, c.scopeFor("com.hedera.node.app.Workflow"));
        assertEquals(Scope.NONE, c.scopeFor("com.hedera.internal.Secret"), "longer exclude beats include");
        assertEquals(Scope.CALLS_ONLY, c.scopeFor("com.swirlds.platform.Gossip"));
        assertEquals(Scope.FULL, c.scopeFor("com.swirlds.merkle.MerkleNode"), "longer include beats callsOnly");
        assertEquals(Scope.NONE, c.scopeFor("org.example.Other"), "no match means untouched");
    }

    @Test
    void exactTieIsResolvedTowardTheMoreRestrictiveScope() {
        final AgentConfig c = AgentConfig.parse("hooks=h.jar,include=com.a.,callsOnly=com.a.,exclude=com.b.");
        assertEquals(Scope.CALLS_ONLY, c.scopeFor("com.a.X"), "callsOnly wins an equal-length tie with include");

        final AgentConfig e = AgentConfig.parse("hooks=h.jar,include=com.b.,exclude=com.b.");
        assertEquals(Scope.NONE, e.scopeFor("com.b.X"), "exclude wins an equal-length tie");
    }

    @Test
    void hardIgnoresBeatAnyConfiguration() {
        final AgentConfig c = AgentConfig.parse(
                "hooks=h.jar,include=java.|io.hindsight.|net.bytebuddy.|jdk.|sun.|com.sun.|javax.");
        assertEquals(Scope.NONE, c.scopeFor("java.util.ArrayList"));
        assertEquals(Scope.NONE, c.scopeFor("javax.crypto.Cipher"));
        assertEquals(Scope.NONE, c.scopeFor("jdk.internal.reflect.GeneratedMethodAccessor1"));
        assertEquals(Scope.NONE, c.scopeFor("sun.misc.Unsafe"));
        assertEquals(Scope.NONE, c.scopeFor("com.sun.tools.javac.Main"));
        assertEquals(Scope.NONE, c.scopeFor("net.bytebuddy.ByteBuddy"));
        assertEquals(Scope.NONE, c.scopeFor("io.hindsight.hooks.MethodHooks"));
        assertEquals(Scope.NONE, c.scopeFor("io.hindsight.agent.HindsightAgent"));
        assertEquals(Scope.NONE, c.scopeFor("[Lcom.example.Thing;"), "array types are never instrumented");
        assertEquals(Scope.NONE, c.scopeFor("io.hindsight.other.Thing"),
                "the bare io.hindsight. umbrella include cannot beat the implicit exclude");
    }

    @Test
    void longerExplicitIncludeOverridesTheHindsightUmbrellaIgnore() {
        // Documented deviation from a literal §10 reading: io.hindsight. is an implicit
        // exclude, not an absolute ignore, so the project's own sample stays recordable.
        final AgentConfig c = AgentConfig.parse("hooks=h.jar,include=io.hindsight.sample.");
        assertEquals(Scope.FULL, c.scopeFor("io.hindsight.sample.Bank"));
        assertEquals(Scope.NONE, c.scopeFor("io.hindsight.other.Thing"));
        assertEquals(Scope.NONE, c.scopeFor("io.hindsight.hooks.MethodHooks"),
                "the recorder's own packages stay absolutely ignored");
        assertEquals(Scope.NONE, c.scopeFor("io.hindsight.agent.SiteRegistry"));
        assertEquals(Scope.NONE, c.scopeFor("io.hindsight.runtime.ThreadWorker"));
    }
}
