// SPDX-License-Identifier: GPL-3.0-or-later
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
                "hooks=/tmp/hooks.jar,outDir=/tmp/rec,include=com.example.|com.example.merkle.,"
                        + "callsOnly=com.example.platform.,exclude=com.example.internal.,"
                        + "snapshotOpen=com.example.model.|com.example.proto.,"
                        + "snapshotAlways=com.example.proto.runtime.OneOf,autostart=false,"
                        + "trigger=com.example.node.App#main,maxString=64,snapshotEvery=0,snapshotDepth=3,"
                        + "stackEvery=256,recordBufKB=8,blobBufKB=4,maxTotalMB=100,debug=true,"
                        + "sourceCommit=216551e1f8da95d50548c9a1448f367eb00697b2");
        assertEquals("/tmp/hooks.jar", c.hooksJar);
        assertEquals("/tmp/rec", c.outDir);
        assertEquals(List.of("com.example.", "com.example.merkle."), c.include);
        assertEquals(List.of("com.example.platform."), c.callsOnly);
        assertEquals(List.of("com.example.internal."), c.exclude);
        assertEquals(List.of("com.example.model.", "com.example.proto."), c.snapshotOpen);
        assertEquals(List.of("com.example.proto.runtime.OneOf"), c.snapshotAlways);
        assertFalse(c.autostart);
        assertEquals("com.example.node.App", c.triggerClass);
        assertEquals("main", c.triggerMethod);
        assertEquals(64, c.maxString);
        assertEquals(0, c.snapshotEvery);
        assertEquals(3, c.snapshotDepth);
        assertEquals(256, c.stackEvery);
        assertEquals(8, c.recordBufKB);
        assertEquals(4, c.blobBufKB);
        assertEquals(100L, c.maxTotalMB);
        assertTrue(c.debug);
        assertEquals("216551e1f8da95d50548c9a1448f367eb00697b2", c.sourceCommit);
    }

    @Test
    void defaultsMatchSpecTable() {
        final AgentConfig c = AgentConfig.parse("hooks=h.jar,include=com.example.");
        assertEquals(".hindsight", c.outDir);
        assertEquals("", c.sourceCommit, "§13: sourceCommit defaults to empty when absent");
        assertTrue(c.autostart);
        assertNull(c.triggerClass);
        assertEquals(512, c.maxString);
        assertEquals(8192, c.snapshotEvery);
        assertEquals(2, c.snapshotDepth, "§10: snapshotDepth defaults to 2");
        assertEquals(1024, c.stackEvery, "§10: stackEvery defaults to 1024");
        assertEquals(256, c.recordBufKB);
        assertEquals(128, c.blobBufKB);
        assertEquals(204_800L, c.maxTotalMB);
        assertFalse(c.debug);
        assertTrue(c.callsOnly.isEmpty());
        assertTrue(c.exclude.isEmpty());
        assertTrue(c.snapshotOpen.isEmpty(), "§10: snapshotOpen defaults to empty");
        assertTrue(c.snapshotAlways.isEmpty(), "§10: snapshotAlways defaults to empty");
    }

    @Test
    void sourceCommitAppearsInDescribe() {
        final String hash = "216551e1f8da95d50548c9a1448f367eb00697b2";
        final AgentConfig c = AgentConfig.parse("hooks=h.jar,include=a.,sourceCommit=" + hash);
        assertEquals(hash, c.sourceCommit);
        assertTrue(c.describe().contains("sourceCommit=" + hash),
                "§21.4: describe() must echo the captured source commit for agent.log");
    }

    @Test
    void snapshotOpenPrefixMatchingIsPlainStartsWith() {
        final List<String> prefixes = List.of("com.example.model.", "com.example.proto.");
        assertTrue(SnapshotOpenTransformer.matches(prefixes, "com.example.model.node.base.AccountID"));
        assertTrue(SnapshotOpenTransformer.matches(prefixes, "com.example.proto.runtime.OneOf"));
        assertFalse(SnapshotOpenTransformer.matches(prefixes, "com.example.app.workflows.IngestWorkflow"));
        assertFalse(SnapshotOpenTransformer.matches(List.of(), "com.example.model.node.base.AccountID"));
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
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfig.parse("hooks=h.jar,include=a.,snapshotDepth=0"), "snapshotDepth must be >= 1");
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfig.parse("hooks=h.jar,include=a.,snapshotDepth=deep"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfig.parse("hooks=h.jar,include=a.,stackEvery=-1"), "stackEvery must be >= 0");
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfig.parse("hooks=h.jar,include=a.,stackEvery=often"));
    }

    @Test
    void longestPrefixWinsAcrossTheThreeLists() {
        final AgentConfig c = AgentConfig.parse(
                "hooks=h.jar,include=com.example.|com.example.merkle.,callsOnly=com.example.platform.,"
                        + "exclude=com.example.internal.");
        assertEquals(Scope.FULL, c.scopeFor("com.example.app.Workflow"));
        assertEquals(Scope.NONE, c.scopeFor("com.example.internal.Secret"), "longer exclude beats include");
        assertEquals(Scope.CALLS_ONLY, c.scopeFor("com.example.platform.Gossip"));
        assertEquals(Scope.FULL, c.scopeFor("com.example.merkle.MerkleNode"), "longer include beats callsOnly");
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
    void branchOnlyScopesByLongestPrefixAndRestrictiveTies() {
        // §10/§11 branchOnly: between include (FULL) and callsOnly in restrictiveness.
        final AgentConfig c = AgentConfig.parse(
                "hooks=h.jar,include=com.app.,branchOnly=com.app.engine.,callsOnly=com.app.engine.gossip.");
        assertEquals(Scope.FULL, c.scopeFor("com.app.Workflow"));
        assertEquals(Scope.BRANCH, c.scopeFor("com.app.engine.Round"), "longer branchOnly beats include");
        assertEquals(Scope.CALLS_ONLY, c.scopeFor("com.app.engine.gossip.Peer"),
                "longer callsOnly beats branchOnly");

        final AgentConfig tie = AgentConfig.parse("hooks=h.jar,include=com.a.,branchOnly=com.a.");
        assertEquals(Scope.BRANCH, tie.scopeFor("com.a.X"), "branchOnly wins an equal-length tie with include");

        final AgentConfig callsTie = AgentConfig.parse(
                "hooks=h.jar,include=com.a.,branchOnly=com.a.,callsOnly=com.a.");
        assertEquals(Scope.CALLS_ONLY, callsTie.scopeFor("com.a.X"),
                "callsOnly wins an equal-length tie with branchOnly");

        final AgentConfig excl = AgentConfig.parse("hooks=h.jar,include=x.,branchOnly=com.b.,exclude=com.b.");
        assertEquals(Scope.NONE, excl.scopeFor("com.b.X"), "exclude wins over branchOnly");
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
