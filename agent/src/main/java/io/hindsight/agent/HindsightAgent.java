// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.agent;

import io.hindsight.runtime.HindsightRuntime;
import io.hindsight.runtime.RuntimeConfig;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarFile;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

/**
 * Java agent entry point for the hindsight recorder
 * ({@code -javaagent:hindsight-agent.jar=hooks=...,include=...}).
 *
 * <p>Premain runs in a strict order, each step a precondition of the next:
 * <ol>
 *   <li><b>Parse config.</b> Errors throw — a JVM that starts recording the wrong thing is
 *       worse than one that refuses to start (FORMAT.md §10).</li>
 *   <li><b>Append the hooks jar to the bootstrap search.</b> Must precede the first touch of
 *       any {@code io.hindsight.runtime}/{@code hooks} class: the agent's fat jar deliberately
 *       excludes them so exactly one copy exists process-wide, resolvable from every
 *       classloader. A quick {@code Class.forName} against the bootstrap loader validates the
 *       jar actually contains the hooks.</li>
 *   <li><b>Boot the runtime.</b> Resolves the recording directory the agent's own writers
 *       (symbols, sites, log) need. From here on all failures are contained: a broken recorder
 *       must degrade to a no-op, never break the host process.</li>
 *   <li><b>Site registry + transformer + AgentBuilder install.</b> Fail-open per class via the
 *       listener; JPMS read edges from every instrumented module to the hooks' bootstrap
 *       unnamed module (§11), because instrumented bytecode in named modules otherwise gets
 *       {@code IllegalAccessError} calling the hooks.</li>
 * </ol>
 */
public final class HindsightAgent {

    private HindsightAgent() {}

    /**
     * Standard {@code -javaagent} entry. Throws only on configuration errors (bad arguments,
     * missing/invalid hooks jar) — anything after that point fails open with a stderr notice
     * and leaves the application uninstrumented.
     */
    public static void premain(String agentArgs, Instrumentation inst) throws IOException {
        final AgentConfig config = AgentConfig.parse(agentArgs);

        final File hooksJar = new File(config.hooksJar);
        if (!hooksJar.isFile()) {
            throw new IllegalArgumentException("hindsight agent config: hooks jar not found: " + config.hooksJar);
        }
        inst.appendToBootstrapClassLoaderSearch(new JarFile(hooksJar));
        final Class<?> hooksAnchor;
        try {
            hooksAnchor = Class.forName("io.hindsight.hooks.MethodHooks", false, null);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(
                    "hindsight agent config: " + config.hooksJar + " does not contain the hindsight hooks", e);
        }

        try {
            install(config, hooksAnchor, inst);
        } catch (Throwable t) {
            System.err.println("hindsight: agent failed to install; application continues uninstrumented: " + t);
            t.printStackTrace(System.err);
        }
    }

    /** Attach-API entry; identical semantics (already-loaded classes are not retransformed). */
    public static void agentmain(String agentArgs, Instrumentation inst) throws IOException {
        premain(agentArgs, inst);
    }

    private static void install(AgentConfig config, Class<?> hooksAnchor, Instrumentation inst) throws IOException {
        final RuntimeConfig runtimeConfig = new RuntimeConfig();
        runtimeConfig.outDir = Paths.get(config.outDir);
        runtimeConfig.sourceCommit = config.sourceCommit;
        runtimeConfig.maxString = config.maxString;
        runtimeConfig.snapshotEvery = config.snapshotEvery;
        runtimeConfig.snapshotFirstSeen = config.snapshotFirstSeen;
        runtimeConfig.snapshotDepth = config.snapshotDepth;
        runtimeConfig.snapshotAlways = config.snapshotAlways.toArray(new String[0]);
        runtimeConfig.stackEvery = config.stackEvery;
        runtimeConfig.recordBufKB = config.recordBufKB;
        runtimeConfig.blobBufKB = config.blobBufKB;
        runtimeConfig.maxTotalMB = config.maxTotalMB;
        runtimeConfig.sweepMillis = config.sweepMillis;
        runtimeConfig.autostart = config.autostart;
        HindsightRuntime.boot(runtimeConfig);

        final Path recordingDir = HindsightRuntime.recordingDir();
        if (recordingDir == null) {
            System.err.println("hindsight: runtime failed to boot; application continues uninstrumented");
            return;
        }

        final AgentLog log = new AgentLog(recordingDir, config.debug);
        log.info("hindsight agent starting: " + config.describe());
        log.info("recording directory: " + recordingDir);

        final SiteRegistry registry = new SiteRegistry(recordingDir, config.triggerClass, config.triggerMethod, log);
        if (config.triggerClass != null && config.scopeFor(config.triggerClass) == AgentConfig.Scope.NONE) {
            log.warn("trigger class " + config.triggerClass
                    + " matches no include/callsOnly prefix; the trigger will never fire");
        }

        final HindsightTransformer transformer = new HindsightTransformer(config, registry, log);

        // §10 snapshotOpen: data/model packages opened for reflective §7 snapshots without
        // being instrumented — a pure side-effect transformer plus an eager boot-layer pass.
        // Deliberately BEFORE the AgentBuilder install: in attach mode instrumented code can
        // run the moment installOn returns, and a dump racing the opens would cache an empty
        // per-class snapshot plan permanently (ClassValue has no invalidation).
        if (!config.snapshotOpen.isEmpty()) {
            final SnapshotOpenTransformer opener =
                    new SnapshotOpenTransformer(config.snapshotOpen, inst, hooksAnchor.getModule(), log);
            inst.addTransformer(opener, false);
            final int eager = opener.openBootLayerPackages();
            log.info("snapshot-open prefixes: " + config.snapshotOpen
                    + " (" + eager + " boot-layer packages opened eagerly)");
        }

        new AgentBuilder.Default()
                // Pure visitation: keeps the class's shape, avoids method-graph compilation
                // and — crucially — any frame recomputation at transform time.
                .with(AgentBuilder.TypeStrategy.Default.DECORATE)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                // Describe types from class files, never via loaded Class objects: premain
                // transforms classes that are mid-load.
                .with(AgentBuilder.DescriptionStrategy.Default.POOL_ONLY)
                .with(new FailOpenListener(log, inst, hooksAnchor.getModule()))
                // §11 JPMS: every instrumented module must read the hooks' (bootstrap
                // unnamed) module or its instrumented bytecode cannot link the hook calls.
                .assureReadEdgeTo(inst, hooksAnchor)
                // Belt and braces next to scopeFor's hard ignores: synthetic types (lambda
                // proxies et al.) are never worth bracketing even when a prefix matches.
                .ignore(ElementMatchers.<TypeDescription>isSynthetic())
                .type(new ScopeMatcher(config))
                .transform(transformer)
                .installOn(inst);

        log.info("instrumentation installed (include=" + config.include
                + ", callsOnly=" + config.callsOnly
                + ", exclude=" + config.exclude + ")");
    }

    /** Positive class filter: anything {@link AgentConfig#scopeFor} does not map to NONE. */
    private static final class ScopeMatcher extends ElementMatcher.Junction.AbstractBase<TypeDescription> {

        private final AgentConfig config;

        ScopeMatcher(AgentConfig config) {
            this.config = config;
        }

        @Override
        public boolean matches(TypeDescription target) {
            return config.scopeFor(target.getName()) != AgentConfig.Scope.NONE;
        }
    }

    /**
     * §11 fail-open diagnostics: a transform error leaves the class's original bytecode in
     * place (ByteBuddy's default behavior) — this listener makes sure the event is visible in
     * {@code agent.log} instead of vanishing.
     */
    private static final class FailOpenListener extends AgentBuilder.Listener.Adapter {

        private final AgentLog log;
        private final Instrumentation inst;
        private final Module hooksModule;
        private final java.util.Set<String> openedPackages = java.util.concurrent.ConcurrentHashMap.newKeySet();

        FailOpenListener(AgentLog log, Instrumentation inst, Module hooksModule) {
            this.log = log;
            this.inst = inst;
            this.hooksModule = hooksModule;
        }

        @Override
        public void onError(
                String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
            log.error("transform failed, class left uninstrumented: " + typeName, throwable);
        }

        @Override
        public void onTransformation(
                TypeDescription typeDescription,
                ClassLoader classLoader,
                JavaModule module,
                boolean loaded,
                DynamicType dynamicType) {
            log.debug("instrumented " + typeDescription.getName());
            openForSnapshots(typeDescription, module);
        }

        /**
         * The §7 snapshot sampler reads fields reflectively from the (bootstrap-unnamed) hooks
         * module; on a module-path launch {@code trySetAccessible} fails for every class in a
         * non-open named module and snapshots silently degrade to empty payloads. Opening each
         * instrumented package to the hooks module (the runtime equivalent of
         * {@code --add-opens pkg=<hooks>}) keeps the feature alive where it matters.
         */
        private void openForSnapshots(TypeDescription type, JavaModule module) {
            try {
                if (module == null || !module.isNamed()) {
                    return;
                }
                final String name = type.getName();
                final int dot = name.lastIndexOf('.');
                if (dot < 0) {
                    return;
                }
                final String pkg = name.substring(0, dot);
                if (!openedPackages.add(module + "/" + pkg)) {
                    return;
                }
                final Module m = (Module) module.unwrap();
                if (!inst.isModifiableModule(m) || m.isOpen(pkg, hooksModule)) {
                    return;
                }
                inst.redefineModule(
                        m,
                        java.util.Set.of(),
                        java.util.Map.of(),
                        java.util.Map.of(pkg, java.util.Set.of(hooksModule)),
                        java.util.Set.of(),
                        java.util.Map.of());
            } catch (Throwable t) {
                log.debug("could not open " + type.getName() + "'s package for snapshots: " + t);
            }
        }
    }
}
