// SPDX-License-Identifier: Apache-2.0
package io.hindsight.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed and validated agent arguments per the FORMAT.md §10 grammar.
 *
 * <p>The grammar is deliberately primitive — top-level {@code ','} between {@code key=value}
 * pairs, {@code '|'} between list elements — because agent argument strings pass through
 * shells, launchd plists and Gradle test configs where anything smarter gets mangled. Parsing
 * is strict and fails fast with a message naming the offending key: a mis-typed agent config
 * silently recording the wrong classes is far worse than a JVM that refuses to start.
 *
 * <p>Class-name matching implements §10's longest-prefix-wins rule across the three scope
 * lists. Where two lists contain a prefix of the same (winning) length the more restrictive
 * scope wins: {@code exclude} over {@code callsOnly} over {@code include} — the spec only
 * fixes the {@code exclude} half of that ordering, the rest is this implementation's
 * conservative tie-break.
 *
 * <p>Hard ignores: the recorder's own packages ({@code io.hindsight.hooks.} etc.), the JDK
 * namespaces and ByteBuddy are ignored absolutely — no configuration can make the agent
 * instrument its own machinery and recurse. One documented deviation from a literal §10
 * reading: the umbrella prefix {@code io.hindsight.} is treated as an <em>implicit exclude</em>
 * that a strictly longer include/callsOnly prefix may override, rather than an absolute
 * ignore. A literal reading would make the project's own {@code io.hindsight.sample.} demo
 * (and the reader module's full-stack test, which records it) permanently unrecordable, while
 * the rule's evident intent — self-protection of the recorder — is fully preserved by the
 * absolute internal prefixes.
 */
public final class AgentConfig {

    /** Instrumentation scope of one class, decided by name per §10. */
    public enum Scope {
        /** Not instrumented at all. */
        NONE,
        /** Method brackets + args only (§11 callsOnly). */
        CALLS_ONLY,
        /** Brackets + args + locals + fields (§11 include). */
        FULL
    }

    /** §10: never instrumented, regardless of include/callsOnly lists (see class doc). */
    private static final String[] HARD_IGNORED_PREFIXES = {
        "io.hindsight.hooks.",
        "io.hindsight.runtime.",
        "io.hindsight.agent.",
        "io.hindsight.reader.",
        "java.",
        "javax.",
        "jdk.",
        "sun.",
        "com.sun.",
        "net.bytebuddy.",
    };

    /** §10 umbrella prefix, applied as an implicit exclude a longer include may override. */
    private static final String SOFT_IGNORED_PREFIX = "io.hindsight.";

    /** Path to the hooks jar to append to the bootstrap search (required). */
    public final String hooksJar;

    /** Requested recording directory (§1 may substitute a sibling). */
    public final String outDir;

    /** Binary-name prefixes receiving FULL instrumentation. */
    public final List<String> include;

    /** Binary-name prefixes receiving brackets + args only. */
    public final List<String> callsOnly;

    /** Binary-name prefixes excluded entirely; wins over the other lists. */
    public final List<String> exclude;

    /**
     * Binary-name prefixes whose packages are OPENED to the hooks module for reflective
     * snapshots without being instrumented (§10 {@code snapshotOpen}) — data/model classes
     * (PBJ records and the like) that are deliberately outside the instrumentation scope but
     * whose field contents the §7 dumps must be able to read.
     */
    public final List<String> snapshotOpen;

    /**
     * Binary-name prefixes always baseline-dumped at first sight regardless of
     * {@code snapshotDepth} (§7/§10 {@code snapshotAlways}) — small value carriers (PBJ's
     * {@code OneOf}) that appear arbitrarily deep in object graphs.
     */
    public final List<String> snapshotAlways;

    /** Start recording at premain; defaults false when a trigger is configured. */
    public final boolean autostart;

    /** Trigger class binary name, or {@code null} when no trigger is configured. */
    public final String triggerClass;

    /** Trigger method bare name, or {@code null} when no trigger is configured. */
    public final String triggerMethod;

    /** Max recorded string length in chars. */
    public final int maxString;

    /** Shallow-snapshot sampling divisor; 0 disables (§7). */
    public final int snapshotEvery;

    /** Baseline snapshot of every object at first sight (§7). */
    public final boolean snapshotFirstSeen;

    /** Nested-baseline snapshot depth (§7); must be ≥ 1, and 1 reproduces pre-v1.3 behaviour. */
    public final int snapshotDepth;

    /**
     * §2.1 STACK_SNAPSHOT cadence (schema 4): capture each thread's live stack with its first
     * business record and every this-many records thereafter; 0 disables captures.
     */
    public final int stackEvery;

    /** Emit LINE records at each source line (FULL scope only) — the §7 execution-flow backbone. */
    public final boolean lines;

    /** Emit BRANCH records at each conditional jump (FULL scope only) — §7 condition values. */
    public final boolean branches;

    /** Per-thread record chunk size in KiB. */
    public final int recordBufKB;

    /** Per-thread blob chunk size in KiB. */
    public final int blobBufKB;

    /** Total recording size cap in MiB. */
    public final long maxTotalMB;

    /** Partial-chunk sweep interval in millis; 0 disables (bounds SIGKILL data loss). */
    public final int sweepMillis;

    /** Verbose agent logging to agent.log and stderr. */
    public final boolean debug;

    private AgentConfig(
            String hooksJar,
            String outDir,
            List<String> include,
            List<String> callsOnly,
            List<String> exclude,
            List<String> snapshotOpen,
            List<String> snapshotAlways,
            boolean autostart,
            String triggerClass,
            String triggerMethod,
            int maxString,
            int snapshotEvery,
            boolean snapshotFirstSeen,
            int snapshotDepth,
            int stackEvery,
            boolean lines,
            boolean branches,
            int recordBufKB,
            int blobBufKB,
            long maxTotalMB,
            int sweepMillis,
            boolean debug) {
        this.hooksJar = hooksJar;
        this.outDir = outDir;
        this.include = include;
        this.callsOnly = callsOnly;
        this.exclude = exclude;
        this.snapshotOpen = snapshotOpen;
        this.snapshotAlways = snapshotAlways;
        this.autostart = autostart;
        this.triggerClass = triggerClass;
        this.triggerMethod = triggerMethod;
        this.maxString = maxString;
        this.snapshotEvery = snapshotEvery;
        this.snapshotFirstSeen = snapshotFirstSeen;
        this.snapshotDepth = snapshotDepth;
        this.stackEvery = stackEvery;
        this.lines = lines;
        this.branches = branches;
        this.recordBufKB = recordBufKB;
        this.blobBufKB = blobBufKB;
        this.maxTotalMB = maxTotalMB;
        this.sweepMillis = sweepMillis;
        this.debug = debug;
    }

    /**
     * Parses and validates an agent argument string. Throws {@link IllegalArgumentException}
     * with a message naming the problem on any grammar or validation error — premain lets that
     * propagate so a bad config aborts JVM startup instead of recording garbage.
     */
    public static AgentConfig parse(String agentArgs) {
        if (agentArgs == null || agentArgs.isEmpty()) {
            throw bad("no agent arguments given; at least 'hooks=' and 'include=' are required");
        }
        final Map<String, String> pairs = new LinkedHashMap<>();
        for (final String chunk : agentArgs.split(",", -1)) {
            if (chunk.isEmpty()) {
                throw bad("empty entry (stray ',') in agent arguments: " + agentArgs);
            }
            final int eq = chunk.indexOf('=');
            if (eq <= 0) {
                throw bad("expected key=value, got '" + chunk + "'");
            }
            final String key = chunk.substring(0, eq).trim();
            final String value = chunk.substring(eq + 1).trim();
            if (value.isEmpty()) {
                throw bad("empty value for key '" + key + "'");
            }
            if (pairs.put(key, value) != null) {
                throw bad("duplicate key '" + key + "'");
            }
        }

        final String hooksJar = pairs.remove("hooks");
        if (hooksJar == null) {
            throw bad("missing required key 'hooks' (path to the hindsight hooks jar)");
        }
        final String outDir = remove(pairs, "outDir", ".hindsight");
        final List<String> include = parseList(pairs.remove("include"), "include");
        if (include.isEmpty()) {
            throw bad("missing required key 'include' (at least one class-name prefix)");
        }
        final List<String> callsOnly = parseList(pairs.remove("callsOnly"), "callsOnly");
        final List<String> exclude = parseList(pairs.remove("exclude"), "exclude");
        final List<String> snapshotOpen = parseList(pairs.remove("snapshotOpen"), "snapshotOpen");
        final List<String> snapshotAlways = parseList(pairs.remove("snapshotAlways"), "snapshotAlways");

        final String trigger = pairs.remove("trigger");
        String triggerClass = null;
        String triggerMethod = null;
        if (trigger != null) {
            final int hash = trigger.indexOf('#');
            if (hash <= 0 || hash == trigger.length() - 1 || trigger.indexOf('#', hash + 1) >= 0) {
                throw bad("trigger must be 'binary.class.Name#methodName', got '" + trigger + "'");
            }
            triggerClass = trigger.substring(0, hash);
            triggerMethod = trigger.substring(hash + 1);
        }

        // §10: a trigger implies autostart=false unless autostart is given explicitly.
        final String autostartRaw = pairs.remove("autostart");
        final boolean autostart =
                autostartRaw != null ? parseBoolean(autostartRaw, "autostart") : trigger == null;

        final int maxString = parseInt(remove(pairs, "maxString", "512"), "maxString", 0);
        final int snapshotEvery = parseInt(remove(pairs, "snapshotEvery", "8192"), "snapshotEvery", 0);
        final boolean snapshotFirstSeen = parseBoolean(remove(pairs, "snapshotFirstSeen", "true"), "snapshotFirstSeen");
        final int snapshotDepth = parseInt(remove(pairs, "snapshotDepth", "2"), "snapshotDepth", 1);
        final int stackEvery = parseInt(remove(pairs, "stackEvery", "1024"), "stackEvery", 0);
        final boolean lines = parseBoolean(remove(pairs, "lines", "true"), "lines");
        final boolean branches = parseBoolean(remove(pairs, "branches", "true"), "branches");
        final int recordBufKB = parseInt(remove(pairs, "recordBufKB", "256"), "recordBufKB", 1);
        final int blobBufKB = parseInt(remove(pairs, "blobBufKB", "128"), "blobBufKB", 1);
        final long maxTotalMB = parseLong(remove(pairs, "maxTotalMB", "204800"), "maxTotalMB", 0);
        final int sweepMillis = parseInt(remove(pairs, "sweepMillis", "500"), "sweepMillis", 0);
        final boolean debug = parseBoolean(remove(pairs, "debug", "false"), "debug");

        if (!pairs.isEmpty()) {
            throw bad("unknown key(s): " + pairs.keySet());
        }
        return new AgentConfig(
                hooksJar,
                outDir,
                include,
                callsOnly,
                exclude,
                snapshotOpen,
                snapshotAlways,
                autostart,
                triggerClass,
                triggerMethod,
                maxString,
                snapshotEvery,
                snapshotFirstSeen,
                snapshotDepth,
                stackEvery,
                lines,
                branches,
                recordBufKB,
                blobBufKB,
                maxTotalMB,
                sweepMillis,
                debug);
    }

    /**
     * Decides the instrumentation scope for a class binary name: hard ignores first, then
     * longest-prefix-wins among the three scope lists (ties broken toward the more restrictive
     * scope). Called on every class-load, so it must stay allocation-free on the hot path.
     */
    public Scope scopeFor(String binaryName) {
        if (binaryName == null || binaryName.isEmpty() || binaryName.charAt(0) == '[') {
            return Scope.NONE;
        }
        for (final String prefix : HARD_IGNORED_PREFIXES) {
            if (binaryName.startsWith(prefix)) {
                return Scope.NONE;
            }
        }
        int excluded = longestMatch(exclude, binaryName);
        if (binaryName.startsWith(SOFT_IGNORED_PREFIX)) {
            excluded = Math.max(excluded, SOFT_IGNORED_PREFIX.length());
        }
        final int calls = longestMatch(callsOnly, binaryName);
        final int included = longestMatch(include, binaryName);
        final int best = Math.max(excluded, Math.max(calls, included));
        if (best == 0) {
            return Scope.NONE;
        }
        if (excluded == best) {
            return Scope.NONE;
        }
        return calls == best ? Scope.CALLS_ONLY : Scope.FULL;
    }

    /** Echo of the effective configuration for agent.log (the manifest echoes it too). */
    public String describe() {
        return "hooks=" + hooksJar
                + ", outDir=" + outDir
                + ", include=" + include
                + ", callsOnly=" + callsOnly
                + ", exclude=" + exclude
                + ", snapshotOpen=" + snapshotOpen
                + ", snapshotAlways=" + snapshotAlways
                + ", autostart=" + autostart
                + ", trigger=" + (triggerClass == null ? "none" : triggerClass + "#" + triggerMethod)
                + ", maxString=" + maxString
                + ", snapshotEvery=" + snapshotEvery
                + ", snapshotFirstSeen=" + snapshotFirstSeen
                + ", snapshotDepth=" + snapshotDepth
                + ", stackEvery=" + stackEvery
                + ", lines=" + lines
                + ", branches=" + branches
                + ", recordBufKB=" + recordBufKB
                + ", blobBufKB=" + blobBufKB
                + ", maxTotalMB=" + maxTotalMB
                + ", debug=" + debug;
    }

    /** Length of the longest list element that prefixes {@code name}, or 0 when none does. */
    private static int longestMatch(List<String> prefixes, String name) {
        int best = 0;
        for (int i = 0; i < prefixes.size(); i++) {
            final String p = prefixes.get(i);
            if (p.length() > best && name.startsWith(p)) {
                best = p.length();
            }
        }
        return best;
    }

    private static String remove(Map<String, String> pairs, String key, String fallback) {
        final String v = pairs.remove(key);
        return v != null ? v : fallback;
    }

    private static List<String> parseList(String raw, String key) {
        if (raw == null) {
            return Collections.emptyList();
        }
        final List<String> out = new ArrayList<>();
        for (final String element : raw.split("\\|", -1)) {
            final String trimmed = element.trim();
            if (trimmed.isEmpty()) {
                throw bad("empty element (stray '|') in list '" + key + "': " + raw);
            }
            out.add(trimmed);
        }
        return Collections.unmodifiableList(out);
    }

    private static boolean parseBoolean(String raw, String key) {
        if ("true".equals(raw)) {
            return true;
        }
        if ("false".equals(raw)) {
            return false;
        }
        throw bad("key '" + key + "' must be true or false, got '" + raw + "'");
    }

    private static int parseInt(String raw, String key, int min) {
        final int v;
        try {
            v = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw bad("key '" + key + "' must be an integer, got '" + raw + "'");
        }
        if (v < min) {
            throw bad("key '" + key + "' must be >= " + min + ", got " + v);
        }
        return v;
    }

    private static long parseLong(String raw, String key, long min) {
        final long v;
        try {
            v = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw bad("key '" + key + "' must be an integer, got '" + raw + "'");
        }
        if (v < min) {
            throw bad("key '" + key + "' must be >= " + min + ", got " + v);
        }
        return v;
    }

    private static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException("hindsight agent config: " + message);
    }
}
