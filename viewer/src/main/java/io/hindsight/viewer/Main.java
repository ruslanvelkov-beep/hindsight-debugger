// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer;

import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.ansi.UnixLikeTerminal;
import io.hindsight.viewer.engine.Engine;
import io.hindsight.viewer.source.SourcePath;
import io.hindsight.viewer.tui.CliArgs;
import io.hindsight.viewer.tui.ViewerApp;
import io.hindsight.reader.Recording;
import java.io.IOException;

/**
 * Command-line entry point for the Hindsight viewer (VIEWER.md §1): parse arguments,
 * build the source path, open the reconstruction engine and the recording, put the
 * terminal into an alternate-screen buffer, run the UI loop, and — above all —
 * always restore the terminal on the way out.
 *
 * <p>The recording is opened twice, once inside {@link Engine} (its private view for
 * reconstruction) and once here to hand the {@link ViewerApp} direct reader access
 * for the §9 search scans and per-thread record counts; both are closed in the
 * {@code finally}. Terminal restoration is done first in that {@code finally} so a
 * crash never leaves the user staring at a scrambled screen.
 */
public final class Main {

    private Main() {}

    /**
     * Clears terminal XON/XOFF flow control on the controlling tty via {@code stty -ixon} (§23.1):
     * with IXON on, the driver eats Ctrl-Q (XON) / Ctrl-S (XOFF), so the viewer's run-to-line
     * backward key (Ctrl-Q) never reaches lanterna. Called once after {@code startScreen}; not
     * restored explicitly — {@code stopScreen} puts back the termios lanterna snapshotted before
     * this, so the user's original IXON is preserved either way. Best-effort — swallows any failure
     * (no {@code /dev/tty}, non-Unix, missing {@code stty}).
     *
     * @param enable {@code false} to clear IXON so Ctrl-Q is delivered ({@code true} unused, kept
     *     for symmetry)
     */
    private static void setFlowControl(final boolean enable) {
        try {
            new ProcessBuilder("sh", "-c", "stty " + (enable ? "ixon" : "-ixon") + " < /dev/tty")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor();
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Best-effort: a terminal that can't do this simply keeps Ctrl-Q on flow control.
        }
    }

    /**
     * Program entry point.
     *
     * @param args the raw command-line arguments (see {@link CliArgs#USAGE})
     */
    public static void main(final String[] args) {
        CliArgs parsed;
        try {
            parsed = CliArgs.parse(args);
        } catch (CliArgs.CliException e) {
            System.err.println("viewer: " + e.getMessage());
            System.err.println(CliArgs.USAGE);
            System.exit(2);
            return;
        }

        SourcePath sourcePath = SourcePath.of(parsed.sources(), parsed.repos());

        Recording recording = null;
        Engine engine = null;
        Screen screen = null;
        int code = 0;
        try {
            recording = Recording.open(parsed.dir());
            engine = Engine.open(parsed.dir());
            screen = new DefaultTerminalFactory()
                    .setUnixTerminalCtrlCBehaviour(UnixLikeTerminal.CtrlCBehaviour.TRAP)
                    .createScreen();
            screen.startScreen();
            // Ctrl-Q is XON in the terminal's default flow control (Ctrl-S/Ctrl-Q = XOFF/XON), so
            // the tty driver swallows it and the run-to-line-backward key never reaches lanterna
            // (Ctrl-R/T/W have no such meaning and already work). Clear IXON on the controlling tty
            // AFTER lanterna's own raw-mode stty so it sticks; restore it in the finally. Best-effort.
            setFlowControl(false);
            new ViewerApp(screen, engine, recording, sourcePath, parsed.dir(), parsed.tid()).run();
        } catch (IOException e) {
            code = 1;
            System.err.println("viewer: " + e.getMessage());
        } finally {
            if (screen != null) {
                try {
                    screen.stopScreen();
                    // stopScreen restores the termios lanterna snapshotted BEFORE our -ixon, so the
                    // user's original IXON returns — no explicit restore (which would wrongly force
                    // IXON back on for someone who runs with it disabled in their shell).
                } catch (IOException ignored) {
                    // Best-effort terminal restore; nothing useful to do if it fails.
                }
            }
            if (engine != null) {
                engine.close();
            }
            if (recording != null) {
                recording.close();
            }
        }
        if (code != 0) {
            System.exit(code);
        }
    }
}
