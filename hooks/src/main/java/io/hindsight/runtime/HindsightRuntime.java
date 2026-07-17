// SPDX-License-Identifier: Apache-2.0
package io.hindsight.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide singleton facade for the hindsight recording subsystem (FORMAT.md §8–§9).
 *
 * <p>Owns the per-thread {@link ThreadWorker}s, the {@code rt-symbols.tsv} interner, the
 * single {@code hindsight-writer} daemon and the global sequence counter. Instrumented
 * bytecode (via the hook classes in {@code io.hindsight.hooks}) calls {@link #worker()} on
 * every recorded event; everything else hangs off the worker.
 *
 * <p>This class is loaded onto the bootstrap classloader by the agent's premain via
 * {@code Instrumentation.appendToBootstrapClassLoaderSearch}, so it must NOT depend on
 * anything outside {@code java.base}.
 *
 * <p>Failure policy: nothing here may ever throw into application code. {@link #boot} itself
 * swallows failures too (leaving the runtime unbooted, {@link #recordingDir()} {@code null});
 * a recorder that cannot start must degrade to a no-op, not take the host process down.
 */
public final class HindsightRuntime {

    /** Threads beyond this many workers are not recorded, logged once (§9). */
    static final int MAX_WORKERS = 4096;

    private static volatile Instance instance;

    /** Error kinds already logged; bounded so a pathological error storm can't leak memory. */
    private static final Set<String> LOGGED_KINDS = ConcurrentHashMap.newKeySet();

    private static final int LOGGED_KINDS_CAP = 1024;

    private HindsightRuntime() {}

    // =====================================================================================
    // Lifecycle (frozen control surface, §8)
    // =====================================================================================

    /**
     * Initialises the runtime: resolves the recording directory (§1 sibling rule), writes
     * {@code manifest.properties} (§13), starts the writer daemon and installs the shutdown
     * hook. Called once by the agent's premain before any transformer is installed; further
     * calls are ignored. Never throws — on failure the runtime stays unbooted and every hook
     * no-ops.
     */
    public static synchronized void boot(RuntimeConfig cfg) {
        if (instance != null) {
            return;
        }
        try {
            instance = new Instance(cfg);
        } catch (Throwable t) {
            reportInternalError("boot", t);
        }
    }

    /**
     * The <em>resolved</em> recording directory (after the {@code "-<n>"} sibling rule), or
     * {@code null} if the runtime is not booted. The agent writes {@code symbols.tsv},
     * {@code sites.tsv} and {@code agent.log} here.
     */
    public static Path recordingDir() {
        final Instance in = instance;
        return in == null ? null : in.dir;
    }

    /** Registers the site whose next METHOD_ENTER starts recording (§8 trigger semantics). */
    public static void setTriggerSite(int siteId) {
        final Instance in = instance;
        if (in != null) {
            in.triggerSite = siteId;
        }
    }

    /** Opens the recording gate. Safe from any thread; a no-op when unbooted or shut down. */
    public static void startRecording() {
        final Instance in = instance;
        if (in != null && !in.shutdownRun.get()) {
            in.recording = true;
        }
    }

    /** Closes the recording gate; buffered data is still flushed at shutdown. */
    public static void stopRecording() {
        final Instance in = instance;
        if (in != null) {
            in.recording = false;
        }
    }

    /** True while the volatile recording gate is open. */
    public static boolean isRecording() {
        final Instance in = instance;
        return in != null && in.recording;
    }

    /**
     * Trigger check used by {@code MethodHooks.enter}: atomically-enough starts recording when
     * {@code siteId} is the registered trigger site and recording is not yet active. Returns
     * true when this call opened the gate (the caller then records the triggering ENTER).
     */
    public static boolean startIfTriggerSite(int siteId) {
        final Instance in = instance;
        if (in == null || in.recording || in.shutdownRun.get()) {
            return false;
        }
        if (in.triggerSite == Integer.MIN_VALUE || siteId != in.triggerSite) {
            return false;
        }
        in.recording = true;
        return true;
    }

    // =====================================================================================
    // Per-thread worker access (§9)
    // =====================================================================================

    /**
     * Returns the calling thread's worker, creating and registering it on first use, or
     * {@code null} when the runtime is not booted. Threads that must never record (the writer
     * daemon, threads past the {@link #MAX_WORKERS} cap) receive a permanently-suspended
     * sentinel, which the hooks' {@code suspended} check filters out.
     */
    public static ThreadWorker worker() {
        final Instance in = instance;
        if (in == null) {
            return null;
        }
        final ThreadWorker w = in.workerTL.get();
        return w != null ? w : in.createWorker();
    }

    // =====================================================================================
    // Diagnostics
    // =====================================================================================

    /** Routes an internal error to stderr, once per throwable class. Never throws. */
    public static void reportInternalError(Throwable t) {
        reportInternalError(t == null ? "null" : t.getClass().getName(), t);
    }

    /**
     * Routes an internal error to stderr with a {@code hindsight:} prefix, at most once per
     * {@code kind} so a hot broken path cannot flood the host application's logs. Never throws.
     */
    public static void reportInternalError(String kind, Throwable t) {
        try {
            if (LOGGED_KINDS.size() < LOGGED_KINDS_CAP && LOGGED_KINDS.add("err:" + kind)) {
                System.err.println("hindsight: internal error (" + kind + "): " + t);
                if (t != null) {
                    t.printStackTrace(System.err);
                }
            }
        } catch (Throwable ignored) {
            // Even diagnostics must never propagate into application threads.
        }
    }

    /** Logs {@code message} to stderr at most once per {@code key}. Never throws. */
    static void logOnce(String key, String message) {
        try {
            if (LOGGED_KINDS.size() < LOGGED_KINDS_CAP && LOGGED_KINDS.add("log:" + key)) {
                System.err.println("hindsight: " + message);
            }
        } catch (Throwable ignored) {
            // see above
        }
    }

    // =====================================================================================
    // Test support
    // =====================================================================================

    /**
     * Runs the full shutdown sequence synchronously and unboots the runtime so a test JVM can
     * boot again against a fresh directory. Production code never calls this — the shutdown
     * hook covers the real path (and is what this delegates to).
     */
    static void shutdownForTests() {
        final Instance in = instance;
        if (in != null) {
            in.shutdown(false);
            instance = null;
        }
    }

    // =====================================================================================
    // The booted state
    // =====================================================================================

    /**
     * Everything that exists only between {@link #boot} and shutdown, bundled so tests can
     * cycle the runtime and so a failed boot leaves no half-initialised statics behind.
     */
    static final class Instance {

        final Path dir;
        final RuntimeSymbols symbols;
        final ChunkWriter writer;
        final int maxString;
        final int snapshotEvery;
        final boolean snapshotFirstSeen;
        final int snapshotDepth;

        /** Global monotonic sequence, starts at 1 (§2); one getAndIncrement per record. */
        final AtomicLong seq = new AtomicLong(1);

        final AtomicInteger nextTid = new AtomicInteger(1);
        final ConcurrentHashMap<Thread, ThreadWorker> workers = new ConcurrentHashMap<>();
        final ThreadLocal<ThreadWorker> workerTL = new ThreadLocal<>();
        final AtomicBoolean shutdownRun = new AtomicBoolean();

        volatile boolean recording;
        volatile int triggerSite = Integer.MIN_VALUE;

        private final Thread shutdownHook;

        Instance(RuntimeConfig cfg) throws IOException {
            this.maxString = Math.max(0, cfg.maxString);
            this.snapshotEvery = Math.max(0, cfg.snapshotEvery);
            this.snapshotFirstSeen = cfg.snapshotFirstSeen;
            this.snapshotDepth = Math.max(1, cfg.snapshotDepth); // §7/§10: must be ≥ 1
            this.dir = resolveRecordingDir(cfg.outDir.toAbsolutePath());
            writeManifest(dir, cfg, System.currentTimeMillis());
            this.symbols = new RuntimeSymbols(dir);
            this.writer = new ChunkWriter(dir, cfg.maxTotalMB, cfg.recordBufKB, cfg.blobBufKB);
            this.writer.start();
            this.shutdownHook = new Thread(() -> shutdown(true), "hindsight-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            if (cfg.sweepMillis > 0) {
                startSweeper(cfg.sweepMillis);
            }
            this.recording = cfg.autostart;
        }

        /**
         * HAPI-test subprocess nodes die by {@code destroyForcibly()} — SIGKILL, no shutdown
         * hooks. Without sweeping, every worker's partial record chunk (≤ recordBufKB) and any
         * blob chunk that never filled (strings!) are lost, and low-traffic threads lose their
         * entire stream. The sweeper bounds that loss to roughly one interval: it periodically
         * takes each worker's monitor (same whole-record guarantee as the shutdown steal) and
         * hands the partial chunks to the writer.
         */
        private void startSweeper(int sweepMillis) {
            final Thread sweeper = new Thread(() -> {
                while (!shutdownRun.get()) {
                    try {
                        Thread.sleep(sweepMillis);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!recording && workers.isEmpty()) {
                        continue;
                    }
                    for (final Map.Entry<Thread, ThreadWorker> e : workers.entrySet()) {
                        try {
                            e.getValue().stealBuffers();
                            // Prune dead threads' workers: their tail is flushed above, and
                            // removing them (a) frees their direct buffers/string memos and
                            // (b) keeps the MAX_WORKERS cap counting only live threads —
                            // otherwise thread churn silently stops recording new threads.
                            if (!e.getKey().isAlive()) {
                                workers.remove(e.getKey(), e.getValue());
                            }
                        } catch (Throwable t) {
                            reportInternalError("sweep", t);
                        }
                    }
                }
            }, "hindsight-sweeper");
            sweeper.setDaemon(true);
            sweeper.start();
        }

        long nextSeq() {
            return seq.getAndIncrement();
        }

        ThreadWorker createWorker() {
            final Thread t = Thread.currentThread();
            if (writer.isWriterThread(t)) {
                // §9: the writer daemon's own worker is permanently suspended.
                final ThreadWorker denied = ThreadWorker.denied();
                workerTL.set(denied);
                return denied;
            }
            if (workers.size() >= MAX_WORKERS) {
                logOnce("worker-cap", "worker cap of " + MAX_WORKERS
                        + " threads reached; further threads are not recorded");
                final ThreadWorker denied = ThreadWorker.denied();
                workerTL.set(denied);
                return denied;
            }
            final ThreadWorker w = new ThreadWorker(this, nextTid.getAndIncrement(), t.getName(), t.threadId());
            workerTL.set(w);
            workers.put(t, w);
            return w;
        }

        /**
         * §9 shutdown: close the gate, quiesce briefly, suspend and steal every live worker's
         * partial chunks, drain the writer, then finish the manifest. Idempotent — the JVM
         * shutdown hook and {@link #shutdownForTests} may race.
         */
        void shutdown(boolean fromShutdownHook) {
            if (!shutdownRun.compareAndSet(false, true)) {
                return;
            }
            recording = false;
            try {
                Thread.sleep(50); // let in-flight hooks finish their current record
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            for (final ThreadWorker w : workers.values()) {
                w.suspended = true;
            }
            // Two steal passes: a record emitted between pass one visiting worker A and
            // visiting worker B can register an object whose OBJECT_FIRST_SEEN lands in A's
            // fresh buffer — pass two (after the suspension has settled) collects it, keeping
            // §2.1's first-seen-before-reference invariant on graceful shutdowns.
            for (int pass = 0; pass < 2; pass++) {
                for (final ThreadWorker w : workers.values()) {
                    try {
                        w.stealBuffers();
                    } catch (Throwable t) {
                        reportInternalError("shutdown-steal", t);
                    }
                }
                if (pass == 0) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            writer.shutdownAndDrain();
            symbols.close();
            appendManifestEnd();
            if (!fromShutdownHook) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (Throwable ignored) {
                    // already shutting down, or hook already ran — both fine
                }
            }
        }

        private void appendManifestEnd() {
            try {
                Files.writeString(
                        dir.resolve("manifest.properties"),
                        "endEpochMillis=" + System.currentTimeMillis() + System.lineSeparator(),
                        StandardCharsets.ISO_8859_1,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (Throwable t) {
                reportInternalError("manifest-end", t);
            }
        }
    }

    // =====================================================================================
    // Boot helpers
    // =====================================================================================

    /**
     * §1: if the requested directory exists and is non-empty, use the sibling
     * {@code "<outDir>-<n>"} with the smallest free {@code n ≥ 2} and log the substitution.
     */
    private static Path resolveRecordingDir(Path requested) throws IOException {
        Path candidate = requested;
        if (Files.exists(candidate) && !isEmptyDirectory(candidate)) {
            int n = 2;
            while (true) {
                final Path sibling = requested.resolveSibling(requested.getFileName() + "-" + n);
                if (!Files.exists(sibling) || isEmptyDirectory(sibling)) {
                    candidate = sibling;
                    break;
                }
                n++;
            }
            logOnce("dir-substitution",
                    "recording dir " + requested + " exists and is non-empty; recording to " + candidate);
        }
        Files.createDirectories(candidate);
        return candidate;
    }

    private static boolean isEmptyDirectory(Path p) throws IOException {
        if (!Files.isDirectory(p)) {
            return false; // a plain file squatting on the name is "non-empty" for our purposes
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
            return !ds.iterator().hasNext();
        }
    }

    private static void writeManifest(Path dir, RuntimeConfig cfg, long startEpochMillis) throws IOException {
        final Properties p = new Properties();
        p.setProperty("schema", "3");
        final Package pkg = HindsightRuntime.class.getPackage();
        final String version = pkg == null ? null : pkg.getImplementationVersion();
        p.setProperty("recorder", version != null ? version : "0.0.1-SNAPSHOT");
        p.setProperty("jvm", System.getProperty("java.vm.name", "?")
                + " " + System.getProperty("java.runtime.version", "?"));
        p.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        p.setProperty("startEpochMillis", Long.toString(startEpochMillis));
        p.setProperty("args", "outDir=" + cfg.outDir
                + ",maxString=" + cfg.maxString
                + ",snapshotEvery=" + cfg.snapshotEvery
                + ",snapshotFirstSeen=" + cfg.snapshotFirstSeen
                + ",snapshotDepth=" + cfg.snapshotDepth
                + ",recordBufKB=" + cfg.recordBufKB
                + ",blobBufKB=" + cfg.blobBufKB
                + ",maxTotalMB=" + cfg.maxTotalMB
                + ",autostart=" + cfg.autostart);
        try (OutputStream os = Files.newOutputStream(dir.resolve("manifest.properties"))) {
            p.store(os, "hindsight recording manifest");
        }
    }
}
