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
            new ViewerApp(screen, engine, recording, sourcePath, parsed.dir(), parsed.tid()).run();
        } catch (IOException e) {
            code = 1;
            System.err.println("viewer: " + e.getMessage());
        } finally {
            if (screen != null) {
                try {
                    screen.stopScreen();
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
