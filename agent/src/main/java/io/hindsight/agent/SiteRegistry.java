// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.agent;

import io.hindsight.runtime.HindsightRuntime;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Transform-time symbol interner and site allocator backing {@code symbols.tsv} and
 * {@code sites.tsv} (FORMAT.md §4–§5).
 *
 * <p>Site ids are what instrumented bytecode embeds as integer constants, so this registry is
 * the single point where "a place in the code" becomes "a number in a record". Three rules
 * shape it:
 *
 * <ul>
 *   <li><b>Idempotent method sites.</b> ByteBuddy resolves the enter and the exit advice of a
 *       method independently; both resolutions must yield the same id or the reader could
 *       never pair ENTER with EXIT. Method sites are therefore deduplicated on
 *       {@code class#name+descriptor}. Field and local sites are one-per-instruction by spec
 *       (§5) and never deduplicated.</li>
 *   <li><b>Eager flush.</b> Every row hits the disk before its id is returned to the caller —
 *       a crashed recording must still resolve every id its records mention (§4).</li>
 *   <li><b>Thread safety.</b> Class loading (and therefore transformation) is concurrent;
 *       id assignment and row writing share one lock so ids stay dense and rows uncorrupted.</li>
 * </ul>
 *
 * <p>Method-site rows also need the source file, the first line number of the method body and
 * the §5 parameter-name suffix (recorder ≥ v1.7) — information ByteBuddy's advice resolution
 * cannot see, because it works on method <em>descriptions</em>, not bytecode. The
 * {@code MethodLinePrescan} visitor buffers each class, extracts that information, and parks
 * it here via {@link #beginClassScan} for the duration of the class's replay through the
 * downstream visitors (same thread, so a {@link ThreadLocal} suffices and concurrent
 * transforms cannot cross-contaminate).
 *
 * <p>The registry also wires the §10 trigger: the first method site matching the configured
 * {@code trigger} class/method is handed to {@link HindsightRuntime#setTriggerSite(int)}.
 */
public final class SiteRegistry implements AutoCloseable {

    /** Symbol kinds of the agent-side id space (§4); the enum name is the on-disk kind text. */
    public enum SymbolKind {
        CLASS,
        METHOD,
        FIELD,
        VAR,
        FILE
    }

    /** Static-member marker suffixed to the descriptor column per §5. */
    private static final String STATIC_SUFFIX = "#s";

    private final BufferedWriter symbolsOut;
    private final BufferedWriter sitesOut;

    private final ConcurrentHashMap<String, Integer> symbolIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> methodSiteIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> lineSiteIds = new ConcurrentHashMap<>();

    /** Guards id assignment + row write for both TSVs (symbols nest inside sites, never reverse). */
    private final Object lock = new Object();

    private int nextSymbolId = 1; // guarded by lock
    private int nextSiteId = 1; // guarded by lock

    private final String triggerClass;
    private final String triggerMethod;
    private final AtomicBoolean triggerBound = new AtomicBoolean();
    private final AgentLog log;

    /** Per-transform-thread class scan info (source file + method first lines), see class doc. */
    private final ThreadLocal<ClassScan> currentScan = new ThreadLocal<>();

    /**
     * Opens {@code symbols.tsv} and {@code sites.tsv} in the recording directory. The trigger
     * class/method may be {@code null} when no trigger is configured.
     */
    public SiteRegistry(Path recordingDir, String triggerClass, String triggerMethod, AgentLog log)
            throws IOException {
        this.symbolsOut = open(recordingDir.resolve("symbols.tsv"));
        this.sitesOut = open(recordingDir.resolve("sites.tsv"));
        this.triggerClass = triggerClass;
        this.triggerMethod = triggerMethod;
        this.log = log;
    }

    // =====================================================================================
    // Class scan info (populated by MethodLinePrescan, consumed by methodSite)
    // =====================================================================================

    /**
     * Parks the given class's source file, per-method first-line map and per-method
     * parameter-name suffix map for the current thread while the class replays through the
     * downstream visitors. Both maps are keyed on {@code name + descriptor};
     * {@code methodParamSuffixes} values are ready-made §5 {@code #p<slot>=<name>|...}
     * suffixes (methods without one simply have no entry).
     */
    public void beginClassScan(
            String classBinaryName,
            String sourceFile,
            Map<String, Integer> methodFirstLines,
            Map<String, String> methodParamSuffixes) {
        currentScan.set(new ClassScan(classBinaryName, sourceFile, methodFirstLines, methodParamSuffixes));
    }

    /** Clears what {@link #beginClassScan} parked; call from a finally around the replay. */
    public void endClassScan() {
        currentScan.remove();
    }

    // =====================================================================================
    // Site assignment (§5)
    // =====================================================================================

    /**
     * Resolves the METHOD site for one instrumented method, assigning it on first sight.
     * Idempotent: enter and exit advice of the same method get the same id. Line, file and
     * the §5 {@code #p} parameter-name suffix (recorder ≥ v1.7) come from the parked class
     * scan when available, else 0 / absent (§5 allows both unknown). Suffix order is fixed by
     * §5: the {@code #s} static marker first, then {@code #p<slot>=<name>|...} — and because
     * the prescan runs for FULL and callsOnly scopes alike, both scopes' sites carry names
     * whenever the class file has an LVT or a MethodParameters attribute.
     */
    public int methodSite(String classBinaryName, String methodName, String descriptor, boolean isStatic) {
        final String desc = isStatic ? descriptor + STATIC_SUFFIX : descriptor;
        final String key = classBinaryName + '#' + methodName + desc;
        final Integer cached = methodSiteIds.get(key);
        if (cached != null) {
            return cached;
        }
        synchronized (lock) {
            final Integer raced = methodSiteIds.get(key);
            if (raced != null) {
                return raced;
            }
            int line = 0;
            int fileSym = 0;
            String suffixedDesc = desc;
            final ClassScan scan = currentScan.get();
            if (scan != null && scan.classBinaryName.equals(classBinaryName)) {
                final Integer firstLine = scan.methodFirstLines.get(methodName + descriptor);
                if (firstLine != null) {
                    line = firstLine;
                }
                if (scan.sourceFile != null) {
                    fileSym = symbol(SymbolKind.FILE, scan.sourceFile);
                }
                final String params = scan.methodParamSuffixes.get(methodName + descriptor);
                if (params != null) {
                    suffixedDesc = desc + params; // §5: #s first, then #p
                }
            }
            final int classSym = symbol(SymbolKind.CLASS, classBinaryName);
            final int memberSym = symbol(SymbolKind.METHOD, methodName);
            final int id = writeSite("METHOD", classSym, fileSym, line, memberSym, suffixedDesc, -1);
            methodSiteIds.put(key, id);
            maybeBindTrigger(classBinaryName, methodName, id);
            return id;
        }
    }

    /**
     * Assigns a fresh FIELD site for one {@code PUTFIELD}/{@code PUTSTATIC} instruction (§5:
     * one site per write instruction, never deduplicated). {@code ownerBinaryName} is the
     * field's declaring class, which may differ from the instrumented class; the source file
     * is the instrumented class's (that is where {@code line} points).
     */
    public int fieldSite(
            String ownerBinaryName,
            String fieldName,
            String typeDescriptor,
            boolean isStatic,
            String sourceFile,
            int line) {
        synchronized (lock) {
            final int classSym = symbol(SymbolKind.CLASS, ownerBinaryName);
            final int fileSym = sourceFile == null ? 0 : symbol(SymbolKind.FILE, sourceFile);
            final int memberSym = symbol(SymbolKind.FIELD, fieldName);
            final String desc = isStatic ? typeDescriptor + STATIC_SUFFIX : typeDescriptor;
            return writeSite("FIELD", classSym, fileSym, line, memberSym, desc, -1);
        }
    }

    /**
     * Assigns a fresh LOCAL site for one store instruction (§5: several sites may share a
     * variable). {@code classBinaryName} is the class declaring the method that contains the
     * store; {@code slot} is the LVT slot index.
     */
    public int localSite(
            String classBinaryName,
            String varName,
            String typeDescriptor,
            int slot,
            String sourceFile,
            int line) {
        synchronized (lock) {
            final int classSym = symbol(SymbolKind.CLASS, classBinaryName);
            final int fileSym = sourceFile == null ? 0 : symbol(SymbolKind.FILE, sourceFile);
            final int memberSym = symbol(SymbolKind.VAR, varName);
            return writeSite("LOCAL", classSym, fileSym, line, memberSym, typeDescriptor, slot);
        }
    }

    /**
     * Resolves the LINE site for one source line, assigning it on first sight. Deduplicated on
     * {@code class#line}: a line crossed a million times in a loop still produces one site row,
     * and every LINE record just references it (§5, §7).
     */
    public int lineSite(String classBinaryName, String sourceFile, int line) {
        final String key = classBinaryName + '#' + line;
        final Integer cached = lineSiteIds.get(key);
        if (cached != null) {
            return cached;
        }
        synchronized (lock) {
            final Integer raced = lineSiteIds.get(key);
            if (raced != null) {
                return raced;
            }
            final int classSym = symbol(SymbolKind.CLASS, classBinaryName);
            final int fileSym = sourceFile == null ? 0 : symbol(SymbolKind.FILE, sourceFile);
            final int id = writeSite("LINE", classSym, fileSym, line, 0, "", -1);
            lineSiteIds.put(key, id);
            return id;
        }
    }

    /**
     * Assigns a fresh BRANCH site for one conditional-jump instruction (§5: one per
     * instruction, never deduplicated). The JVM jump opcode is stored in the {@code slot}
     * column so the reader can render the comparison operator and derive the taken result.
     */
    public int branchSite(String classBinaryName, String sourceFile, int line, int opcode) {
        synchronized (lock) {
            final int classSym = symbol(SymbolKind.CLASS, classBinaryName);
            final int fileSym = sourceFile == null ? 0 : symbol(SymbolKind.FILE, sourceFile);
            return writeSite("BRANCH", classSym, fileSym, line, 0, "", opcode);
        }
    }

    // =====================================================================================
    // Symbols (§4)
    // =====================================================================================

    /**
     * Returns the stable agent-side id for {@code (kind, text)}, assigning and durably writing
     * a new dense-from-1 id on first sight.
     */
    public int symbol(SymbolKind kind, String text) {
        final String safe = text == null ? "" : text;
        final String key = kind.name() + ' ' + safe;
        final Integer cached = symbolIds.get(key);
        if (cached != null) {
            return cached;
        }
        synchronized (lock) {
            final Integer raced = symbolIds.get(key);
            if (raced != null) {
                return raced;
            }
            final int id = nextSymbolId++;
            writeRow(symbolsOut, id + "\t" + kind.name() + "\t" + escape(safe), "symbols.tsv");
            symbolIds.put(key, id);
            return id;
        }
    }

    /** TSV escaping per §4: backslash, tab, newline; everything else verbatim. */
    static String escape(String text) {
        int i = 0;
        final int n = text.length();
        while (i < n) {
            final char c = text.charAt(i);
            if (c == '\\' || c == '\t' || c == '\n') {
                break;
            }
            i++;
        }
        if (i == n) {
            return text;
        }
        final StringBuilder sb = new StringBuilder(n + 8);
        sb.append(text, 0, i);
        for (; i < n; i++) {
            final char c = text.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Flushes and closes both TSVs; used by tests — production relies on per-row flushing. */
    @Override
    public void close() {
        synchronized (lock) {
            closeQuietly(symbolsOut, "symbols.tsv");
            closeQuietly(sitesOut, "sites.tsv");
        }
    }

    // =====================================================================================
    // Internals
    // =====================================================================================

    /** Must hold {@code lock}. Writes one §5 row and returns its freshly assigned dense id. */
    private int writeSite(
            String kind, int classSym, int fileSym, int line, int memberSym, String desc, int slot) {
        final int id = nextSiteId++;
        writeRow(
                sitesOut,
                id + "\t" + kind + "\t" + classSym + "\t" + fileSym + "\t" + line + "\t" + memberSym
                        + "\t" + escape(desc) + "\t" + slot,
                "sites.tsv");
        return id;
    }

    /**
     * Wires the §10 trigger exactly once: the first site of a matching {@code class#method}
     * wins — with overloads the spec gives no way to choose, and "first transformed" at least
     * makes the choice deterministic per run and visible in the log.
     */
    private void maybeBindTrigger(String classBinaryName, String methodName, int siteId) {
        if (triggerClass == null
                || !triggerClass.equals(classBinaryName)
                || !triggerMethod.equals(methodName)
                || !triggerBound.compareAndSet(false, true)) {
            return;
        }
        try {
            HindsightRuntime.setTriggerSite(siteId);
        } catch (Throwable t) {
            // Unbooted runtime (tests, degraded boot): the trigger simply never fires.
        }
        if (log != null) {
            log.info("trigger bound: " + classBinaryName + "#" + methodName + " -> site " + siteId);
        }
    }

    /**
     * Never throws: a full disk must not fail class transformation. But an unwritten row means
     * instrumented bytecode now embeds site/symbol ids the tables will never resolve — every
     * event at those sites would be unattributable. Rather than let ids silently outrun the
     * durable tables, the first failure stops the recording (the §9 integrity-over-continuation
     * rule); transformation itself continues so the application is unaffected.
     */
    private void writeRow(BufferedWriter out, String row, String file) {
        try {
            out.write(row);
            out.write('\n');
            out.flush(); // eager: a crashed run must still parse (§4)
        } catch (IOException e) {
            if (log != null) {
                log.error("cannot write " + file + " row (stopping recording): " + row, e);
            }
            try {
                HindsightRuntime.stopRecording();
            } catch (Throwable ignored) {
                // unbooted runtime (tests): nothing to stop
            }
        }
    }

    private void closeQuietly(BufferedWriter out, String file) {
        try {
            out.close();
        } catch (IOException e) {
            if (log != null) {
                log.error("cannot close " + file, e);
            }
        }
    }

    private static BufferedWriter open(Path file) throws IOException {
        return Files.newBufferedWriter(
                file,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    /** What one class transform learned ahead of advice resolution; see class doc. */
    private record ClassScan(
            String classBinaryName,
            String sourceFile,
            Map<String, Integer> methodFirstLines,
            Map<String, String> methodParamSuffixes) {}
}
