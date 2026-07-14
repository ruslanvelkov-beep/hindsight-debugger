// SPDX-License-Identifier: Apache-2.0
package io.hindsight.agent;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The agent's diagnostics sink, backing {@code agent.log} in the recording directory
 * (FORMAT.md §1). Transform failures land here — the whole fail-open story is worthless if
 * the operator cannot find out <em>which</em> classes silently kept their original bytecode.
 *
 * <p>Every line is flushed immediately: transform errors typically precede crashes, and a
 * post-mortem must be able to read them. When {@code debug=true} everything is mirrored to
 * stderr; errors are mirrored to stderr regardless when the log file itself could not be
 * opened (diagnostics must degrade, never disappear). Logging never throws — this class is
 * called from class-load paths where an exception would take the transformed class down
 * with it.
 */
public final class AgentLog {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final PrintWriter out; // null when the file could not be opened
    private final boolean debugEnabled;

    /**
     * Opens (or appends to) {@code agent.log} in the given recording directory. On failure the
     * logger degrades to stderr-only rather than failing the agent.
     */
    public AgentLog(Path recordingDir, boolean debugEnabled) {
        this.debugEnabled = debugEnabled;
        PrintWriter writer = null;
        try {
            final Writer w = Files.newBufferedWriter(
                    recordingDir.resolve("agent.log"),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            writer = new PrintWriter(w);
        } catch (IOException e) {
            System.err.println("hindsight: cannot open agent.log in " + recordingDir + ": " + e);
        }
        this.out = writer;
    }

    /** Routine lifecycle information (config echo, install confirmation). */
    public void info(String message) {
        write("INFO", message, null, false);
    }

    /** Suspicious-but-survivable conditions (e.g. a trigger class outside all scopes). */
    public void warn(String message) {
        write("WARN", message, null, false);
    }

    /**
     * High-volume detail (per-class transformation notices); only written when debug=true and
     * NEVER mirrored to stderr — thousands of per-class lines through the synchronized writer
     * would flood the shared console and serialize concurrent class loading at node startup.
     */
    public void debug(String message) {
        if (debugEnabled) {
            write("DEBUG", message, null, false, false);
        }
    }

    /**
     * A failure the agent survived by failing open — always written to agent.log, mirrored to
     * stderr when debug is on (or when the log file is unavailable).
     */
    public void error(String message, Throwable thrown) {
        write("ERROR", message, thrown, true);
    }

    /** True when debug output was requested via the agent arguments. */
    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    private void write(String level, String message, Throwable thrown, boolean isError) {
        write(level, message, thrown, isError, true);
    }

    private synchronized void write(
            String level, String message, Throwable thrown, boolean isError, boolean mirrorable) {
        try {
            final String line = TIMESTAMP.format(LocalDateTime.now()) + " [" + level + "] " + message;
            if (out != null) {
                out.println(line);
                if (thrown != null) {
                    thrown.printStackTrace(out);
                }
                out.flush(); // eager: errors must survive a crash
            }
            if ((mirrorable && debugEnabled) || (isError && out == null)) {
                System.err.println("hindsight: " + line);
                if (thrown != null) {
                    thrown.printStackTrace(System.err);
                }
            }
        } catch (Throwable ignored) {
            // Diagnostics must never propagate into class-loading paths.
        }
    }
}
