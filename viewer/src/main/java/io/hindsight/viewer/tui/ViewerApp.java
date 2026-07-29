// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import io.hindsight.reader.Format;
import io.hindsight.reader.Rec;
import io.hindsight.reader.Recording;
import io.hindsight.reader.Site;
import io.hindsight.viewer.engine.Engine;
import io.hindsight.viewer.engine.FieldWriteHit;
import io.hindsight.viewer.engine.Frame;
import io.hindsight.viewer.engine.Instance;
import io.hindsight.viewer.engine.Local;
import io.hindsight.viewer.engine.ObjectNode;
import io.hindsight.viewer.engine.RecordInfo;
import io.hindsight.viewer.engine.SeqLocation;
import io.hindsight.viewer.engine.StepResult;
import io.hindsight.viewer.engine.Value;
import io.hindsight.viewer.highlight.JavaHighlighter;
import io.hindsight.viewer.source.SourcePath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Supplier;
import java.util.regex.PatternSyntaxException;

/**
 * The lanterna terminal UI (VIEWER.md §5–§12): the one class that touches a
 * terminal. It owns the {@link Screen}, drives the render/read/dispatch loop, keeps
 * all per-window view state (scroll offsets, selections, expansion, the browse-mode
 * source target, the navigation history trail, the search dialogs and the quit
 * confirmation), and translates key strokes into {@link Engine} navigation,
 * refreshing the whole model from the engine after every move.
 *
 * <p>Design notes tying back to the spec:
 * <ul>
 *   <li><b>Full redraw each frame.</b> {@link #render} rebuilds the entire screen
 *       from the engine's current position (§10); nothing is diffed by hand — the
 *       {@code TerminalScreen} does the minimal terminal writes.
 *   <li><b>Never throw out of the loop.</b> Every key is dispatched inside a
 *       try/catch that routes the message to the status row (§10); the loop keeps
 *       running so a bad recording corner never drops the user back to a broken
 *       terminal. Terminal restoration is the caller's {@code finally}.
 *   <li><b>Source auto-follow</b> is applied at render time driven by a
 *       {@code followPending} flag and a remembered file key, so a manual scroll is
 *       never yanked back (§6.1) yet a step always re-centres/follows; {@code o}
 *       (§11.2) re-centres on demand and leaves browse mode.
 *   <li><b>Per-window horizontal scroll</b> (§11.4): every window carries its own
 *       {@code hcol} and each row is clipped through {@link HScroll}; {@code <}/{@code >}
 *       scroll the focused window, plain Left/Right also scroll the non-tree windows.
 *   <li><b>Navigation history</b> (§11.5): every user navigation that moves the
 *       cursor appends one {@link HistoryTrail.HistoryEntry}; the Records window
 *       lists them and {@code Enter} seeks back without re-appending.
 *   <li><b>Regex method browse</b> (§11.7) uses {@link Engine#methodSites()} /
 *       {@link Engine#callsOf}; object usage (§9) still scans the reader directly.
 *   <li><b>Modal lists scroll incrementally</b> (§13.1): every dialog/overlay list
 *       persists its scroll offset and adjusts it edge-only via
 *       {@link ScrollMath#adjustScroll}, resetting to 0 on open/query/mode change.
 *   <li><b>Instance browse</b> (§13.2/§14.1/§14.2/§15.1): {@code Tab} in the {@code /}
 *       dialog toggles calls ↔ instances ({@link InstanceBrowse}, whose query also
 *       accepts {@code <regex>#<id>}/{@code #<id>} and matches simple class names
 *       unless qualified with {@code .}/{@code $}); the instance rows themselves are
 *       expandable object-tree roots (a dedicated {@link TreeView}) resolved as-of
 *       the current cursor, and {@code f} jumps to the selected row's owning
 *       instance's first mention while the dialog stays open.
 *   <li><b>Search-dialog focus model + persistence</b> (§15.2–§15.5): the {@code /}
 *       dialog has explicit QUERY/LIST foci (the query line edits vs. the numbered,
 *       counted result list navigates; {@code /} returns to the query), and its one
 *       {@link SearchDialog} instance survives closing, so reopening restores the
 *       whole dialog state.
 *   <li><b>Source-line cursor + variable history</b> (§13.4/§13.5): the Source
 *       window keeps a cursor line (rendered in the gutter while focused) that
 *       ↑/↓ move; {@code v} parses it via {@link FieldDecl} and lists the matching
 *       {@link Engine#fieldWrites} in a jumpable modal.
 *   <li><b>Exec-record landings</b> (§17.1/§17.2): every step and jump lands on the
 *       exec set (LINE/METHOD_ENTER/METHOD_EXIT, {@link ExecRecords}) — never on a
 *       metadata record — and stepping honours a selected outer frame by seeking to
 *       its as-of record first (§17.5, {@link Engine#frameAsOf}).
 *   <li><b>Open-file browser</b> (§17.7): {@code F} is a persistent directory-tree
 *       browser over the source roots ({@link FileTree}) with a per-keystroke regex
 *       filter over file names; {@code Ctrl-D} pops up the recording's full path and
 *       the Threads border carries its name (§17.6).
 * </ul>
 */
public final class ViewerApp {

    private static final int MIN_COLS = 80;
    private static final int MIN_ROWS = 24;
    private static final int TAB_WIDTH = 4;
    private static final int HSTEP = 4;
    private static final int RESULT_CAP = 200;
    private static final int VAR_HISTORY_CAP = 500;
    private static final int VAR_CHANGE_CAP = 1000;
    private static final long SEEK_CAP = 5_000_000L;
    private static final String DOT = " · ";

    /** The five focusable windows, in Tab-cycle order (VIEWER.md §5, §11.3/§11.5, §12.1). */
    private enum Win {
        SOURCE, THREADS, STACK, LOCALS, RECORDS
    }

    /** Which query modal is open (§9, §11.7 regex method browse); the §17.7 file browser is separate. */
    private enum DialogKind {
        METHOD, OBJECT
    }

    /** Which part of the {@code /} dialog receives keys (§15.2): the query line or the result list. */
    private enum DialogFocus {
        QUERY, LIST
    }

    /**
     * Live state of an open modal dialog: a query, its result rows + jump targets, a note (the
     * §15.3 count line for the {@code /} kind, the bottom cap note otherwise), and a persistent
     * scroll offset (§13.1 — adjusted incrementally each frame, reset to 0 on open, on query
     * change and on the §13.2 mode toggle). The {@code METHOD} kind carries the calls ↔ instances
     * mode flag and the §15.2 QUERY/LIST focus (which replaced the §14.2 list-active heuristic);
     * in instances mode the result rows are the roots of {@code instanceTree} (§14.2, expanded in
     * place; {@code instanceRows} holds the backing per-root instances and {@code rows} mirrors
     * their labels for the empty/no-matches checks) and {@code hcol} is the tree's horizontal
     * scroll. {@code listViewH} is the list's visible row count, captured at render so LIST-focus
     * paging (§15.2 Ctrl-F/Ctrl-B, PgDn/PgUp) knows the page size. The {@code /} instance of this
     * class outlives its closing (§15.5): {@code ViewerApp.searchDialog} keeps it across
     * Esc/reopen so mode, query, results, selection, scroll, expansion and focus all survive.
     */
    private static final class SearchDialog {
        final DialogKind kind;
        final StringBuilder query = new StringBuilder();
        List<String> rows = List.of();
        List<long[]> targets = List.of();
        // §20.3: for the `.` (OBJECT) dialog, the object id each result row references (0 = none),
        // so Enter on a lambda row can jump to its first mention instead of the plain record seek.
        List<Long> objectIds = List.of();
        List<InstanceBrowse.Row> instanceRows = List.of();
        TreeView instanceTree;
        String note = "";
        int sel;
        int scroll;
        int hcol;
        boolean instancesMode;
        DialogFocus focus = DialogFocus.QUERY;
        int listViewH = 1;

        SearchDialog(final DialogKind kind) {
            this.kind = kind;
        }
    }

    /**
     * Live state of the §17.7 open-file browser: the once-built {@link FileTree} (index +
     * expansion memory + filter), the incrementally-edited query, the §15.2 QUERY/LIST focus,
     * and the list selection/scroll. The one instance is persistent like the {@code /} dialog
     * (§15.5): {@code ViewerApp.fileDialog} survives Esc, so reopening with {@code F} restores
     * filter, selection, scroll and expansion exactly; {@code fileDialogOpen} is the only
     * open/closed switch. {@code listViewH} is the visible row count captured at render so
     * paging knows the page size.
     */
    private static final class FileDialog {
        final FileTree tree;
        final StringBuilder query = new StringBuilder();
        DialogFocus focus = DialogFocus.QUERY;
        int sel;
        int scroll;
        int listViewH = 1;

        FileDialog(final FileTree tree) {
            this.tree = tree;
        }
    }

    /**
     * Live state of the §13.5/§16.1 variable-history overlay: the shown field's identity
     * ({@code fileName}/{@code fieldName}) and the enclosing instance id ({@code thisId})
     * captured when {@code v} was pressed, the current this-instance filter flag
     * ({@code thisOnly}, toggled by Tab), and the currently-built rows/hits/cap-note (rebuilt on
     * every toggle via {@link #rebuildVarHistory}; values are as-of the write, not the cursor).
     * {@code listViewH} is the list's visible row count captured at render so Ctrl-F/Ctrl-B and
     * PgDn/PgUp know the page size; {@code scroll} is the §13.1 persistent offset.
     */
    private static final class VarHistoryWindow {
        final String fileName;
        final String fieldName;
        final long thisId;
        boolean thisOnly;
        List<String> rows = List.of();
        List<FieldWriteHit> hits = List.of();
        String note = "";
        int sel;
        int scroll;
        int hcol;
        int listViewH = 1;

        VarHistoryWindow(final String fileName, final String fieldName, final long thisId) {
            this.fileName = fileName;
            this.fieldName = fieldName;
            this.thisId = thisId;
        }
    }

    /**
     * Live state of the §16.4/§19.4 Source Code in-file search: the {@code active} flag (search
     * mode on/off), the incrementally-edited {@code query}, the {@link InFileSearch#occurrences}
     * table ({@code {line, col}} 1-based per matching line), the {@code current} index into it (or
     * {@code -1}) and the §19.4 pending-boundary direction {@code boundary} ({@code null} =
     * disarmed; non-{@code null} inverts the whole field colour and arms the two-press wrap — §20.7
     * dropped the old red no-match state, so the field is always yellow otherwise). One instance
     * lives for the app; {@code active} toggles it.
     */
    private static final class SourceSearch {
        boolean active;
        final StringBuilder query = new StringBuilder();
        List<int[]> occurrences = List.of();
        int current = -1;
        InFileSearch.Dir boundary;
    }

    /**
     * Live state of the §20.9 global variable-change window ({@code V}): a case-insensitive
     * field-name {@code filter} (empty = all), the currently-built rows/hits/cap-note (rebuilt on
     * every filter keystroke via {@link #rebuildVarChanges}, capped at {@link #VAR_CHANGE_CAP}), the
     * §15.2-style QUERY/LIST {@code focus} (the filter line edits vs. the result list navigates),
     * the list selection/scroll/hscroll and the visible row count captured at render for paging.
     * Rows are formatted by the shared {@link VarHistory#row} so they render identically to the v1.6
     * variable-history window. One instance lives while the window is open ({@code varChanges != null}).
     */
    private static final class VarChangeWindow {
        final StringBuilder filter = new StringBuilder();
        DialogFocus focus = DialogFocus.QUERY;
        List<String> rows = List.of();
        List<FieldWriteHit> hits = List.of();
        String note = "";
        int sel;
        int scroll;
        int hcol;
        int listViewH = 1;
    }

    private final Screen screen;
    private final Engine engine;
    private final Recording recording;
    private final SourcePath sourcePath;
    private final JavaHighlighter highlighter = new JavaHighlighter();
    private final List<Integer> tids;
    // §17.6: the recording directory's full absolute path (Ctrl-D popup) and bare name (border).
    private final String recordingPath;
    private final String recordingName;

    private final TreeView localsTree;
    private final Map<String, List<List<JavaHighlighter.Span>>> highlightCache = new HashMap<>();
    private final HistoryTrail history = new HistoryTrail();

    private Win focus = Win.SOURCE;
    private boolean running = true;
    private String status = "";
    private boolean helpOverlay;
    private boolean quitConfirm;
    private SearchDialog dialog;
    // §15.5: the one persistent `/` dialog — kept across Esc/close so reopening restores it.
    private SearchDialog searchDialog;
    // §17.7: the persistent open-file browser state (lazily built on first `F`) + its open flag.
    private FileDialog fileDialog;
    private boolean fileDialogOpen;
    // §17.6: whether the Ctrl-D "Recording" path popup is open.
    private boolean recordingPopup;
    private VarHistoryWindow varHist;
    // §20.9: the global variable-change window opened by `V` (null when closed).
    private VarChangeWindow varChanges;
    // §16.3: the Records "jump to record number" modal (null when closed).
    private RecordJump recordJump;
    // §16.4: the Source Code in-file search state (one instance; `active` toggles it).
    private final SourceSearch sourceSearch = new SourceSearch();
    // §16.2: the tid last shown, so afterMove collapses Locals only on a real cross-thread jump.
    private int lastShownTid;
    private TerminalPosition cursorPos;

    // §14.4: the Locals selection's value/descriptor, captured by renderLocals for the info line.
    private Value localsSelValue;
    private String localsSelDescriptor;

    // Source window state.
    private int srcTop = 1;
    private int srcHcol;
    private String shownFileKey;
    private boolean followPending = true;
    private boolean recenterSource;
    private int srcViewH = 1;
    private int srcFileLines;
    private boolean srcResolved;

    // Source-line cursor (§13.4): 1-based, rendered in the gutter while Source is focused.
    private int srcCursor = 1;
    private boolean srcCursorPending = true;

    // Source browse mode (§11.8): a file opened for reading, decoupled from execution.
    private boolean browseMode;
    private String browseKey = "";
    private String browseName = "";
    private List<String> browseLines = List.of();
    private int browseLine = 1;
    private boolean browseCenterPending;

    // List window scroll/selection/hscroll.
    private int threadSel;
    private int threadScroll;
    private int threadsHcol;
    private int threadsViewH = 1;
    private int stackSel;
    private int stackScroll;
    private int stackHcol;
    private int stackViewH = 1;
    private int localsHcol;
    private int localsViewH = 1;
    private int recordsSel;
    private int recordsScroll;
    private int recordsHcol;
    private int recordsViewH = 1;

    /**
     * Wires the UI to its backing engine/recording/source-path and applies the initial thread.
     *
     * @param screen a started (or startable) lanterna screen the loop renders to
     * @param engine the reconstruction engine (already open)
     * @param recording the same recording, used directly for the §9 object scan and record counts
     * @param sourcePath resolves class/file to source lines and modules; its roots seed the
     *     §17.7 open-file browser
     * @param recordingDir the recording directory, shown on the Threads border and in the
     *     {@code Ctrl-D} popup (§17.6)
     * @param initialTid the {@code --tid} thread to focus, or empty for the engine's default
     */
    public ViewerApp(
            final Screen screen,
            final Engine engine,
            final Recording recording,
            final SourcePath sourcePath,
            final Path recordingDir,
            final OptionalInt initialTid) {
        this.screen = screen;
        this.engine = engine;
        this.recording = recording;
        this.sourcePath = sourcePath;
        Path absDir = recordingDir != null ? recordingDir.toAbsolutePath().normalize() : null;
        this.recordingPath = absDir != null ? absDir.toString() : "";
        Path dirName = absDir != null ? absDir.getFileName() : null;
        this.recordingName = dirName != null ? dirName.toString() : this.recordingPath;
        this.tids = engine.threads();
        this.localsTree = new TreeView(engine::children);
        if (initialTid.isPresent()) {
            StepResult r = engine.selectThread(initialTid.getAsInt());
            if (!r.moved()) {
                status = r.message();
            }
        }
        threadSel = Math.max(0, tids.indexOf(engine.currentTid()));
        stackSel = 0;
        lastShownTid = engine.currentTid(); // §16.2: seed so the first in-thread step never collapses
        appendHistory(); // seed the trail with the initial landing so Records is never empty (§11.5)
    }

    /**
     * Runs the render/read/dispatch loop until the user quits or the input stream ends. Does not
     * start or stop the screen — the caller owns that (so the terminal is restored even if this
     * throws).
     *
     * @throws IOException if a screen refresh or input read fails unrecoverably
     */
    public void run() throws IOException {
        while (running) {
            screen.doResizeIfNecessary();
            render();
            screen.refresh();
            KeyStroke key = screen.readInput();
            if (key == null) {
                continue;
            }
            if (key.getKeyType() == KeyType.EOF) {
                running = false;
                break;
            }
            handleKey(key);
        }
    }

    // ---- rendering ----

    private void render() {
        screen.clear();
        cursorPos = null;
        TextGraphics g = screen.newTextGraphics();
        TerminalSize sz = screen.getTerminalSize();
        int cols = sz.getColumns();
        int rows = sz.getRows();
        if (cols < MIN_COLS || rows < MIN_ROWS) {
            drawTooSmall(g, cols, rows);
            screen.setCursorPosition(null);
            return;
        }
        Layout layout = Layout.compute(cols, rows);
        RecordInfo ri = engine.recordInfo();
        renderSource(g, layout.source());
        List<Layout.Rect> r = layout.right();
        renderThreads(g, r.get(0));
        renderStack(g, r.get(1));
        renderLocals(g, r.get(2));
        renderRecords(g, r.get(3));
        renderStatus(g, cols, layout.statusRow(), ri);
        renderHelp(g, cols, layout.helpRow());
        if (helpOverlay) {
            drawHelpOverlay(g, cols, rows);
        }
        if (dialog != null) {
            drawDialog(g, cols, rows);
        }
        if (fileDialogOpen) {
            drawFileDialog(g, cols, rows);
        }
        if (varHist != null) {
            drawVarHistory(g, cols, rows);
        }
        if (varChanges != null) {
            drawVarChanges(g, cols, rows);
        }
        if (recordJump != null) {
            drawRecordJump(g, cols, rows);
        }
        if (recordingPopup) {
            drawRecordingPopup(g, cols, rows);
        }
        if (quitConfirm) {
            drawQuitConfirm(g, cols, rows);
        }
        screen.setCursorPosition(cursorPos);
    }

    private void drawTooSmall(final TextGraphics g, final int cols, final int rows) {
        String msg = "terminal too small (need >= 80x24)";
        int row = Math.max(0, rows / 2);
        put(g, Math.max(0, (cols - msg.length()) / 2), row, msg, Palette.ERROR, Palette.BG, true);
    }

    private void renderSource(final TextGraphics g, final Layout.Rect rect) {
        boolean focused = focus == Win.SOURCE;
        drawBox(g, rect, sourceTitle(), Palette.BG, focused ? Palette.FOCUS : Palette.UNFOCUS, focused);
        if (sourceSearch.active) {
            // §20.7: the full-width search field is drawn ON the title row (covering any browse
            // filename); the §18.1 frame text is suppressed while searching.
            renderSourceSearchField(g, rect, focused ? Palette.FOCUS : Palette.UNFOCUS, focused);
        } else {
            drawSourceFrameText(g, rect, focused);
        }
        int ix = rect.col() + 1;
        int iy = rect.row() + 1;
        int iw = rect.width() - 2;
        int ih = rect.height() - 2;
        if (iw <= 0 || ih <= 0) {
            return;
        }
        srcViewH = ih;
        if (browseMode) {
            renderBrowse(g, ix, iy, iw, ih);
        } else {
            renderExecSource(g, ix, iy, iw, ih);
        }
    }

    /** The exec-mode Source title; §18.1 renders the frame text separately, past a dashed gap. */
    private static final String SOURCE_TITLE = "Source Code [c]";

    /**
     * The Source window title (§15.7/§18.1): browse mode keeps
     * {@code Source Code [c] — <fileName>}; exec mode is the plain fixed title — the SELECTED
     * frame's {@code SimpleClass.method} is drawn separately by {@link #drawSourceFrameText}
     * so the border dashes stay visible in the ten-column gap.
     */
    private String sourceTitle() {
        if (browseMode) {
            return SOURCE_TITLE + " — " + browseName;
        }
        return SOURCE_TITLE;
    }

    /**
     * The §18.1 exec-mode frame text: {@code ␣<SimpleClass>.<method>␣} (one space each side),
     * put on the top border AFTER {@link #drawBox} at the offset the text previously started —
     * title start + title length + ten — so the border's own dashes remain visible between the
     * fixed title and the frame text, and after it. Browse mode (and a frame-less exec cursor)
     * draws nothing.
     */
    private void drawSourceFrameText(final TextGraphics g, final Layout.Rect rect, final boolean focused) {
        if (browseMode) {
            return;
        }
        Frame f = selectedFrameOrNull();
        if (f == null) {
            return;
        }
        int start = rect.col() + 3 + SOURCE_TITLE.length() + 10; // title text begins at col+3
        int maxLen = rect.col() + rect.width() - 1 - start; // clip before the corner, like a title
        if (maxLen <= 0) {
            return;
        }
        put(g, start, rect.row(), clip(" " + FrameText.simple(f) + " ", maxLen),
                focused ? Palette.FOCUS : Palette.UNFOCUS, Palette.BG, focused);
    }

    /** The currently selected stack frame — recorded or unrecorded (§14.5) — or {@code null}. */
    private Frame selectedFrameOrNull() {
        List<Frame> frames = engine.stack();
        int sf = engine.selectedFrame();
        return sf >= 0 && sf < frames.size() ? frames.get(sf) : null;
    }

    /**
     * §19: "green context" — the INNERMOST (execution) frame is recorded but line-less (the §18.3
     * callsOnly case), so there is no line trace at the cursor. It gates the §19.2 {@code j}/{@code
     * J} jump-to-line-trace bindings (which replace {@code e}/{@code E}), the §19.3 green info-line
     * suffix and the base-help swap. Guards an empty stack.
     */
    private boolean greenContext() {
        int innermost = engine.stack().size() - 1;
        return innermost >= 0 && !engine.frameHasLines(innermost);
    }

    private void renderExecSource(final TextGraphics g, final int ix, final int iy, final int iw, final int ih) {
        List<Frame> frames = engine.stack();
        int sf = engine.selectedFrame();
        Frame frame = sf >= 0 && sf < frames.size() ? frames.get(sf) : null;
        String cls = frame != null ? frame.classBinary() : null;
        String file = frame != null ? frame.fileName() : null;
        String fileKey = cls + "|" + file;

        Optional<List<String>> src = frame != null ? sourcePath.lines(cls, file) : Optional.empty();
        srcResolved = src.isPresent();
        srcFileLines = src.map(List::size).orElse(0);
        int line = frame != null && frame.currentLine() >= 1 ? frame.currentLine() : 1;
        // §18.3/§19.1: the method DECLARATION line + dark-green treatment applies ONLY when the
        // INNERMOST (execution) frame is selected and is recorded but line-less. A selected
        // non-innermost line-less frame keeps its own reconstructed call-site line (frame
        // .currentLine()) with the ordinary §12.3 frame marker — never the declaration line.
        boolean declTreatment = DeclLine.innermostLineless(
                sf, frames.size(), frame != null && frame.recorded(), engine.frameHasLines(sf));
        if (declTreatment && srcResolved) {
            line = DeclLine.find(src.get(), frame.methodName(), simpleClass(cls), line);
        }

        boolean fileChanged = !Objects.equals(fileKey, shownFileKey);
        if (srcResolved) {
            if (fileChanged || recenterSource) {
                srcTop = ScrollMath.center(line, ih, srcFileLines);
                srcHcol = 0;
            } else if (followPending) {
                srcTop = ScrollMath.autoFollow(srcTop, line, ih, srcFileLines);
            } else {
                srcTop = ScrollMath.clampTop(srcTop, srcFileLines, ih);
            }
        }
        shownFileKey = fileKey;
        followPending = false;
        recenterSource = false;
        // §13.4: reset the source cursor to the exec/frame line after every navigation
        // and whenever the shown file changed; always clamp to the shown file.
        if (srcCursorPending || fileChanged) {
            srcCursor = line;
        }
        srcCursor = clamp(srcCursor, 1, Math.max(1, srcFileLines));
        srcCursorPending = false;

        if (frame == null) {
            put(g, ix, iy, "<no active frame at this record>", Palette.DIM, Palette.BG, false);
            return;
        }
        if (!srcResolved) {
            String label = simpleClass(cls) + "." + (frame.methodName() != null ? frame.methodName() : "?");
            put(g, ix, iy, clip("<source not found on source path>", iw), Palette.ERROR, Palette.BG, false);
            put(g, ix, iy + 1, clip(label, iw), Palette.DIM, Palette.BG, false);
            put(g, ix, iy + 2, clip("line " + line + "  (" + (file != null ? file : "?") + ")", iw),
                    Palette.DIM, Palette.BG, false);
            return;
        }

        List<String> lines = src.get();
        List<List<JavaHighlighter.Span>> spans =
                highlightCache.computeIfAbsent(fileKey, k -> highlighter.highlight(lines));
        // §14.5: an unrecorded frame's line gets the frame-line highlight, never the exec one.
        // §18.3/§19.1: the innermost line-less EXEC frame's highlight is dark green; a selected
        // non-innermost line-less frame is not `exec`, so it keeps the §12.3 frame marker.
        boolean exec = sf == frames.size() - 1 && frame.recorded();
        TextColor hlBg = !exec ? Palette.FRAME_LINE_BG
                : declTreatment ? Palette.EXEC_NOLINE_BG
                : Palette.EXEC_LINE_BG;
        renderCodeLines(g, ix, iy, iw, ih, lines, spans, srcTop, srcHcol, line, hlBg,
                sourceCursorLine(), searchQuery());
    }

    private void renderBrowse(final TextGraphics g, final int ix, final int iy, final int iw, final int ih) {
        srcResolved = true;
        srcFileLines = browseLines.size();
        int line = Math.min(Math.max(browseLine, 1), Math.max(1, srcFileLines));
        if (browseCenterPending) {
            srcTop = ScrollMath.center(line, ih, srcFileLines);
            srcHcol = 0;
            srcCursor = line; // §13.4: cursor starts on the requested line in browse mode
            browseCenterPending = false;
        } else {
            srcTop = ScrollMath.clampTop(srcTop, srcFileLines, ih);
        }
        srcCursor = clamp(srcCursor, 1, Math.max(1, srcFileLines));
        if (srcFileLines == 0) {
            put(g, ix, iy, clip("<empty file>", iw), Palette.DIM, Palette.BG, false);
            return;
        }
        List<List<JavaHighlighter.Span>> spans =
                highlightCache.computeIfAbsent(browseKey, k -> highlighter.highlight(browseLines));
        // Browse marks the requested line with the dim (frame) highlight, never the exec highlight.
        renderCodeLines(g, ix, iy, iw, ih, browseLines, spans, srcTop, srcHcol, line,
                Palette.FRAME_LINE_BG, sourceCursorLine(), searchQuery());
    }

    /** The §13.4 cursor line to render, or -1 — the cursor shows only while Source is focused. */
    private int sourceCursorLine() {
        return focus == Win.SOURCE ? srcCursor : -1;
    }

    /** §20.7: the in-file search query to highlight in the source body, or {@code null} when not searching. */
    private String searchQuery() {
        return sourceSearch.active ? sourceSearch.query.toString() : null;
    }

    /** §20.7 field prefix; {@code [ Search: } is followed by the input area then {@code ]}. */
    private static final String SEARCH_PREFIX = "[ Search: ";

    /**
     * The §20.7 in-file search field drawn over the Source title row: a FULL-WIDTH, symmetric box
     * {@code [ Search: <query………> ]} whose {@code [} sits at {@code rect.col()+2} and {@code ]} at
     * {@code rect.col()+width-3} (two columns inside each corner), so it covers the whole title row
     * — including any browse-mode {@code — <file>} title. Foreground is ALWAYS yellow on black; the
     * §19.4 pending-boundary flag still inverts the WHOLE field (fg/bg swap) to signal end-of-file.
     * A query longer than the input area scrolls to show its TAIL ({@link InFileSearch#visibleInput}).
     * The single border cell between {@code ]} and the corner is restored so no title leaks there.
     * The terminal cursor stays visible at the input caret — one column after {@code Search: },
     * advanced by the visible query length, clamped into the input area.
     */
    private void renderSourceSearchField(
            final TextGraphics g, final Layout.Rect rect, final TextColor border, final boolean bold) {
        int startX = rect.col() + 2; // the '[' column
        int endBracket = rect.col() + rect.width() - 3; // the ']' column: 2 cols inside the corner
        int prefixLen = SEARCH_PREFIX.length();
        int inputW = endBracket - startX - prefixLen; // the input area fills the rest of the row
        if (inputW <= 0) {
            return; // too narrow to draw a sensible field
        }
        String visible = InFileSearch.visibleInput(sourceSearch.query.toString(), inputW);
        StringBuilder box = new StringBuilder(SEARCH_PREFIX).append(visible);
        while (box.length() < prefixLen + inputW) {
            box.append(' ');
        }
        box.append(']'); // ends exactly at endBracket
        // §20.7: always yellow-on-black; the §19.4 armed-boundary state inverts fg/bg.
        boolean invert = sourceSearch.boundary != null;
        put(g, startX, rect.row(), box.toString(),
                invert ? TextColor.ANSI.BLACK : TextColor.ANSI.YELLOW,
                invert ? TextColor.ANSI.YELLOW : TextColor.ANSI.BLACK, false);
        // Restore the ONE border cell between ']' (endBracket) and the corner (endBracket+2) so a
        // long browse title cannot leak through it — the field itself covers everything up to ']'.
        put(g, endBracket + 1, rect.row(), "─", border, Palette.BG, bold);
        // §19.4 visible cursor: the caret column, clamped into the input area.
        int caret = startX + prefixLen + Math.min(visible.length(), inputW - 1);
        cursorPos = new TerminalPosition(caret, rect.row());
    }

    /** The lines currently shown in the Source window: browse lines, else the selected frame's source. */
    private List<String> shownSourceLines() {
        if (browseMode) {
            return browseLines;
        }
        Frame f = selectedFrameOrNull();
        return f == null ? List.of() : sourcePath.lines(f.classBinary(), f.fileName()).orElse(List.of());
    }

    private void renderCodeLines(
            final TextGraphics g,
            final int ix,
            final int iy,
            final int iw,
            final int ih,
            final List<String> lines,
            final List<List<JavaHighlighter.Span>> spans,
            final int top,
            final int hcol,
            final int highlightLine,
            final TextColor highlightBg,
            final int cursorLine,
            final String searchQuery) {
        int fileLines = lines.size();
        int gutterW = Integer.toString(Math.max(1, fileLines)).length() + 1;
        int numW = Math.max(1, gutterW - 1);
        int contentX = ix + gutterW;
        int contentW = iw - gutterW;
        for (int row = 0; row < ih; row++) {
            int lineNo = top + row;
            if (lineNo > fileLines) {
                break;
            }
            int y = iy + row;
            boolean isHl = lineNo == highlightLine;
            boolean isCursor = lineNo == cursorLine;
            TextColor lineBg = isHl ? highlightBg : Palette.BG;

            // §13.4: the cursor line marks only its gutter cells; the code keeps its own colours.
            put(g, ix, y, String.format("%" + numW + "d ", lineNo),
                    isCursor ? Palette.SELECTION_FG : Palette.DIM,
                    isCursor ? Palette.SELECTION_BG : lineBg, false);
            if (contentW <= 0) {
                continue;
            }
            if (isHl) {
                put(g, contentX, y, " ".repeat(contentW), Palette.FG, lineBg, false);
            }
            List<SourceLayout.Cell> cells = SourceLayout.cells(lines.get(lineNo - 1), spans.get(lineNo - 1), TAB_WIDTH);
            int sx = 0;
            int ci = hcol;
            while (ci < cells.size() && sx < contentW) {
                JavaHighlighter.Style st = cells.get(ci).style();
                StringBuilder run = new StringBuilder();
                while (ci < cells.size() && sx + run.length() < contentW && cells.get(ci).style() == st) {
                    run.append(cells.get(ci).ch());
                    ci++;
                }
                put(g, contentX + sx, y, run.toString(), Palette.syntax(st), lineBg, false);
                sx += run.length();
            }
            // §20.7: overlay the in-file search match highlight on every visible occurrence of the
            // query on this line — the matched cells are repainted reverse-video ON TOP of the normal
            // render (and any line highlight), so all matches stand out, not just the current-match line.
            if (searchQuery != null && !searchQuery.isEmpty()) {
                for (int[] span : InFileSearch.matchColumns(lines.get(lineNo - 1), searchQuery, TAB_WIDTH)) {
                    int from = Math.max(span[0], hcol);
                    int to = Math.min(span[1], hcol + contentW);
                    for (int vc = from; vc < to; vc++) {
                        char c = vc < cells.size() ? cells.get(vc).ch() : ' ';
                        put(g, contentX + (vc - hcol), y, String.valueOf(c),
                                Palette.SEARCH_MATCH_FG, Palette.SEARCH_MATCH_BG, false);
                    }
                }
            }
        }
    }

    private void renderThreads(final TextGraphics g, final Layout.Rect rect) {
        boolean focused = focus == Win.THREADS;
        drawBox(g, rect, "Threads [t]", Palette.BG, focused ? Palette.FOCUS : Palette.UNFOCUS, focused);
        drawRecordingName(g, rect, focused);
        int ix = rect.col() + 1;
        int iy = rect.row() + 1;
        int iw = rect.width() - 2;
        int ih = rect.height() - 2;
        threadsViewH = Math.max(1, ih);
        if (iw <= 0 || ih <= 0) {
            return;
        }
        int cur = engine.currentTid();
        List<String> rows = new ArrayList<>();
        List<TextColor> rowFg = new ArrayList<>();
        for (int tid : tids) {
            String mark = tid == cur ? "* " : "  ";
            rows.add(mark + "t" + tid + "  \"" + engine.threadName(tid) + "\"  (" + count(tid) + ")");
            // §18.3: threads with no LINE records at all render light green; '*' text unchanged.
            rowFg.add(engine.threadHasLines(tid) ? Palette.FG : Palette.CALLS_ONLY_FG);
        }
        threadSel = clamp(threadSel, 0, Math.max(0, rows.size() - 1));
        threadScroll = ScrollMath.adjustScroll(threadScroll, threadSel, ih, rows.size());
        drawRows(g, ix, iy, iw, ih, rows, threadSel, threadScroll, threadsHcol, true, rowFg);
    }

    /**
     * The recording directory's NAME on the Threads window's top border (§17.6/§18.2):
     * rendered as {@code ␣<name>␣} with the TRAILING space ending exactly 2 columns left of
     * the top-right corner, drawn AFTER {@link #drawBox} so it overwrites border dashes, and
     * kept to the right of the {@code Threads [t]} title. The §17.6 budget for the name itself
     * is {@link RecordingName#MAX_BORDER_NAME} (21, first-10 {@code …} last-10 beyond it — the
     * spaces are extra); a narrower window shrinks the budget further so the block never
     * collides with the title.
     */
    private void drawRecordingName(final TextGraphics g, final Layout.Rect rect, final boolean focused) {
        if (recordingName.isEmpty()) {
            return;
        }
        int x = rect.col();
        int w = rect.width();
        int trailCol = x + w - 3; // the trailing space's column: 2 left of the corner at x+w-1
        int titleEnd = x + 2 + "Threads [t]".length() + 2; // first border column after " Threads [t] "
        int maxLen = trailCol - 1 - titleEnd; // name's last column is trailCol-1; leading space stays >= titleEnd
        if (maxLen < 1) {
            return;
        }
        String name = RecordingName.abbreviate(recordingName, Math.min(RecordingName.MAX_BORDER_NAME, maxLen));
        if (name.isEmpty()) {
            return;
        }
        // The spaces visually separate the name from the border dashes, like a title (§18.2).
        put(g, trailCol - name.length() - 1, rect.row(), " " + name + " ",
                focused ? Palette.FOCUS : Palette.UNFOCUS, Palette.BG, focused);
    }

    private void renderStack(final TextGraphics g, final Layout.Rect rect) {
        boolean focused = focus == Win.STACK;
        drawBox(g, rect, "Stack [s]", Palette.BG, focused ? Palette.FOCUS : Palette.UNFOCUS, focused);
        int ix = rect.col() + 1;
        int iy = rect.row() + 1;
        int iw = rect.width() - 2;
        int ih = rect.height() - 2;
        stackViewH = Math.max(1, ih);
        if (iw <= 0 || ih <= 0) {
            return;
        }
        List<Frame> frames = engine.stack();
        int sf = engine.selectedFrame();
        List<String> rows = new ArrayList<>();
        for (int i = frames.size() - 1; i >= 0; i--) {
            Frame f = frames.get(i);
            String mark = i == sf ? "* " : "  ";
            String m = f.methodName() != null ? f.methodName() : "?";
            rows.add(mark + simpleClass(f.classBinary()) + "." + m + Descriptors.simplifyParams(f.descriptor())
                    + " (" + (f.fileName() != null ? f.fileName() : "?") + ":" + f.currentLine() + ")");
        }
        stackSel = clamp(stackSel, 0, Math.max(0, rows.size() - 1));
        stackScroll = ScrollMath.adjustScroll(stackScroll, stackSel, ih, rows.size());
        // §14.5: unrecorded (capture-only) frames render DIM, same row text; selection as usual.
        // §18.3: recorded but line-less (enter/exit-only) frames render light green.
        for (int r = 0; r < ih; r++) {
            int idx = stackScroll + r;
            if (idx < 0 || idx >= rows.size()) {
                continue;
            }
            boolean s = idx == stackSel;
            int frameIndex = frames.size() - 1 - idx; // rows are innermost-first
            TextColor fg = !frames.get(frameIndex).recorded() ? Palette.DIM
                    : engine.frameHasLines(frameIndex) ? Palette.FG
                    : Palette.CALLS_ONLY_FG;
            // §20.6: a green (line-less) frame keeps CALLS_ONLY_FG when selected; others invert as usual.
            TextColor selFg = fg == Palette.CALLS_ONLY_FG ? Palette.CALLS_ONLY_FG : Palette.SELECTION_FG;
            put(g, ix, iy + r, HScroll.clip(rows.get(idx), stackHcol, iw),
                    s ? selFg : fg,
                    s ? Palette.SELECTION_BG : Palette.BG, false);
        }
    }

    private void renderLocals(final TextGraphics g, final Layout.Rect rect) {
        boolean focused = focus == Win.LOCALS;
        drawBox(g, rect, "Locals [l]", Palette.BG, focused ? Palette.FOCUS : Palette.UNFOCUS, focused);
        int ix = rect.col() + 1;
        int iy = rect.row() + 1;
        int iw = rect.width() - 2;
        int ih = rect.height() - 2;
        localsViewH = Math.max(1, ih);
        if (iw <= 0 || ih <= 0) {
            return;
        }
        localsSelValue = null;
        localsSelDescriptor = null;
        List<Frame> frames = engine.stack();
        int sf = engine.selectedFrame();
        Frame sel = sf >= 0 && sf < frames.size() ? frames.get(sf) : null;
        if (sel != null && !sel.recorded()) {
            // §14.5: a capture-only frame has no reconstructable locals.
            localsTree.setRoots(List.of());
            put(g, ix, iy, clip("(unrecorded frame — no locals)", iw), Palette.DIM, Palette.BG, false);
            return;
        }
        List<Local> locals = engine.locals(sf);
        List<ObjectNode> roots = new ArrayList<>();
        for (Local l : locals) {
            roots.add(ObjectNode.of(l.name() + " = " + l.value().display(), l.value()));
        }
        localsTree.setRoots(roots);
        // §14.4: remember the selected row's value (and, for a top-level local, its descriptor)
        // so renderStatus can name its full type while Locals is focused.
        List<TreeView.Row> treeRows = localsTree.rows();
        if (!treeRows.isEmpty()) {
            TreeView.Row row = treeRows.get(localsTree.selection());
            localsSelValue = row.node().value();
            int rootIdx = localsTree.selectedRootIndex();
            if (row.depth() == 0 && rootIdx >= 0 && rootIdx < locals.size()) {
                localsSelDescriptor = locals.get(rootIdx).descriptor();
            }
        }
        drawTree(g, ix, iy, iw, ih, localsTree, localsHcol);
    }

    private void renderRecords(final TextGraphics g, final Layout.Rect rect) {
        boolean focused = focus == Win.RECORDS;
        drawBox(g, rect, "Records [r]", Palette.BG, focused ? Palette.FOCUS : Palette.UNFOCUS, focused);
        int ix = rect.col() + 1;
        int iy = rect.row() + 1;
        int iw = rect.width() - 2;
        int ih = rect.height() - 2;
        recordsViewH = Math.max(1, ih);
        if (iw <= 0 || ih <= 0) {
            return;
        }
        List<HistoryTrail.HistoryEntry> entries = history.entries();
        if (entries.isEmpty()) {
            put(g, ix, iy, clip("(no navigation yet — step to record history)", iw), Palette.DIM, Palette.BG, false);
            return;
        }
        List<String> rows = new ArrayList<>(entries.size());
        for (HistoryTrail.HistoryEntry e : entries) {
            rows.add(HistoryTrail.render(e));
        }
        recordsSel = clamp(recordsSel, 0, rows.size() - 1);
        recordsScroll = ScrollMath.adjustScroll(recordsScroll, recordsSel, ih, rows.size());
        drawRows(g, ix, iy, iw, ih, rows, recordsSel, recordsScroll, recordsHcol, true);
    }

    /** §19.3 green info-line suffix appended (after 10 spaces) to the position text in a green context. */
    private static final String GREEN_INFO_SUFFIX = "No line trace info available";

    private void renderStatus(final TextGraphics g, final int cols, final int row, final RecordInfo ri) {
        String pos = positionText(ri);
        if (!status.isEmpty()) {
            // A transient status message takes precedence over the whole line, incl. the §19.3 suffix.
            put(g, 0, row, clipPad(status + "   —   " + pos, cols), Palette.FG, Palette.BG, false);
            return;
        }
        // §19.3: the normal focus-dependent position text first, then — in a green context — 10
        // spaces + "No line trace info available" in the §18.3 CALLS_ONLY_FG green (a second put()
        // so the suffix carries its own colour). Absent on any line-traced (non-green) position.
        put(g, 0, row, clipPad(pos, cols), Palette.FG, Palette.BG, false);
        if (greenContext()) {
            int at = pos.length() + 10;
            if (at < cols) {
                put(g, at, row, clip(GREEN_INFO_SUFFIX, cols - at), Palette.CALLS_ONLY_FG, Palette.BG, false);
            }
        }
    }

    /**
     * The focus-sensitive position part of the status row (§14.4/§15.6/§17.5): the selected
     * instance's full class + id while the {@code /} dialog is in instances mode, the selected
     * Locals row's full type while Locals is focused, the selected stack frame's fully-qualified
     * {@code Class.method} while Stack is focused (grey §14.5 frames included), the SELECTED
     * frame's as-of record while Source is focused on a non-innermost recorded frame (§17.5),
     * and the compact record info everywhere else (or as fallback when the focused window has
     * no selection).
     */
    private String positionText(final RecordInfo ri) {
        if (dialog != null && dialog.kind == DialogKind.METHOD && dialog.instancesMode) {
            InstanceBrowse.Row row = selectedInstanceRow();
            if (row != null) {
                return (row.classBinary() != null ? row.classBinary() : "?") + "#" + row.objectId();
            }
        } else if (dialog == null && focus == Win.LOCALS) {
            String type = TypeName.of(localsSelValue, localsSelDescriptor, recording::objectClassName);
            if (type != null) {
                return type;
            }
        } else if (dialog == null && focus == Win.STACK) {
            Frame f = selectedFrameOrNull();
            if (f != null) {
                return FrameText.qualified(f); // §15.6
            }
        } else if (dialog == null && focus == Win.SOURCE) {
            String framePos = selectedFramePositionText();
            if (framePos != null) {
                return framePos; // §17.5: the SELECTED (non-innermost) frame's as-of record
            }
        }
        return "seq " + ri.seq() + DOT + "t" + ri.tid() + DOT + ri.siteText();
    }

    /**
     * The §17.5 Source-focused position text: with a non-innermost RECORDED frame selected, the
     * compact record info ({@code seq · t · site}) of THAT frame's as-of record
     * ({@link Engine#frameAsOf}) — the record its shown state is reconstructed as-of — instead
     * of the cursor's. {@code null} (caller falls back to the cursor's info) for the innermost
     * frame, an unrecorded frame ({@code found=false}) or an out-of-range selection.
     */
    private String selectedFramePositionText() {
        List<Frame> frames = engine.stack();
        int sf = engine.selectedFrame();
        if (sf < 0 || sf >= frames.size() - 1) {
            return null; // innermost (or nothing) selected: today's behaviour
        }
        SeqLocation loc = engine.frameAsOf(sf);
        if (!loc.found()) {
            return null; // unrecorded frame: no as-of record to show
        }
        Rec r = rec(loc.tid(), loc.index());
        return "seq " + r.seq() + DOT + "t" + loc.tid() + DOT + asOfSiteText(r, frames.get(sf));
    }

    /**
     * {@code Class.method (File:line)} for a frame's as-of record, mirroring the engine's
     * {@code RecordInfo.siteText} shape; the frame itself backfills any part the record's
     * site does not carry (e.g. a LINE site without a member symbol).
     */
    private String asOfSiteText(final Rec r, final Frame f) {
        Site s = recording.site(r.location());
        String cls = s != null ? recording.symbolText(s.classSym()) : null;
        if (cls == null) {
            cls = f.classBinary();
        }
        String method = s != null && s.memberSym() != 0 ? recording.symbolText(s.memberSym()) : null;
        if (method == null) {
            method = f.methodName();
        }
        String file = s != null ? recording.symbolText(s.fileSym()) : null;
        if (file == null) {
            file = f.fileName();
        }
        int line = s != null ? s.line() : f.currentLine();
        String methodPart = method != null && !method.isEmpty() ? "." + method : "";
        return simpleClass(cls) + methodPart + " (" + (file != null ? file : "?") + ":" + line + ")";
    }

    /** The instance owning the selected row of the instances-mode result tree (§14.2), or null. */
    private InstanceBrowse.Row selectedInstanceRow() {
        if (dialog == null || dialog.instanceTree == null || dialog.instanceRows.isEmpty()) {
            return null;
        }
        int root = dialog.instanceTree.selectedRootIndex();
        return root >= 0 && root < dialog.instanceRows.size() ? dialog.instanceRows.get(root) : null;
    }

    private void renderHelp(final TextGraphics g, final int cols, final int row) {
        // §19.2/§19.3: the exit-frame/jump slot swaps with the context — the two are mutually
        // exclusive: e/E exit-frame when line-traced, j/J jump-to-line-trace in a green context.
        String exitOrJump = greenContext() ? "j/J jump to line-trace" : "e/E exit-frame";
        // §20.11a: the status help drops ^R/^Q/V/^X (they live in the F1 help window) and ends with
        // "F1 help".
        String base = "F7 step  F8 over  ⇧F7/⇧F8 reverse  " + exitOrJump + "  o origin  "
                + "/ calls  . obj  F file  ^D recording  c/s/t/l/r focus  q quit  F1 help";
        String ctx = switch (focus) {
            case SOURCE -> browseMode
                    ? "↑↓ cursor  v var-history  Alt-F search  <> hscroll  o return  g/G/Home/End"
                    : "↑↓ cursor  v var-history  Alt-F search  ←→/<> hscroll  g/G/Home/End";
            case THREADS -> "↑↓ select  ↵ switch  ←→/<> hscroll";
            case STACK -> "↑↓ select frame  ←→/<> hscroll";
            case LOCALS -> "↑↓ move  →/↵ expand  ← collapse  <> hscroll";
            case RECORDS -> "↑↓ select  ↵ jump  c record#  ←→/<> hscroll"; // §20.12: c (was j)
        };
        put(g, 0, row, clipPad(ctx + "  |  " + base, cols), Palette.DIM, Palette.BG, false);
    }

    // ---- drawing primitives ----

    private void drawTree(
            final TextGraphics g, final int ix, final int iy, final int iw, final int ih,
            final TreeView tree, final int hcol) {
        List<TreeView.Row> rows = tree.rows();
        tree.ensureVisible(ih, rows.size());
        List<String> text = new ArrayList<>(rows.size());
        for (TreeView.Row r : rows) {
            String glyph = r.expandable() ? (r.expanded() ? "▾ " : "▸ ") : "  ";
            text.add("  ".repeat(r.depth()) + glyph + r.node().label());
        }
        drawRows(g, ix, iy, iw, ih, text, tree.selection(), tree.scroll(), hcol, true);
    }

    private void drawRows(
            final TextGraphics g,
            final int ix,
            final int iy,
            final int iw,
            final int ih,
            final List<String> rows,
            final int sel,
            final int scroll,
            final int hcol,
            final boolean showSel) {
        drawRows(g, ix, iy, iw, ih, rows, sel, scroll, hcol, showSel, null);
    }

    /**
     * The per-row-foreground variant of {@link #drawRows(TextGraphics, int, int, int, int, List,
     * int, int, int, boolean)} (§18.3, Threads green marking): {@code rowFg} supplies each row's
     * unselected foreground ({@code null} = all {@link Palette#FG}); selection inversion still wins.
     */
    private void drawRows(
            final TextGraphics g,
            final int ix,
            final int iy,
            final int iw,
            final int ih,
            final List<String> rows,
            final int sel,
            final int scroll,
            final int hcol,
            final boolean showSel,
            final List<TextColor> rowFg) {
        for (int r = 0; r < ih; r++) {
            int idx = scroll + r;
            if (idx < 0 || idx >= rows.size()) {
                continue;
            }
            boolean s = showSel && idx == sel;
            TextColor fg = rowFg != null ? rowFg.get(idx) : Palette.FG;
            // §20.6: a green (§18.3 line-less) row keeps its CALLS_ONLY_FG even when selected, so its
            // green identity survives the highlight; every other row uses the usual SELECTION_FG.
            TextColor selFg = fg == Palette.CALLS_ONLY_FG ? Palette.CALLS_ONLY_FG : Palette.SELECTION_FG;
            put(g, ix, iy + r, HScroll.clip(rows.get(idx), hcol, iw),
                    s ? selFg : fg, s ? Palette.SELECTION_BG : Palette.BG, false);
        }
    }

    private void drawBox(
            final TextGraphics g,
            final Layout.Rect rect,
            final String title,
            final TextColor bg,
            final TextColor color,
            final boolean bold) {
        int x = rect.col();
        int y = rect.row();
        int w = rect.width();
        int h = rect.height();
        if (w < 2 || h < 2) {
            return;
        }
        put(g, x, y, "┌" + "─".repeat(w - 2) + "┐", color, bg, bold);
        put(g, x, y + h - 1, "└" + "─".repeat(w - 2) + "┘", color, bg, bold);
        for (int r = y + 1; r < y + h - 1; r++) {
            put(g, x, r, "│", color, bg, bold);
            put(g, x + w - 1, r, "│", color, bg, bold);
        }
        if (w >= 6) {
            put(g, x + 2, y, clip(" " + title + " ", w - 3), color, bg, bold);
        }
    }

    private void put(
            final TextGraphics g,
            final int col,
            final int row,
            final String s,
            final TextColor fg,
            final TextColor bg,
            final boolean bold) {
        if (row < 0 || s == null) {
            return;
        }
        TerminalSize sz = g.getSize();
        if (row >= sz.getRows()) {
            return;
        }
        int maxCols = sz.getColumns();
        int c = col;
        String str = s;
        if (c < 0) {
            int drop = -c;
            if (drop >= str.length()) {
                return;
            }
            str = str.substring(drop);
            c = 0;
        }
        if (c >= maxCols) {
            return;
        }
        if (c + str.length() > maxCols) {
            str = str.substring(0, maxCols - c);
        }
        if (str.isEmpty()) {
            return;
        }
        g.setForegroundColor(fg);
        g.setBackgroundColor(bg);
        if (bold) {
            g.enableModifiers(SGR.BOLD);
        } else {
            g.disableModifiers(SGR.BOLD);
        }
        g.putString(c, row, str);
    }

    private void drawDialog(final TextGraphics g, final int cols, final int rows) {
        int w = clamp(cols - 6, 24, 74);
        int h = clamp(rows - 6, 8, 20);
        int x = Math.max(0, (cols - w) / 2);
        int y = Math.max(0, (rows - h) / 2);
        g.setForegroundColor(Palette.FG);
        g.setBackgroundColor(Palette.DIALOG_BG);
        g.disableModifiers(SGR.BOLD);
        g.fillRectangle(new TerminalPosition(x, y), new TerminalSize(w, h), ' ');
        String title = switch (dialog.kind) {
            case METHOD -> dialog.instancesMode
                    ? "Browse instances [/]  ⇥ calls"
                    : "Browse method calls [/]  ⇥ instances";
            case OBJECT -> "Find object usage [.]";
        };
        drawBox(g, new Layout.Rect(x, y, w, h), title, Palette.DIALOG_BG, Palette.FOCUS, true);

        int ix = x + 1;
        int iy = y + 1;
        int iw = w - 2;
        String prompt = switch (dialog.kind) {
            case METHOD -> "regex: ";
            case OBJECT -> "query: ";
        };
        put(g, ix, iy, clipPad(prompt + dialog.query, iw), Palette.FG, Palette.DIALOG_BG, false);
        if (dialog.kind != DialogKind.METHOD || dialog.focus == DialogFocus.QUERY) {
            // §15.2: the terminal cursor sits on the query line only in QUERY focus; it stays
            // hidden (cursorPos == null) while the `/` dialog's LIST has the focus.
            cursorPos = new TerminalPosition(Math.min(x + w - 2, ix + prompt.length() + dialog.query.length()), iy);
        }

        int hintRow = y + h - 2;
        String hint = switch (dialog.kind) {
            case METHOD -> searchHint();
            case OBJECT -> "↑↓ select  ↵ jump  Esc close";
        };
        put(g, ix, hintRow, clip(hint, iw), Palette.DIM, Palette.DIALOG_BG, false);

        int listTop = iy + 2;
        int listBottom = hintRow;
        if (dialog.kind == DialogKind.METHOD) {
            // §15.3: the count line (or "invalid regex") sits DIM directly under the query; it
            // replaced the bottom cap note, so the list always runs down to the hint row.
            if (!dialog.note.isEmpty()) {
                put(g, ix, iy + 1, clip(dialog.note, iw), Palette.DIM, Palette.DIALOG_BG, false);
            }
        } else if (!dialog.note.isEmpty()) {
            listBottom = hintRow - 1;
            put(g, ix, listBottom, clip(dialog.note, iw), Palette.DIM, Palette.DIALOG_BG, false);
        }
        int listH = listBottom - listTop;
        if (dialog.kind == DialogKind.METHOD) {
            dialog.listViewH = Math.max(1, listH); // §15.2: page size for Ctrl-F/Ctrl-B, PgDn/PgUp
        }

        if (dialog.rows.isEmpty()) {
            put(g, ix, listTop, clip(dialog.query.length() == 0 ? "(type to search)" : "(no matches)", iw),
                    Palette.DIM, Palette.DIALOG_BG, false);
            return;
        }
        if (dialog.kind == DialogKind.METHOD && dialog.instancesMode) {
            drawInstanceTree(g, ix, listTop, iw, listH);
            return;
        }
        boolean numbered = dialog.kind == DialogKind.METHOD; // §15.4: `/` rows only
        dialog.sel = clamp(dialog.sel, 0, dialog.rows.size() - 1);
        // §13.1: adjust the persisted offset incrementally — never recompute from 0.
        dialog.scroll = ScrollMath.adjustScroll(dialog.scroll, dialog.sel, listH, dialog.rows.size());
        for (int r = 0; r < listH; r++) {
            int idx = dialog.scroll + r;
            if (idx >= dialog.rows.size()) {
                break;
            }
            boolean s = idx == dialog.sel;
            String text = numbered
                    ? DialogFormat.number(idx, dialog.rows.size()) + dialog.rows.get(idx)
                    : dialog.rows.get(idx);
            put(g, ix, listTop + r, clipPad(text, iw),
                    s ? Palette.SELECTION_FG : Palette.FG, s ? Palette.SELECTION_BG : Palette.DIALOG_BG, false);
        }
    }

    /** The `/` dialog's hint line, reflecting the active §15.2 focus and the mode. */
    private String searchHint() {
        if (dialog.focus == DialogFocus.QUERY) {
            return dialog.instancesMode
                    ? "type to filter  ↑↓ results  ⇥ calls  ^U clear  Esc close"
                    : "type to filter  ↑↓ results  ⇥ instances  ^U clear  Esc close";
        }
        return dialog.instancesMode
                ? "↑↓ move  →/↵ expand  ← collapse  f first-mention  / query  Esc close"
                : "↑↓ select  ↵ jump  ^F/^B page  g/G  / query  Esc close";
    }

    /**
     * The §14.2 instances-mode result list: each instance row is the root of an in-place
     * expandable object tree, rendered Locals-style (glyphs + indentation) with the §13.1
     * edge-only scrolling the {@link TreeView} provides. Children are re-fetched from the
     * engine on every draw, so expanded trees re-resolve as-of the cursor's current seq.
     * Root rows carry the §15.4 {@code 1.}–{@code N.} numbers; child rows carry a blank
     * prefix of the same width so they stay indented under their root; the whole row —
     * prefix included — participates in the {@code <}/{@code >} horizontal scroll.
     */
    private void drawInstanceTree(final TextGraphics g, final int ix, final int iy, final int iw, final int listH) {
        TreeView tree = dialog.instanceTree;
        List<TreeView.Row> rows = tree.rows();
        tree.ensureVisible(listH, rows.size());
        int roots = dialog.instanceRows.size();
        for (int r = 0; r < listH; r++) {
            int idx = tree.scroll() + r;
            if (idx >= rows.size()) {
                break;
            }
            TreeView.Row row = rows.get(idx);
            String glyph = row.expandable() ? (row.expanded() ? "▾ " : "▸ ") : "  ";
            String prefix = row.depth() == 0
                    ? DialogFormat.number(rootOrdinal(row.key()), roots)
                    : DialogFormat.childPad(roots);
            String text = prefix + "  ".repeat(row.depth()) + glyph + row.node().label();
            boolean s = idx == tree.selection();
            put(g, ix, iy + r, clipPad(HScroll.clip(text, dialog.hcol, iw), iw),
                    s ? Palette.SELECTION_FG : Palette.FG, s ? Palette.SELECTION_BG : Palette.DIALOG_BG, false);
        }
    }

    /** The 0-based root index a tree row's structural key starts with ({@code "2/0/1"} → 2). */
    private static int rootOrdinal(final String key) {
        int slash = key.indexOf('/');
        try {
            return Integer.parseInt(slash < 0 ? key : key.substring(0, slash));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The §16.1 variable-history overlay, drawn as a centered modal like {@link #drawDialog} but
     * 75% of the terminal width ({@link VarHistory#width}). The title carries the entry count and
     * the active scope ({@link VarHistory#title}/{@link VarHistory#scope}); rows are numbered
     * {@code 1.}–{@code N.} ({@link DialogFormat#number}) prefixed to the seq/old→new text, and the
     * whole numbered row is clipped through the Left/Right {@code hcol}.
     */
    private void drawVarHistory(final TextGraphics g, final int cols, final int rows) {
        int w = VarHistory.width(cols);
        int h = clamp(rows - 6, 8, 20);
        int x = Math.max(0, (cols - w) / 2);
        int y = Math.max(0, (rows - h) / 2);
        g.setForegroundColor(Palette.FG);
        g.setBackgroundColor(Palette.DIALOG_BG);
        g.disableModifiers(SGR.BOLD);
        g.fillRectangle(new TerminalPosition(x, y), new TerminalSize(w, h), ' ');
        int count = varHist.rows.size();
        String scope = VarHistory.scope(varHist.thisOnly, varHist.thisId, varHistoryThisSimpleClass());
        drawBox(g, new Layout.Rect(x, y, w, h), VarHistory.title(count, scope),
                Palette.DIALOG_BG, Palette.FOCUS, true);
        int ix = x + 1;
        int iy = y + 1;
        int iw = w - 2;
        int hintRow = y + h - 2;
        put(g, ix, hintRow, clip("↑↓ select  ←→ hscroll  ^F/^B pg  g/G ends  ⇥ this/all  ↵ jump  Esc", iw),
                Palette.DIM, Palette.DIALOG_BG, false);
        int listBottom = hintRow;
        if (!varHist.note.isEmpty()) {
            listBottom = hintRow - 1;
            put(g, ix, listBottom, clip(varHist.note, iw), Palette.DIM, Palette.DIALOG_BG, false);
        }
        int listH = listBottom - iy;
        varHist.listViewH = Math.max(1, listH); // §16.1: page size for ^F/^B, PgDn/PgUp
        varHist.sel = clamp(varHist.sel, 0, Math.max(0, count - 1));
        varHist.scroll = ScrollMath.adjustScroll(varHist.scroll, varHist.sel, listH, count);
        for (int r = 0; r < listH; r++) {
            int idx = varHist.scroll + r;
            if (idx >= count) {
                break;
            }
            boolean s = idx == varHist.sel;
            String text = DialogFormat.number(idx, count) + varHist.rows.get(idx);
            put(g, ix, iy + r, HScroll.clip(text, varHist.hcol, iw),
                    s ? Palette.SELECTION_FG : Palette.FG, s ? Palette.SELECTION_BG : Palette.DIALOG_BG, false);
        }
    }

    /** The simple class name of the var-history enclosing instance (for the §16.1 scope), or {@code ?}. */
    private String varHistoryThisSimpleClass() {
        if (varHist.thisId == 0) {
            return "?";
        }
        String cls = recording.objectClassName(varHist.thisId);
        return cls == null ? "?" : Format.simpleName(cls);
    }

    /**
     * The §20.9 global variable-change window: a centered modal (75% width, like the §16.1
     * variable-history window) with a {@code filter:} line (a visible caret in QUERY focus), a DIM
     * count note ({@code showing first 1000 of N} / {@code N results}), the ascending-seq field-write
     * rows (shared {@link VarHistory#row} formatting, §13.1 edge-only scrolling, Left/Right {@code hcol}
     * clip) and a hint row.
     */
    private void drawVarChanges(final TextGraphics g, final int cols, final int rows) {
        VarChangeWindow w = varChanges;
        int width = VarHistory.width(cols);
        int h = clamp(rows - 6, 8, 20);
        int x = Math.max(0, (cols - width) / 2);
        int y = Math.max(0, (rows - h) / 2);
        g.setForegroundColor(Palette.FG);
        g.setBackgroundColor(Palette.DIALOG_BG);
        g.disableModifiers(SGR.BOLD);
        g.fillRectangle(new TerminalPosition(x, y), new TerminalSize(width, h), ' ');
        drawBox(g, new Layout.Rect(x, y, width, h), "Variable changes [V]", Palette.DIALOG_BG, Palette.FOCUS, true);
        int ix = x + 1;
        int iy = y + 1;
        int iw = width - 2;
        String filterLine = "filter: " + w.filter;
        put(g, ix, iy, clipPad(filterLine, iw), Palette.FG, Palette.DIALOG_BG, false);
        if (w.focus == DialogFocus.QUERY) {
            cursorPos = new TerminalPosition(Math.min(x + width - 2, ix + filterLine.length()), iy);
        }
        put(g, ix, iy + 1, clip(w.note, iw), Palette.DIM, Palette.DIALOG_BG, false);
        int hintRow = y + h - 2;
        put(g, ix, hintRow, clip("type filter  ↑↓ select  ←→/<> hscroll  ^F/^B pg  g/G ends  ↵ jump  Esc", iw),
                Palette.DIM, Palette.DIALOG_BG, false);
        int listTop = iy + 2;
        int listH = hintRow - listTop;
        w.listViewH = Math.max(1, listH);
        int count = w.rows.size();
        if (count == 0) {
            put(g, ix, listTop, clip(w.filter.length() == 0 ? "(no field writes)" : "(no matches)", iw),
                    Palette.DIM, Palette.DIALOG_BG, false);
            return;
        }
        w.sel = clamp(w.sel, 0, count - 1);
        w.scroll = ScrollMath.adjustScroll(w.scroll, w.sel, listH, count);
        for (int r = 0; r < listH; r++) {
            int idx = w.scroll + r;
            if (idx >= count) {
                break;
            }
            boolean s = idx == w.sel;
            put(g, ix, listTop + r, HScroll.clip(w.rows.get(idx), w.hcol, iw),
                    s ? Palette.SELECTION_FG : Palette.FG, s ? Palette.SELECTION_BG : Palette.DIALOG_BG, false);
        }
    }

    private void drawHelpOverlay(final TextGraphics g, final int cols, final int rows) {
        // §20.11b: the help window lists EVERY command, including the ones dropped from the status
        // line (Ctrl-R/Ctrl-Q run-to-line, V var-changes, Ctrl-X quit) and the §20.12/§20.13 keys.
        String[] lines = {
            "Hindsight Viewer — keys",
            "",
            "F7 / Shift+F7         step into / reverse step",
            "F8 / Shift+F8         step over / reverse step over",
            "                      (Apple Terminal sends ⇧F7/⇧F8 as F15/F16)",
            "e / E                 exit current frame: forward / backward",
            "j / J                 jump line-trace next/prev (green context)",
            "Ctrl+R / Ctrl+Q       run to current line: forward / backward",
            "o                     origin — return Source to the exec line",
            "Ctrl+D                show the recording directory's full path",
            "c t s l r             focus Source/Threads/Stack/Locals/Records",
            "c (in Records)        jump to a record by seq  (Tab → Source)",
            "Tab / Shift+Tab       cycle focus forward / backward",
            "Arrows PgUp PgDn      navigate the focused window",
            "Ctrl+P / Ctrl+N       scroll focused window up / down one line",
            "Ctrl+K / Ctrl+L       hscroll left / right (= < / > and ←/→)",
            "< / >                 horizontal-scroll the focused window",
            "^F / ^B  Home End     page / jump to ends;  g / G  top/bottom",
            "v                     variable history for the cursor line",
            "V                     global variable-change list (field filter)",
            "Alt-F                 Source in-file search (↵ next  ⌥↵/↑↓ prev)",
            "/  .  F               browse calls / find object / open file",
            "Tab in /              toggle calls ↔ instances;  f first mention",
            "green                 enter/exit-only (no line records)",
            "?  F1                 toggle this help window",
            "q  Ctrl+X  Ctrl+C     quit (q / Ctrl+X confirm; Ctrl+C now)",
            "",
            "press ? / F1 / Esc to close",
        };
        int w = 0;
        for (String l : lines) {
            w = Math.max(w, l.length());
        }
        w = Math.min(cols - 2, w + 4);
        int h = Math.min(rows - 2, lines.length + 2);
        int x = Math.max(0, (cols - w) / 2);
        int y = Math.max(0, (rows - h) / 2);
        g.setForegroundColor(Palette.FG);
        g.setBackgroundColor(Palette.DIALOG_BG);
        g.disableModifiers(SGR.BOLD);
        g.fillRectangle(new TerminalPosition(x, y), new TerminalSize(w, h), ' ');
        drawBox(g, new Layout.Rect(x, y, w, h), "Help", Palette.DIALOG_BG, Palette.FOCUS, true);
        for (int i = 0; i < lines.length && i + 1 < h - 1; i++) {
            put(g, x + 2, y + 1 + i, clip(lines[i], w - 4), Palette.FG, Palette.DIALOG_BG, false);
        }
    }

    /** The small centered §12.4 quit-confirmation box drawn over everything else. */
    private void drawQuitConfirm(final TextGraphics g, final int cols, final int rows) {
        String msg = "Really quit the viewer?";
        String hint = "y / ↵ quit    any other key stays";
        int w = Math.min(cols - 2, Math.max(msg.length(), hint.length()) + 6);
        int h = Math.min(rows - 2, 5);
        int x = Math.max(0, (cols - w) / 2);
        int y = Math.max(0, (rows - h) / 2);
        g.setForegroundColor(Palette.FG);
        g.setBackgroundColor(Palette.DIALOG_BG);
        g.disableModifiers(SGR.BOLD);
        g.fillRectangle(new TerminalPosition(x, y), new TerminalSize(w, h), ' ');
        drawBox(g, new Layout.Rect(x, y, w, h), "Quit", Palette.DIALOG_BG, Palette.FOCUS, true);
        put(g, x + 3, y + 1, clip(msg, w - 6), Palette.FG, Palette.DIALOG_BG, false);
        put(g, x + 3, y + 3, clip(hint, w - 6), Palette.DIM, Palette.DIALOG_BG, false);
    }

    /**
     * The §17.6 {@code Recording} popup, selection-friendly per §18.5: FULL terminal width — a
     * top border row carrying the {@code Recording} title (drawBox-style corners), the FULL
     * absolute path wrapped at the terminal width ({@link RecordingName#wrap}) and rendered on
     * bare full-width lines with NO side decoration (no {@code │}, no leading padding), then a
     * bottom border row — so a terminal selection of a long path grabs clean text.
     */
    private void drawRecordingPopup(final TextGraphics g, final int cols, final int rows) {
        List<String> lines = RecordingName.wrap(recordingPath, cols);
        int h = Math.min(rows, lines.size() + 2);
        int y = Math.max(0, (rows - h) / 2);
        put(g, 0, y, "┌" + "─".repeat(Math.max(0, cols - 2)) + "┐", Palette.FOCUS, Palette.DIALOG_BG, true);
        put(g, 2, y, clip(" Recording ", cols - 3), Palette.FOCUS, Palette.DIALOG_BG, true);
        for (int i = 0; i < lines.size() && y + 1 + i < y + h - 1; i++) {
            put(g, 0, y + 1 + i, clipPad(lines.get(i), cols), Palette.FG, Palette.DIALOG_BG, false);
        }
        put(g, 0, y + h - 1, "└" + "─".repeat(Math.max(0, cols - 2)) + "┘", Palette.FOCUS, Palette.DIALOG_BG, true);
    }

    /**
     * One key while the §17.6 {@code Recording} popup is open — it consumes every key: only Esc
     * closes it (Ctrl-C stays the emergency quit).
     */
    private void handleRecordingPopup(final KeyStroke k) {
        if (isCtrlC(k)) {
            running = false;
            return;
        }
        if (k.getKeyType() == KeyType.Escape) {
            recordingPopup = false;
        }
    }

    /** The §16.3 "jump to record number" modal: a digit input line and (when present) an error line. */
    private void drawRecordJump(final TextGraphics g, final int cols, final int rows) {
        String prompt = "seq: " + recordJump.text();
        String err = recordJump.error();
        String hint = "digits then ↵ jump    Esc close";
        int content = Math.max(prompt.length(), Math.max(err.length(), hint.length()));
        int w = Math.min(cols - 2, Math.max(24, content + 6));
        int h = Math.min(rows - 2, err.isEmpty() ? 5 : 6);
        int x = Math.max(0, (cols - w) / 2);
        int y = Math.max(0, (rows - h) / 2);
        g.setForegroundColor(Palette.FG);
        g.setBackgroundColor(Palette.DIALOG_BG);
        g.disableModifiers(SGR.BOLD);
        g.fillRectangle(new TerminalPosition(x, y), new TerminalSize(w, h), ' ');
        drawBox(g, new Layout.Rect(x, y, w, h), "Jump to record", Palette.DIALOG_BG, Palette.FOCUS, true);
        put(g, x + 3, y + 1, clip(prompt, w - 6), Palette.FG, Palette.DIALOG_BG, false);
        int hintRow = y + h - 2;
        if (!err.isEmpty()) {
            put(g, x + 3, y + 2, clip(err, w - 6), Palette.ERROR, Palette.DIALOG_BG, false);
        }
        put(g, x + 3, hintRow, clip(hint, w - 6), Palette.DIM, Palette.DIALOG_BG, false);
        cursorPos = new TerminalPosition(Math.min(x + w - 2, x + 3 + prompt.length()), y + 1);
    }

    // ---- key dispatch ----

    private void handleKey(final KeyStroke k) {
        try {
            TerminalSize sz = screen.getTerminalSize();
            if (sz.getColumns() < MIN_COLS || sz.getRows() < MIN_ROWS) {
                // No room for the confirm box (§12.4) — quit immediately (Ctrl-X is a q synonym, §20.11c).
                if (isCtrlC(k) || (isChar(k, 'q') && !k.isCtrlDown()) || isCtrlX(k)) {
                    running = false;
                }
                return;
            }
            if (quitConfirm) {
                handleQuitConfirm(k);
                return;
            }
            if (helpOverlay) {
                KeyType t = k.getKeyType();
                if (t == KeyType.F1 || t == KeyType.Escape || isChar(k, '?')) {
                    helpOverlay = false;
                } else if (isCtrlC(k)) {
                    running = false;
                } else if ((isChar(k, 'q') && !k.isCtrlDown()) || isCtrlX(k)) {
                    helpOverlay = false;
                    quitConfirm = true; // §20.11c Ctrl-X is a q synonym
                }
                return;
            }
            if (recordingPopup) {
                // §17.6: the popup consumes all keys; Esc closes (Ctrl-C still quits).
                handleRecordingPopup(k);
                return;
            }
            if (dialog != null) {
                handleDialog(k);
                return;
            }
            if (fileDialogOpen) {
                handleFileDialog(k);
                return;
            }
            if (varHist != null) {
                handleVarHistory(k);
                return;
            }
            if (varChanges != null) {
                // §20.9: the global variable-change window consumes all keys (Ctrl-C still quits).
                handleVarChanges(k);
                return;
            }
            if (recordJump != null) {
                // §16.3: the modal consumes all keys (Ctrl-C still the emergency quit).
                handleRecordJump(k);
                return;
            }
            if (sourceSearch.active) {
                // §16.4: intercept BEFORE global/nav so typing does not trigger focus/step keys.
                handleSourceSearch(k);
                return;
            }
            if (isCtrlC(k)) {
                running = false;
                return;
            }
            // §12.4 quit-confirm; §20.11c Ctrl-X is a synonym for q. Ctrl-Q is NOT quit (it is the
            // §20.10 reverse run-to-line), so the plain-q check must exclude a Ctrl modifier.
            if ((isChar(k, 'q') && !k.isCtrlDown()) || isCtrlX(k)) {
                quitConfirm = true;
                return;
            }
            if (handleGlobal(k)) {
                return;
            }
            handleNav(k);
        } catch (RuntimeException e) {
            status = "error: " + describe(e);
        }
    }

    /** One key while the §12.4 confirm box is open: y/Y/Enter quits, anything else cancels. */
    private void handleQuitConfirm(final KeyStroke k) {
        if (isChar(k, 'y') || isChar(k, 'Y') || k.getKeyType() == KeyType.Enter) {
            running = false;
            return;
        }
        quitConfirm = false;
        status = "quit cancelled";
    }

    /** Handles keys that work regardless of focus (§7, §11); returns whether the key was consumed. */
    private boolean handleGlobal(final KeyStroke k) {
        KeyType t = k.getKeyType();
        // Help.
        if (t == KeyType.F1 || isChar(k, '?')) {
            helpOverlay = true;
            return true;
        }
        // Stepping via function keys (§17.8: ⇧F7/⇧F8 reverse, with F15/F16 as the aliases
        // Apple Terminal's legacy "+8" encodings decode to; the F9/F10 fallbacks are gone).
        switch (t) {
            case F7 -> {
                doStepFromSelected(() -> k.isShiftDown() ? engine.reverseStep() : engine.stepInto());
                return true;
            }
            case F8 -> {
                doStepFromSelected(() -> k.isShiftDown() ? engine.reverseStepOver() : engine.stepOver());
                return true;
            }
            case F15 -> {
                doStepFromSelected(engine::reverseStep);
                return true;
            }
            case F16 -> {
                doStepFromSelected(engine::reverseStepOver);
                return true;
            }
            case Tab -> {
                cycleFocus(1);
                return true;
            }
            case ReverseTab -> {
                cycleFocus(-1);
                return true;
            }
            default -> {
                // fall through to character handling
            }
        }
        // §20.10 run-to-line: Ctrl-R forward, Ctrl-Q backward (both thread-preserving). The old
        // Alt-R / Ctrl-Shift-R reverse binding is removed. On a non-move doStep shows the engine's
        // message ("no earlier/later pass through this line" / "no line records in this thread").
        // (Ctrl-Q is XOFF/flow-control on some terminals; lanterna's raw mode disables IXON so it
        // arrives as a Ctrl-'q' character here.)
        if (isChar(k, 'r') && k.isCtrlDown()) {
            doStepFromSelected(() -> engine.runToCurrentLine(true));
            return true;
        }
        if (isChar(k, 'q') && k.isCtrlDown()) {
            doStepFromSelected(() -> engine.runToCurrentLine(false));
            return true;
        }
        // §20.13 per-window scroll keys (global; act on the focused window): Ctrl-P/Ctrl-N scroll the
        // viewport up/down one line (selection unmoved where the window has its own selection; Source
        // moves srcTop), Ctrl-K/Ctrl-L horizontal-scroll one HSTEP (synonyms for ←/→ and </>).
        if (isChar(k, 'p') && k.isCtrlDown()) {
            scrollFocused(-1);
            return true;
        }
        if (isChar(k, 'n') && k.isCtrlDown()) {
            scrollFocused(1);
            return true;
        }
        if (isChar(k, 'k') && k.isCtrlDown()) {
            hscrollFocused(-HSTEP);
            return true;
        }
        if (isChar(k, 'l') && k.isCtrlDown()) {
            hscrollFocused(HSTEP);
            return true;
        }
        // §19.2: in a GREEN context (the innermost frame is line-less) j/J jump between LINE-trace
        // records and REPLACE the e/E exit-frame bindings — checked here in handleGlobal (BEFORE
        // handleNav). Since §20.12 moved the Records seq dialog from `j` to `c`, `j`/`J` are now the
        // green jump only (no Records-focus collision); outside a green context e/E work as before
        // and j/J are NOT globally bound. The two (j/J vs e/E) are mutually exclusive.
        if (greenContext()) {
            // Check J first so a shifted 'j' is not eaten by 'j' (mirrors the e/E order).
            if (isChar(k, 'J') || (isChar(k, 'j') && k.isShiftDown())) {
                doStep(engine.toLineTrace(false));
                return true;
            }
            if (isChar(k, 'j')) {
                doStep(engine.toLineTrace(true));
                return true;
            }
        } else {
            // Exit current frame (§11.6). Check E first so a shifted 'e' is not eaten by 'e'.
            if (isChar(k, 'E') || (isChar(k, 'e') && k.isShiftDown())) {
                doStepFromSelected(engine::exitFrameBackward);
                return true;
            }
            if (isChar(k, 'e')) {
                doStepFromSelected(engine::exitFrameForward);
                return true;
            }
        }
        // Recording-path popup (§17.6).
        if (k.isCtrlDown() && isChar(k, 'd')) {
            recordingPopup = true;
            return true;
        }
        // Return-to-execution / leave browse mode (§11.2).
        if (isChar(k, 'o')) {
            origin();
            return true;
        }
        // Per-window horizontal scroll (§11.4).
        if (isChar(k, '<')) {
            hscrollFocused(-HSTEP);
            return true;
        }
        if (isChar(k, '>')) {
            hscrollFocused(HSTEP);
            return true;
        }
        // Dialogs.
        if (isChar(k, '/')) {
            openDialog(DialogKind.METHOD);
            return true;
        }
        if (isChar(k, '.')) {
            openDialog(DialogKind.OBJECT);
            return true;
        }
        if (isChar(k, 'F') || (isChar(k, 'f') && k.isShiftDown())) {
            openFileDialog(); // §17.7: the persistent tree browser
            return true;
        }
        // §20.9: `V` (or shift-v — mirroring the E/J shifted-letter detection) opens the global
        // variable-change window from any focus. Plain lowercase `v` (no shift) is NOT matched here,
        // so it still reaches navSource's Source-focused variable-history binding.
        if (isChar(k, 'V') || (isChar(k, 'v') && k.isShiftDown())) {
            openVarChanges();
            return true;
        }
        // Focus letters (unmodified only, so Ctrl/Alt+letter reach navigation). §11.3 swaps c/s.
        if (t == KeyType.Character && !k.isCtrlDown() && !k.isAltDown()) {
            Character ch = k.getCharacter();
            if (ch != null) {
                switch (ch) {
                    case 'c' -> {
                        // §20.12: from the Records window, `c` opens the seq-jump dialog (handled in
                        // navRecords) instead of switching focus — break out so handleNav gets it.
                        // (Reach Source from Records via Tab.) Every other focus: `c` focuses Source.
                        if (focus == Win.RECORDS) {
                            break;
                        }
                        focus = Win.SOURCE;
                        return true;
                    }
                    case 't' -> {
                        focus = Win.THREADS;
                        return true;
                    }
                    case 's' -> {
                        focus = Win.STACK;
                        return true;
                    }
                    case 'l' -> {
                        focus = Win.LOCALS;
                        return true;
                    }
                    case 'r' -> {
                        focus = Win.RECORDS;
                        return true;
                    }
                    default -> {
                        // not a focus letter; navigation may still want it (g/G)
                    }
                }
            }
        }
        return false;
    }

    private void handleNav(final KeyStroke k) {
        switch (focus) {
            case SOURCE -> navSource(k);
            case THREADS -> navThreads(k);
            case STACK -> navStack(k);
            case LOCALS -> navTree(k, localsTree, localsViewH);
            case RECORDS -> navRecords(k);
        }
    }

    /** Source-focused keys (§13.4): ↑/↓ etc. move the source-line cursor, not the plain scroll. */
    private void navSource(final KeyStroke k) {
        KeyType t = k.getKeyType();
        Character ch = k.getCharacter();
        int page = Math.max(1, srcViewH - 1);
        int lastLine = Math.max(1, srcFileLines);
        if (t == KeyType.ArrowUp) {
            setSrcCursor(srcCursor - 1);
        } else if (t == KeyType.ArrowDown) {
            setSrcCursor(srcCursor + 1);
        } else if (t == KeyType.ArrowLeft) {
            srcHcol = Math.max(0, srcHcol - HSTEP);
        } else if (t == KeyType.ArrowRight) {
            if (srcResolved) {
                srcHcol += HSTEP;
            }
        } else if (t == KeyType.PageUp || (k.isCtrlDown() && ch != null && ch == 'b')) {
            setSrcCursor(srcCursor - page);
        } else if (t == KeyType.PageDown || (k.isCtrlDown() && ch != null && ch == 'f')) {
            setSrcCursor(srcCursor + page);
        } else if (t == KeyType.Home) {
            setSrcCursor(1);
            srcHcol = 0;
        } else if (t == KeyType.End) {
            setSrcCursor(lastLine);
        } else if (ch != null && ch == 'g') {
            setSrcCursor(1);
        } else if (ch != null && ch == 'G') {
            setSrcCursor(lastLine);
        } else if (isChar(k, 'v') && !k.isCtrlDown() && !k.isAltDown()) {
            varHistory();
        } else if (isChar(k, 'f') && k.isAltDown()) {
            enterSourceSearch(); // §16.4: mirror the Alt-R dispatch
        }
    }

    /**
     * Moves the §13.4 source cursor (clamped to the shown file) and scrolls the view only when the
     * cursor would leave the visible rows — the §13.1 edge-only rule, no margin.
     */
    private void setSrcCursor(final int line) {
        srcCursor = clamp(line, 1, Math.max(1, srcFileLines));
        srcTop = ScrollMath.adjustScroll(srcTop - 1, srcCursor - 1, Math.max(1, srcViewH), Math.max(1, srcFileLines)) + 1;
    }

    private void navThreads(final KeyStroke k) {
        KeyType t = k.getKeyType();
        Character ch = k.getCharacter();
        int page = Math.max(1, threadsViewH - 1);
        if (t == KeyType.ArrowLeft) {
            threadsHcol = Math.max(0, threadsHcol - HSTEP);
        } else if (t == KeyType.ArrowRight) {
            threadsHcol += HSTEP;
        } else if (t == KeyType.ArrowUp) {
            threadSel = Math.max(0, threadSel - 1);
        } else if (t == KeyType.ArrowDown) {
            threadSel = Math.min(tids.size() - 1, threadSel + 1);
        } else if (t == KeyType.PageUp || (k.isCtrlDown() && ch != null && ch == 'b')) {
            threadSel = Math.max(0, threadSel - page);
        } else if (t == KeyType.PageDown || (k.isCtrlDown() && ch != null && ch == 'f')) {
            threadSel = Math.min(tids.size() - 1, threadSel + page);
        } else if (t == KeyType.Home || (ch != null && ch == 'g')) {
            threadSel = 0;
        } else if (t == KeyType.End || (ch != null && ch == 'G')) {
            threadSel = tids.size() - 1;
        } else if (t == KeyType.Enter) {
            if (threadSel >= 0 && threadSel < tids.size()) {
                doStep(engine.selectThread(tids.get(threadSel)));
            }
        }
    }

    private void navStack(final KeyStroke k) {
        KeyType t = k.getKeyType();
        Character ch = k.getCharacter();
        if (t == KeyType.ArrowLeft) {
            stackHcol = Math.max(0, stackHcol - HSTEP);
            return;
        }
        if (t == KeyType.ArrowRight) {
            stackHcol += HSTEP;
            return;
        }
        int size = engine.stack().size();
        if (size == 0) {
            return;
        }
        int newSel = stackSel;
        if (t == KeyType.ArrowUp) {
            newSel = Math.max(0, stackSel - 1);
        } else if (t == KeyType.ArrowDown) {
            newSel = Math.min(size - 1, stackSel + 1);
        } else if (t == KeyType.Home || (ch != null && ch == 'g')) {
            newSel = 0;
        } else if (t == KeyType.End || (ch != null && ch == 'G')) {
            newSel = size - 1;
        } else if (t == KeyType.Enter) {
            newSel = stackSel;
        } else {
            return;
        }
        selectStackRow(newSel);
    }

    private void selectStackRow(final int row) {
        List<Frame> frames = engine.stack();
        int frameIndex = frames.size() - 1 - row;
        int prevFrame = engine.selectedFrame();
        if (engine.selectFrame(frameIndex)) {
            stackSel = row;
            // §20.8: selecting a stack frame changes the shown frame, so browse mode yields to the
            // execution source (re-centred on the frame's line via recenterSource + srcCursorPending).
            if (browseMode) {
                browseMode = false;
                recenterSource = true;
                srcCursorPending = true;
                srcHcol = 0;
            }
            if (frameIndex != prevFrame) {
                // §16.2: only an ACTUAL frame switch re-follows, resets scroll and collapses
                // Locals — a no-op re-selection must leave the reader's view untouched.
                followPending = true;
                srcHcol = 0;
                localsHcol = 0;
                srcCursorPending = true; // §13.4: cursor to the selected frame's line
                localsTree.clearExpansion();
            }
        }
    }

    private void navTree(final KeyStroke k, final TreeView tree, final int viewH) {
        KeyType t = k.getKeyType();
        Character ch = k.getCharacter();
        int page = Math.max(1, viewH - 1);
        if (t == KeyType.Enter && jumpTreeLambda(tree)) {
            // §20.3: Enter on a lambda local jumps to its first mention (definition) instead of
            // expanding it; the captured arg$N fields stay reachable via ArrowRight / elsewhere.
            return;
        }
        if (t == KeyType.ArrowUp) {
            tree.moveUp(1);
        } else if (t == KeyType.ArrowDown) {
            tree.moveDown(1);
        } else if (t == KeyType.ArrowRight || t == KeyType.Enter) {
            tree.expandOrDescend();
        } else if (t == KeyType.ArrowLeft) {
            tree.collapseOrParent();
        } else if (t == KeyType.PageUp || (k.isCtrlDown() && ch != null && ch == 'b')) {
            tree.moveUp(page);
        } else if (t == KeyType.PageDown || (k.isCtrlDown() && ch != null && ch == 'f')) {
            tree.moveDown(page);
        } else if (t == KeyType.Home || (ch != null && ch == 'g')) {
            tree.toFirst();
        } else if (t == KeyType.End || (ch != null && ch == 'G')) {
            tree.toLast();
        }
    }

    private void navRecords(final KeyStroke k) {
        List<HistoryTrail.HistoryEntry> entries = history.entries();
        KeyType t = k.getKeyType();
        Character ch = k.getCharacter();
        int page = Math.max(1, recordsViewH - 1);
        int last = Math.max(0, entries.size() - 1);
        // §20.12: the seq-jump dialog moved from `j` to `c` (freeing `j` for the §19.2 green jump).
        // handleGlobal yields `c` to Records rather than switching focus to Source (reach Source via Tab).
        if (isChar(k, 'c') && !k.isCtrlDown() && !k.isAltDown()) {
            recordJump = new RecordJump(); // §16.3
            return;
        }
        if (t == KeyType.ArrowLeft) {
            recordsHcol = Math.max(0, recordsHcol - HSTEP);
        } else if (t == KeyType.ArrowRight) {
            recordsHcol += HSTEP;
        } else if (t == KeyType.ArrowUp) {
            recordsSel = Math.max(0, recordsSel - 1);
        } else if (t == KeyType.ArrowDown) {
            recordsSel = Math.min(last, recordsSel + 1);
        } else if (t == KeyType.PageUp || (k.isCtrlDown() && ch != null && ch == 'b')) {
            recordsSel = Math.max(0, recordsSel - page);
        } else if (t == KeyType.PageDown || (k.isCtrlDown() && ch != null && ch == 'f')) {
            recordsSel = Math.min(last, recordsSel + page);
        } else if (t == KeyType.Home || (ch != null && ch == 'g')) {
            recordsSel = 0;
        } else if (t == KeyType.End || (ch != null && ch == 'G')) {
            recordsSel = last;
        } else if (t == KeyType.Enter && recordsSel >= 0 && recordsSel < entries.size()) {
            HistoryTrail.HistoryEntry e = entries.get(recordsSel);
            int keep = recordsSel;
            StepResult r = engine.seek(e.tid(), e.index());
            status = r.message();
            if (r.moved()) {
                afterMove();          // selecting from the list does NOT append (§11.5)
                recordsSel = clamp(keep, 0, Math.max(0, history.size() - 1));
            }
        }
    }

    // ---- dialogs (§9, §11.7, §11.8) ----

    private void openDialog(final DialogKind kind) {
        if (kind == DialogKind.METHOD) {
            // §15.5: the `/` dialog is persistent — reopening restores mode, query, results,
            // selection, scroll, expansion and focus exactly (expanded trees still re-resolve
            // as-of the current seq at render time). `.`/`F` stay transient below.
            if (searchDialog == null) {
                searchDialog = new SearchDialog(kind);
                searchDialog.instanceTree = new TreeView(engine::children);
                dialog = searchDialog;
                recomputeDialog();
            } else {
                dialog = searchDialog;
            }
            return;
        }
        dialog = new SearchDialog(kind);
        recomputeDialog();
    }

    private void handleDialog(final KeyStroke k) {
        if (k.getKeyType() == KeyType.Escape) {
            // §15.2: Esc closes from either focus; §15.5: searchDialog keeps the `/` state.
            dialog = null;
            return;
        }
        if (dialog.kind == DialogKind.METHOD) {
            handleSearchDialog(k);
        } else {
            handleTransientDialog(k);
        }
    }

    /** Keys of the transient {@code .} dialog (§9) — unchanged by §15.2. */
    private void handleTransientDialog(final KeyStroke k) {
        KeyType t = k.getKeyType();
        if (t == KeyType.Enter) {
            jumpDialog();
        } else if (t == KeyType.ArrowUp) {
            dialog.sel = Math.max(0, dialog.sel - 1);
        } else if (t == KeyType.ArrowDown) {
            dialog.sel = Math.min(Math.max(0, dialog.rows.size() - 1), dialog.sel + 1);
        } else if (t == KeyType.Backspace) {
            if (dialog.query.length() > 0) {
                dialog.query.deleteCharAt(dialog.query.length() - 1);
                dialog.sel = 0;
                dialog.scroll = 0;
                recomputeDialog();
            }
        } else if (t == KeyType.Character) {
            Character ch = k.getCharacter();
            if (ch != null && ch >= ' ') {
                dialog.query.append(ch.charValue());
                dialog.sel = 0;
                dialog.scroll = 0;
                recomputeDialog();
            }
        }
    }

    /**
     * Keys of the {@code /} dialog under the §15.2 focus model (which replaced the §14.2
     * list-active heuristic): Tab toggles calls ↔ instances from either focus (§13.2 — query
     * kept, selection/scroll/expansion reset); everything else goes to the QUERY line or the
     * result LIST depending on {@code dialog.focus}.
     */
    private void handleSearchDialog(final KeyStroke k) {
        if (k.getKeyType() == KeyType.Tab) {
            dialog.instancesMode = !dialog.instancesMode;
            dialog.sel = 0;
            dialog.scroll = 0;
            recomputeDialog();
            return;
        }
        if (dialog.focus == DialogFocus.QUERY) {
            handleSearchQueryKey(k);
        } else {
            handleSearchListKey(k);
        }
    }

    /**
     * QUERY-focus keys (§15.2): printables edit, Backspace deletes one character, and
     * Ctrl-Backspace clears the query with its results and count line in one stroke — with
     * Ctrl-U and Ctrl-W accepted as portable synonyms, because on classic xterm-style
     * encodings Ctrl-Backspace reaches lanterna as a plain {@code KeyType.Backspace}
     * (indistinguishable from an unmodified one; only kitty-/win32-style terminals report
     * the Ctrl modifier on Backspace). ↑/↓ hand the focus to the result list, performing
     * the first move — only when there are results to move over.
     */
    private void handleSearchQueryKey(final KeyStroke k) {
        KeyType t = k.getKeyType();
        boolean ctrl = k.isCtrlDown();
        if ((t == KeyType.Backspace && ctrl) || (ctrl && (isChar(k, 'u') || isChar(k, 'w')))) {
            dialog.query.setLength(0);
            dialog.sel = 0;
            dialog.scroll = 0;
            recomputeDialog(); // empties rows and note, so the §15.3 count line disappears too
            return;
        }
        if (t == KeyType.Backspace) {
            if (dialog.query.length() > 0) {
                dialog.query.deleteCharAt(dialog.query.length() - 1);
                dialog.sel = 0;
                dialog.scroll = 0;
                recomputeDialog();
            }
            return;
        }
        if (t == KeyType.ArrowUp || t == KeyType.ArrowDown) {
            if (!dialog.rows.isEmpty()) {
                // §15.2: the first arrow only ENTERS the list — the already-highlighted
                // row stays selected; moving on entry would skip the first result.
                dialog.focus = DialogFocus.LIST;
            }
            return;
        }
        if (t == KeyType.Character && !ctrl && !k.isAltDown()) {
            Character ch = k.getCharacter();
            if (ch != null && ch >= ' ') {
                dialog.query.append(ch.charValue());
                dialog.sel = 0;
                dialog.scroll = 0;
                recomputeDialog();
            }
        }
    }

    /**
     * LIST-focus keys (§15.2): ↑/↓ move, Ctrl-F/Ctrl-B and PgDn/PgUp page, {@code g}/{@code G}
     * jump to the first/last row; in instances mode →/↵ expand, ← collapses, {@code f} jumps to
     * the selected row's owning instance's first mention (dialog stays open) and {@code <}/
     * {@code >} horizontal-scroll the tree; in calls mode ↵ jumps and closes. {@code /} returns
     * the focus to the query line; all other printables are deliberately ignored.
     */
    private void handleSearchListKey(final KeyStroke k) {
        KeyType t = k.getKeyType();
        boolean ctrl = k.isCtrlDown();
        int page = Math.max(1, dialog.listViewH - 1);
        if (!ctrl && isChar(k, '/')) {
            dialog.focus = DialogFocus.QUERY;
            return;
        }
        if (t == KeyType.ArrowUp) {
            moveSearchSelection(-1);
            return;
        }
        if (t == KeyType.ArrowDown) {
            moveSearchSelection(1);
            return;
        }
        if (t == KeyType.PageUp || (ctrl && isChar(k, 'b'))) {
            moveSearchSelection(-page);
            return;
        }
        if (t == KeyType.PageDown || (ctrl && isChar(k, 'f'))) {
            moveSearchSelection(page);
            return;
        }
        if (!ctrl && isChar(k, 'g')) {
            if (dialog.instancesMode) {
                dialog.instanceTree.toFirst();
            } else {
                dialog.sel = 0;
            }
            return;
        }
        if (!ctrl && isChar(k, 'G')) {
            if (dialog.instancesMode) {
                dialog.instanceTree.toLast();
            } else {
                dialog.sel = Math.max(0, dialog.rows.size() - 1);
            }
            return;
        }
        if (dialog.instancesMode) {
            TreeView tree = dialog.instanceTree;
            if (t == KeyType.ArrowRight || t == KeyType.Enter) {
                // §14.2: Enter is the expand toggle only — it never jumps and never closes.
                tree.expandOrDescend();
            } else if (t == KeyType.ArrowLeft) {
                tree.collapseOrParent();
            } else if (!ctrl && isChar(k, 'f')) {
                jumpToFirstMention(); // §15.2: the §14.2 first-mention jump moved from g to f
            } else if (isChar(k, '<')) {
                dialog.hcol = Math.max(0, dialog.hcol - HSTEP);
            } else if (isChar(k, '>')) {
                dialog.hcol += HSTEP;
            }
        } else if (t == KeyType.Enter) {
            jumpDialog(); // calls mode: jump and close (unchanged)
        }
    }

    /** Moves the LIST selection by {@code delta} rows in whichever mode's list is showing. */
    private void moveSearchSelection(final int delta) {
        if (dialog.instancesMode) {
            if (delta < 0) {
                dialog.instanceTree.moveUp(-delta);
            } else {
                dialog.instanceTree.moveDown(delta);
            }
        } else {
            dialog.sel = clamp(dialog.sel + delta, 0, Math.max(0, dialog.rows.size() - 1));
        }
    }

    /**
     * The {@code f} key of the instances-mode {@code /} dialog (§14.2 semantics, rebound from
     * {@code g} by §15.2): move the cursor to the OWNING instance of the selected row (the root
     * of its subtree) — seek to its {@code OBJECT_FIRST_SEEN}, advance to the nearest following
     * execution record (the §11.7/§17.2 jump pattern), one history entry — and keep the dialog
     * open; the expanded trees re-resolve against the new seq on the next draw. If the engine
     * has no first-mention record (defensive), report and stay put.
     */
    private void jumpToFirstMention() {
        InstanceBrowse.Row row = selectedInstanceRow();
        if (row == null) {
            return;
        }
        jumpToFirstMentionOf(row.objectId(), row.display());
    }

    /**
     * The shared first-mention jump (§14.2, extended by §20.3): seek to {@code objectId}'s
     * {@code OBJECT_FIRST_SEEN}, advance to the nearest following execution record (the §11.7/§17.2
     * forward-jump pattern) and append exactly ONE history entry. Reused by the {@code /}
     * instances-dialog {@code f}, the §20.3 lambda {@code Enter} in Locals and the §20.3 lambda
     * {@code Enter} in the {@code .} object dialog. If the engine has no first-mention record for the
     * id (defensive), report and stay put.
     *
     * @param objectId the object id to jump to the first mention of
     * @param display a short label for the status message (e.g. {@code Foo#20})
     */
    private void jumpToFirstMentionOf(final long objectId, final String display) {
        Instance c = engine.firstMentionOf(objectId);
        if (c == null) {
            status = "no first mention recorded for " + display;
            return;
        }
        landOnNearestExecAfter(c.tid(), c.index());
        afterMove();
        appendHistory();
        status = "jumped to first mention of " + display + " (seq " + c.seq() + ")";
    }

    /**
     * §20.3 helper: whether the selected {@code tree} row carries a lambda reference and, if so,
     * performs the first-mention jump and returns {@code true} (so the caller skips its normal
     * expand/seek). Guards on the node actually holding a non-null {@link Value.Ref} whose id
     * {@link Engine#isLambda} confirms — every other node returns {@code false} and behaves as today.
     */
    private boolean jumpTreeLambda(final TreeView tree) {
        List<TreeView.Row> rows = tree.rows();
        if (rows.isEmpty()) {
            return false;
        }
        Value v = rows.get(tree.selection()).node().value();
        if (v instanceof Value.Ref ref && ref.objectId() != 0 && engine.isLambda(ref.objectId())) {
            jumpToFirstMentionOf(ref.objectId(), ref.display());
            return true;
        }
        return false;
    }

    private void recomputeDialog() {
        switch (dialog.kind) {
            case METHOD -> {
                if (dialog.instancesMode) {
                    recomputeInstanceResults();
                } else {
                    recomputeMethodResults();
                }
                // §15.2: an empty result list cannot hold LIST focus — editing resumes.
                if (dialog.rows.isEmpty()) {
                    dialog.focus = DialogFocus.QUERY;
                }
            }
            case OBJECT -> recomputeObjectResults();
        }
    }

    private void recomputeMethodResults() {
        String q = dialog.query.toString();
        if (q.isEmpty()) {
            dialog.rows = List.of();
            dialog.targets = List.of();
            dialog.note = "";
            return;
        }
        try {
            MethodBrowse.Result res = MethodBrowse.filter(
                    engine.methodSites(), sid -> engine.callsOf(sid, MethodBrowse.CAP), q, MethodBrowse.CAP);
            List<String> rows = new ArrayList<>(res.rows().size());
            List<long[]> targets = new ArrayList<>(res.rows().size());
            for (MethodBrowse.Row r : res.rows()) {
                rows.add("seq " + r.seq() + DOT + "t" + r.tid() + DOT + r.display());
                targets.add(new long[] {r.tid(), r.index(), r.seq()});
            }
            dialog.rows = rows;
            dialog.targets = targets;
            dialog.note = DialogFormat.countLine(res.totalMatches(), rows.size()); // §15.3
        } catch (PatternSyntaxException e) {
            dialog.rows = List.of();
            dialog.targets = List.of();
            dialog.note = "invalid regex";
            status = "invalid regex: " + describe(e);
        }
    }

    /**
     * The §13.2/§14.1/§15.1 instances mode of the {@code /} dialog: regex over runtime class
     * names (simple names for an unqualified query — {@link InstanceBrowse}), optionally narrowed
     * to one exact object id by a {@code #<digits>} query suffix. Every recompute (open, query
     * edit, mode toggle) rebuilds the §14.2 result tree from scratch, dropping
     * expansion/selection/scroll state.
     */
    private void recomputeInstanceResults() {
        dialog.targets = List.of();
        dialog.hcol = 0;
        dialog.instanceTree.clearExpansion();
        String q = dialog.query.toString();
        if (q.isEmpty()) {
            dialog.rows = List.of();
            dialog.instanceRows = List.of();
            dialog.instanceTree.setRoots(List.of());
            dialog.note = "";
            return;
        }
        try {
            // §14.1: an id query must see EVERY instance of a matching class, not the first CAP —
            // the one demanded id may be arbitrarily late in the class's instance list.
            int perClassCap =
                    InstanceBrowse.parseQuery(q).objectId() != null ? Integer.MAX_VALUE : InstanceBrowse.CAP;
            InstanceBrowse.Result res = InstanceBrowse.filter(
                    engine.classSites(), cls -> engine.instancesOf(cls, perClassCap), q, InstanceBrowse.CAP);
            List<String> rows = new ArrayList<>(res.rows().size());
            List<ObjectNode> roots = new ArrayList<>(res.rows().size());
            for (InstanceBrowse.Row r : res.rows()) {
                String label = "seq " + r.seq() + DOT + "t" + r.tid() + DOT + r.display();
                rows.add(label);
                // §14.2: the row itself is an expandable object-tree root (label unchanged).
                roots.add(new ObjectNode(
                        label, new Value.Ref(r.objectId(), r.display()), r.objectId() != 0));
            }
            dialog.rows = rows;
            dialog.instanceRows = res.rows();
            dialog.instanceTree.setRoots(roots);
            dialog.note = DialogFormat.countLine(res.totalMatches(), rows.size()); // §15.3
        } catch (PatternSyntaxException e) {
            dialog.rows = List.of();
            dialog.instanceRows = List.of();
            dialog.instanceTree.setRoots(List.of());
            dialog.note = "invalid regex";
            status = "invalid regex: " + describe(e);
        }
    }

    private void recomputeObjectResults() {
        String q = dialog.query.toString().trim();
        List<String> rows = new ArrayList<>();
        List<long[]> targets = new ArrayList<>();
        List<Long> objectIds = new ArrayList<>(); // §20.3: the object id each row references (0 = none)
        dialog.note = "";
        if (q.isEmpty()) {
            dialog.rows = rows;
            dialog.targets = targets;
            dialog.objectIds = objectIds;
            return;
        }
        String idText = q.startsWith("#") ? q.substring(1) : q;
        Long id = null;
        if (idText.matches("\\d+")) {
            try {
                id = Long.parseLong(idText);
            } catch (NumberFormatException e) {
                id = null;
            }
        }
        String needle = q.toLowerCase(Locale.ROOT);
        outer:
        for (int tid : tids) {
            long n = count(tid);
            for (long i = 0; i < n; i++) {
                Rec r = rec(tid, i);
                boolean match;
                if (id != null) {
                    match = referencesId(r, id);
                } else {
                    match = r.type() == Format.OBJECT_FIRST_SEEN && classMatches(r.target(), needle);
                }
                if (match) {
                    rows.add("seq " + r.seq() + DOT + "t" + tid + DOT + formatSiteText(r));
                    targets.add(new long[] {tid, i, r.seq()});
                    // §20.3: for an id query every row references that id; for a class query each
                    // OBJECT_FIRST_SEEN row's object is its target() — either lets Enter detect a lambda.
                    objectIds.add(id != null ? id : r.target());
                    if (rows.size() >= RESULT_CAP) {
                        dialog.note = "showing first " + RESULT_CAP;
                        break outer;
                    }
                }
            }
        }
        dialog.rows = rows;
        dialog.targets = targets;
        dialog.objectIds = objectIds;
    }

    private boolean referencesId(final Rec r, final long id) {
        if (r.target() == id) {
            return true;
        }
        if (r.beforeKind() == Format.KIND_OBJECT_ID && r.before() == id) {
            return true;
        }
        return r.afterKind() == Format.KIND_OBJECT_ID && r.after() == id;
    }

    private boolean classMatches(final long objectId, final String needle) {
        String cls = recording.objectClassName(objectId);
        if (cls == null) {
            return false;
        }
        return Format.simpleName(cls).toLowerCase(Locale.ROOT).contains(needle)
                || cls.toLowerCase(Locale.ROOT).contains(needle);
    }

    private void jumpDialog() {
        if (dialog == null) {
            return;
        }
        if (dialog.rows.isEmpty()) {
            status = "no matches for \"" + dialog.query + "\"";
            dialog = null;
            return;
        }
        int sel = clamp(dialog.sel, 0, dialog.rows.size() - 1);
        long[] target = dialog.targets.get(sel);
        DialogKind kind = dialog.kind;
        // §20.3: the object id the selected `.` row references (0 = none / METHOD dialog), captured
        // before the dialog is cleared so Enter on a lambda can jump to its first mention.
        long objId = kind == DialogKind.OBJECT && sel < dialog.objectIds.size() ? dialog.objectIds.get(sel) : 0L;
        dialog = null;
        int tid = (int) target[0];
        long index = target[1];
        long seq = target[2];
        if (kind == DialogKind.METHOD) {
            jumpToCall(tid, index);
        } else if (objId != 0 && engine.isLambda(objId)) {
            // §20.3: Enter on a lambda object jumps to its first mention (definition) instead of the
            // normal record seek — jumpToFirstMentionOf sets its own status, so return here.
            jumpToFirstMentionOf(objId, lambdaLabel(objId));
            return;
        } else {
            seekToSeq(tid, seq);
            afterMove();
            appendHistory();
        }
        status = "jumped to seq " + engine.currentSeq() + " (t" + engine.currentTid() + ")";
    }

    /** A short {@code SimpleClass#id} label for a §20.3 lambda first-mention status message. */
    private String lambdaLabel(final long objectId) {
        return simpleClass(recording.objectClassName(objectId)) + "#" + objectId;
    }

    /**
     * Jumps to a method call (§11.7/§17.2): seek to its {@code METHOD_ENTER} record, then advance
     * to the nearest following execution record; appends exactly one history entry.
     */
    private void jumpToCall(final int tid, final long index) {
        landOnNearestExecAfter(tid, index);
        afterMove();
        appendHistory();
    }

    /**
     * The §11.7/§17.2 forward-jump landing: seek to {@code (tid, index)}, then advance to the
     * nearest FOLLOWING execution record (the §17.1 set — LINE/METHOD_ENTER/METHOD_EXIT). With
     * exec-based stepping the {@code stepInto} lands on the exec set by construction; when
     * nothing follows the target in its thread, fall back to the nearest exec record before it
     * so the cursor never rests on a metadata record (§17.2: such a landing is a bug).
     */
    private void landOnNearestExecAfter(final int tid, final long index) {
        engine.seek(tid, index);
        if (!engine.stepInto().moved()
                && !ExecRecords.isExec(rec(engine.currentTid(), engine.currentIndex()).type())) {
            engine.reverseStep();
        }
    }

    // ---- variable history (§13.5) ----

    /**
     * The {@code v} key (Source focused): parse the cursor line of the shown file for field
     * declarations and open the history of the first candidate with recorded writes.
     */
    private void varHistory() {
        List<String> lines;
        String fileName;
        long thisId;
        if (browseMode) {
            lines = browseLines;
            fileName = browseName;
            thisId = 0; // §16.1: no enclosing instance in browse mode
        } else {
            List<Frame> frames = engine.stack();
            int sf = engine.selectedFrame();
            Frame frame = sf >= 0 && sf < frames.size() ? frames.get(sf) : null;
            fileName = frame != null ? frame.fileName() : null;
            lines = frame != null
                    ? sourcePath.lines(frame.classBinary(), fileName).orElse(null)
                    : null;
            thisId = frame != null ? engine.frameThisId(sf) : 0; // §16.1: the selected frame's `this`
        }
        if (lines == null || lines.isEmpty()) {
            status = "no class variable definition on this line";
            return;
        }
        List<String> names = FieldDecl.parse(lines.get(clamp(srcCursor, 1, lines.size()) - 1));
        if (names.isEmpty()) {
            status = "no class variable definition on this line";
            return;
        }
        // §13.5: first candidate with hits (all-instances scope) wins; §16.1 remembers thisId for Tab.
        for (String name : names) {
            if (!engine.fieldWrites(fileName, name, 0L, 1).isEmpty()) {
                VarHistoryWindow w = new VarHistoryWindow(fileName, name, thisId);
                rebuildVarHistory(w);
                varHist = w;
                return;
            }
        }
        status = "no recorded writes for '" + names.get(0) + "'";
    }

    /**
     * §16.1 shared row builder used at open and by the Tab toggle: {@code ownerId = thisOnly &&
     * thisId != 0 ? thisId : 0}; asks {@link Engine#fieldWrites} for {@code CAP+1} hits so a full
     * window is distinguishable from a capped one, uses {@link Engine#countFieldWrites} for the
     * cap note, and resets selection/scroll/hcol.
     */
    private void rebuildVarHistory(final VarHistoryWindow w) {
        long ownerId = w.thisOnly && w.thisId != 0 ? w.thisId : 0L;
        List<FieldWriteHit> hits = engine.fieldWrites(w.fileName, w.fieldName, ownerId, VAR_HISTORY_CAP + 1);
        boolean capped = hits.size() > VAR_HISTORY_CAP;
        List<FieldWriteHit> shown = capped ? hits.subList(0, VAR_HISTORY_CAP) : hits;
        List<String> rows = new ArrayList<>(shown.size());
        for (FieldWriteHit hit : shown) {
            rows.add(varHistoryRow(hit));
        }
        w.hits = List.copyOf(shown);
        w.rows = rows;
        w.note = capped
                ? "showing first " + VAR_HISTORY_CAP + " of "
                        + engine.countFieldWrites(w.fileName, w.fieldName, ownerId)
                : "";
        w.sel = 0;
        w.scroll = 0;
        w.hcol = 0;
    }

    /**
     * {@code seq N · t<tid> · Owner#oid.field: old → new  (File:line)}; statics omit {@code #oid}.
     * Delegates to the shared {@link VarHistory#row} so this window and the §20.9 variable-change
     * window render field writes identically.
     */
    private String varHistoryRow(final FieldWriteHit h) {
        return VarHistory.row(h);
    }

    /**
     * One key while the §16.1 variable-history overlay is open — it consumes all keys, like a dialog:
     * ↑/↓ select, ←/→ hscroll (step {@code HSTEP}; {@code <}/{@code >} no longer scroll here — §16.5),
     * Ctrl-F/Ctrl-B and PgDn/PgUp page, {@code g}/Home first row, {@code G}/End last, Tab toggles the
     * this-instance filter, Enter jumps, Esc closes.
     */
    private void handleVarHistory(final KeyStroke k) {
        KeyType t = k.getKeyType();
        if (t == KeyType.Escape) {
            varHist = null;
            return;
        }
        if (isCtrlC(k)) {
            running = false;
            return;
        }
        int last = Math.max(0, varHist.rows.size() - 1);
        int page = Math.max(1, varHist.listViewH - 1);
        if (t == KeyType.Tab) {
            toggleVarHistoryScope();
        } else if (t == KeyType.ArrowUp) {
            varHist.sel = Math.max(0, varHist.sel - 1);
        } else if (t == KeyType.ArrowDown) {
            varHist.sel = Math.min(last, varHist.sel + 1);
        } else if (t == KeyType.ArrowLeft) {
            varHist.hcol = Math.max(0, varHist.hcol - HSTEP);
        } else if (t == KeyType.ArrowRight) {
            varHist.hcol += HSTEP;
        } else if (t == KeyType.PageUp || (k.isCtrlDown() && isChar(k, 'b'))) {
            varHist.sel = Math.max(0, varHist.sel - page);
        } else if (t == KeyType.PageDown || (k.isCtrlDown() && isChar(k, 'f'))) {
            varHist.sel = Math.min(last, varHist.sel + page);
        } else if (t == KeyType.Home || isChar(k, 'g')) {
            varHist.sel = 0;
        } else if (t == KeyType.End || isChar(k, 'G')) {
            varHist.sel = last;
        } else if (t == KeyType.Enter) {
            jumpToWrite();
        }
    }

    /**
     * §16.1 Tab: toggle between all-instances and this-instance-only, re-query via the owner-filtered
     * engine calls ({@link #rebuildVarHistory}) and reset selection/scroll/hcol. A no-op (with a
     * scope note) when there is no enclosing instance ({@code thisId == 0}: static context or browse).
     */
    private void toggleVarHistoryScope() {
        if (varHist.thisId == 0) {
            status = "no enclosing this — showing all instances";
            return;
        }
        varHist.thisOnly = !varHist.thisOnly;
        rebuildVarHistory(varHist);
    }

    /**
     * Jump to a field write (§13.5/§17.2): position on the nearest execution record at/before
     * the {@code FIELD_WRITE} record (the write belongs to that position). One history entry.
     * A no-op when the current scope lists no writes (e.g. an empty §16.1 this-filter).
     */
    private void jumpToWrite() {
        if (varHist.hits.isEmpty()) {
            return;
        }
        FieldWriteHit hit = varHist.hits.get(clamp(varHist.sel, 0, varHist.hits.size() - 1));
        varHist = null;
        landOnNearestExecAtOrBefore(hit.tid(), hit.index());
        afterMove();
        appendHistory();
        status = "jumped to seq " + engine.currentSeq() + " (t" + engine.currentTid() + ")";
    }

    /**
     * The §13.5/§16.3/§17.2 landing rule: seek to {@code (tid, index)}, then position on the
     * nearest EXECUTION record (the §17.1 set — LINE/METHOD_ENTER/METHOD_EXIT) AT/BEFORE it (the
     * record belongs to that execution position); fall FORWARD only if no exec record precedes.
     */
    private void landOnNearestExecAtOrBefore(final int tid, final long index) {
        engine.seek(tid, index);
        // When the target record is itself an exec record, seek already put the cursor exactly
        // on it — stepping would move OFF it (reverseStep from it goes to the previous exec
        // record, the §16.3 off-by-one). Only a non-exec target needs walking back to the exec
        // record it belongs to (falling forward only when none precedes).
        if (ExecRecords.isExec(rec(tid, index).type())) {
            return;
        }
        if (!engine.reverseStep().moved()) {
            engine.stepInto();
        }
    }

    // ---- global variable-change window (§20.9) ----

    /** §20.9: opens the global variable-change window (all field writes; empty filter). */
    private void openVarChanges() {
        VarChangeWindow w = new VarChangeWindow();
        rebuildVarChanges(w);
        varChanges = w;
    }

    /**
     * §20.9: rebuilds the window's rows from {@link Engine#fieldWriteChanges} for the current filter
     * (capped at {@link #VAR_CHANGE_CAP}, ascending seq), formats them with the shared
     * {@link VarHistory#row}, sets the DIM count note ({@code showing first 1000 of N} when capped,
     * else {@code N results}) and resets the selection/scroll/hscroll — called on open and on every
     * filter keystroke.
     */
    private void rebuildVarChanges(final VarChangeWindow w) {
        String filter = w.filter.toString();
        List<FieldWriteHit> hits = engine.fieldWriteChanges(filter, VAR_CHANGE_CAP);
        long total = engine.countFieldWriteChanges(filter);
        List<String> rows = new ArrayList<>(hits.size());
        for (FieldWriteHit hit : hits) {
            rows.add(VarHistory.row(hit));
        }
        w.hits = hits;
        w.rows = rows;
        w.note = total > rows.size()
                ? "showing first " + VAR_CHANGE_CAP + " of " + total
                : rows.size() + " results";
        w.sel = 0;
        w.scroll = 0;
        w.hcol = 0;
    }

    /**
     * One key while the §20.9 variable-change window is open (Ctrl-C still quits): Esc closes, Enter
     * jumps to the selected write's record from either focus, and everything else goes to the
     * §15.2-style QUERY (filter line) or LIST (result list) handler depending on {@code focus}.
     */
    private void handleVarChanges(final KeyStroke k) {
        KeyType t = k.getKeyType();
        if (isCtrlC(k)) {
            running = false;
            return;
        }
        if (t == KeyType.Escape) {
            varChanges = null;
            return;
        }
        if (t == KeyType.Enter) {
            jumpToVarChange();
            return;
        }
        if (varChanges.focus == DialogFocus.QUERY) {
            handleVarChangesQuery(k);
        } else {
            handleVarChangesList(k);
        }
    }

    /**
     * §20.9 QUERY focus: printables edit the filter (recompute per keystroke), Backspace deletes,
     * Ctrl-U/Ctrl-W/Ctrl-Backspace clear it, ←/→ hscroll, and ↑/↓ (and the paging keys) hand focus
     * to the result LIST — mirroring the §15.2 {@code /} dialog so field names containing {@code g}
     * or {@code <} can still be typed instead of triggering the list's g/G/</> commands.
     */
    private void handleVarChangesQuery(final KeyStroke k) {
        KeyType t = k.getKeyType();
        boolean ctrl = k.isCtrlDown();
        VarChangeWindow w = varChanges;
        if ((t == KeyType.Backspace && ctrl) || (ctrl && (isChar(k, 'u') || isChar(k, 'w')))) {
            w.filter.setLength(0);
            rebuildVarChanges(w);
            return;
        }
        if (t == KeyType.Backspace) {
            if (w.filter.length() > 0) {
                w.filter.deleteCharAt(w.filter.length() - 1);
                rebuildVarChanges(w);
            }
            return;
        }
        if (t == KeyType.ArrowLeft) {
            w.hcol = Math.max(0, w.hcol - HSTEP);
            return;
        }
        if (t == KeyType.ArrowRight) {
            w.hcol += HSTEP;
            return;
        }
        if (t == KeyType.ArrowUp || t == KeyType.ArrowDown
                || t == KeyType.PageUp || t == KeyType.PageDown
                || (ctrl && (isChar(k, 'f') || isChar(k, 'b')))) {
            if (!w.rows.isEmpty()) {
                w.focus = DialogFocus.LIST; // §15.2: the first arrow only ENTERS; paging applies now
                if (t != KeyType.ArrowUp && t != KeyType.ArrowDown) {
                    handleVarChangesList(k);
                }
            }
            return;
        }
        if (t == KeyType.Character && !ctrl && !k.isAltDown()) {
            Character ch = k.getCharacter();
            if (ch != null && ch >= ' ') {
                w.filter.append(ch.charValue());
                rebuildVarChanges(w);
            }
        }
    }

    /**
     * §20.9 LIST focus: ↑/↓ select, ←/→ and </> hscroll, Ctrl-F/Ctrl-B and PgDn/PgUp page, g/G (and
     * Home/End) jump to the ends; {@code /} or Backspace returns to the filter line (Backspace also
     * deletes a character so editing resumes naturally).
     */
    private void handleVarChangesList(final KeyStroke k) {
        KeyType t = k.getKeyType();
        boolean ctrl = k.isCtrlDown();
        VarChangeWindow w = varChanges;
        int last = Math.max(0, w.rows.size() - 1);
        int page = Math.max(1, w.listViewH - 1);
        if (!ctrl && isChar(k, '/')) {
            w.focus = DialogFocus.QUERY;
            return;
        }
        if (t == KeyType.Backspace) {
            w.focus = DialogFocus.QUERY;
            if (w.filter.length() > 0) {
                w.filter.deleteCharAt(w.filter.length() - 1);
                rebuildVarChanges(w);
            }
            return;
        }
        if (t == KeyType.ArrowUp) {
            w.sel = Math.max(0, w.sel - 1);
        } else if (t == KeyType.ArrowDown) {
            w.sel = Math.min(last, w.sel + 1);
        } else if (t == KeyType.ArrowLeft) {
            w.hcol = Math.max(0, w.hcol - HSTEP);
        } else if (t == KeyType.ArrowRight) {
            w.hcol += HSTEP;
        } else if (t == KeyType.PageUp || (ctrl && isChar(k, 'b'))) {
            w.sel = Math.max(0, w.sel - page);
        } else if (t == KeyType.PageDown || (ctrl && isChar(k, 'f'))) {
            w.sel = Math.min(last, w.sel + page);
        } else if (t == KeyType.Home || (!ctrl && isChar(k, 'g'))) {
            w.sel = 0;
        } else if (t == KeyType.End || (!ctrl && isChar(k, 'G'))) {
            w.sel = last;
        } else if (!ctrl && isChar(k, '<')) {
            w.hcol = Math.max(0, w.hcol - HSTEP);
        } else if (!ctrl && isChar(k, '>')) {
            w.hcol += HSTEP;
        }
    }

    /**
     * §20.9 Enter: land on the nearest execution record at/before the selected write's record
     * (the §17.2 rule, {@link #landOnNearestExecAtOrBefore}), append one history entry and close.
     * A no-op when the current filter lists nothing.
     */
    private void jumpToVarChange() {
        if (varChanges.hits.isEmpty()) {
            return;
        }
        FieldWriteHit hit = varChanges.hits.get(clamp(varChanges.sel, 0, varChanges.hits.size() - 1));
        varChanges = null;
        landOnNearestExecAtOrBefore(hit.tid(), hit.index());
        afterMove();
        appendHistory();
        status = "jumped to seq " + engine.currentSeq() + " (t" + engine.currentTid() + ")";
    }

    // ---- record jump (§16.3) ----

    /** One key while the §16.3 record-jump modal is open — it consumes all keys (Ctrl-C still quits). */
    private void handleRecordJump(final KeyStroke k) {
        KeyType t = k.getKeyType();
        if (isCtrlC(k)) {
            running = false;
            return;
        }
        if (t == KeyType.Escape) {
            recordJump = null;
            return;
        }
        if (t == KeyType.Backspace) {
            recordJump.backspace();
            return;
        }
        if (t == KeyType.Enter) {
            submitRecordJump();
            return;
        }
        if (t == KeyType.Character && !k.isCtrlDown() && !k.isAltDown()) {
            Character ch = k.getCharacter();
            if (ch != null) {
                recordJump.appendDigit(ch); // non-digits are dropped by RecordJump
            }
        }
    }

    /**
     * §16.3 Enter: resolve the typed seq via {@link Engine#findSeq}. If found, close the modal
     * and land on the nearest execution record at/before it (§17.2, one history entry); if not
     * found, keep the modal open with a {@code no record with seq N} error. An
     * empty/overflowing buffer is a no-op.
     */
    private void submitRecordJump() {
        OptionalLong parsed = recordJump.parse();
        if (parsed.isEmpty()) {
            return;
        }
        long seq = parsed.getAsLong();
        SeqLocation loc = engine.findSeq(seq);
        if (!loc.found()) {
            recordJump.setError("no record with seq " + seq);
            return;
        }
        recordJump = null;
        landOnNearestExecAtOrBefore(loc.tid(), loc.index());
        afterMove();
        appendHistory();
        status = "jumped to seq " + seq + " (t" + loc.tid() + ")";
    }

    // ---- source in-file search (§16.4) ----

    /** §16.4: enter in-file search (empty query; the cursor stays where it is until a match). */
    private void enterSourceSearch() {
        sourceSearch.active = true;
        sourceSearch.query.setLength(0);
        recomputeSourceSearch();
    }

    /**
     * §16.4/§19.4: recompute occurrences (case-insensitive substring, one per matching line) over
     * the shown file, pick the current occurrence (first at/after the cursor line, wrapping) and
     * center it. Called on entry and on every query edit — which per §19.4 also clears the
     * pending-boundary state (restoring the normal field colour). An empty query clears the match
     * set without leaving search mode.
     */
    private void recomputeSourceSearch() {
        sourceSearch.boundary = null; // §19.4: a query edit clears the pending-boundary invert
        sourceSearch.occurrences = InFileSearch.occurrences(shownSourceLines(), sourceSearch.query.toString());
        if (sourceSearch.occurrences.isEmpty()) {
            sourceSearch.current = -1;
            return;
        }
        sourceSearch.current = InFileSearch.currentIndex(sourceSearch.occurrences, srcCursor);
        focusOccurrence();
    }

    /** §16.4: move the source cursor to the current occurrence and center the view on it. */
    private void focusOccurrence() {
        if (sourceSearch.current < 0 || sourceSearch.current >= sourceSearch.occurrences.size()) {
            return;
        }
        int line = sourceSearch.occurrences.get(sourceSearch.current)[0];
        int fileLines = Math.max(1, srcFileLines);
        srcCursor = clamp(line, 1, fileLines);
        srcTop = ScrollMath.center(srcCursor, Math.max(1, srcViewH), fileLines);
    }

    /**
     * §19.4 one search-motion press in {@code dir}: computes the reference line (the current
     * occurrence's line, or {@code srcCursor} when none is current), runs the pure
     * {@link InFileSearch#step} boundary/wrap state machine, stores the new pending-boundary flag
     * and — only when the step yields a move — re-centres on the resulting occurrence. Non-cyclic:
     * hitting the file boundary arms the field-colour invert (no move) instead of wrapping; the
     * next same-direction press wraps.
     */
    private void moveSearch(final InFileSearch.Dir dir) {
        List<int[]> occ = sourceSearch.occurrences;
        int refLine = sourceSearch.current >= 0 && sourceSearch.current < occ.size()
                ? occ.get(sourceSearch.current)[0]
                : srcCursor;
        InFileSearch.Step s = InFileSearch.step(occ, refLine, dir, sourceSearch.boundary);
        sourceSearch.boundary = s.armed();
        if (s.index() >= 0) {
            sourceSearch.current = s.index();
            focusOccurrence();
        }
    }

    /**
     * One key while §16.4/§19.4 in-file search is active — consumes all keys (Ctrl-C still quits):
     * Esc exits (restoring the title; cursor stays on the last occurrence), Backspace edits, Enter
     * = forward from the current line, Shift-Enter/Ctrl-Enter = reverse, ↓ = forward / ↑ = reverse
     * (universal fallback), printables extend the query. §19.4 motion is non-cyclic with a
     * two-press boundary-invert-then-wrap ({@link #moveSearch}); a query edit clears that state.
     */
    private void handleSourceSearch(final KeyStroke k) {
        KeyType t = k.getKeyType();
        if (isCtrlC(k)) {
            running = false;
            return;
        }
        if (t == KeyType.Escape) {
            sourceSearch.active = false;
            return;
        }
        if (t == KeyType.Backspace) {
            if (sourceSearch.query.length() > 0) {
                sourceSearch.query.deleteCharAt(sourceSearch.query.length() - 1);
                recomputeSourceSearch();
            }
            return;
        }
        if (t == KeyType.Enter) {
            // §19.4: Enter = forward; Alt-Enter / Shift-Enter / Ctrl-Enter = reverse. Alt-Enter
            // arrives as Enter+isAltDown (the terminal sends ESC+CR, which lanterna pairs like
            // Alt-F/Alt-R) and is the reliable reverse key on terminals where Shift/Ctrl-Enter
            // are indistinguishable from Enter (Apple Terminal).
            boolean reverse = k.isAltDown() || k.isShiftDown() || k.isCtrlDown();
            moveSearch(reverse ? InFileSearch.Dir.REVERSE : InFileSearch.Dir.FORWARD);
            return;
        }
        if (t == KeyType.ArrowDown) {
            moveSearch(InFileSearch.Dir.FORWARD); // §19.4 universal forward fallback
            return;
        }
        if (t == KeyType.ArrowUp) {
            moveSearch(InFileSearch.Dir.REVERSE); // §19.4 universal reverse fallback
            return;
        }
        if (t == KeyType.Character && !k.isCtrlDown() && !k.isAltDown()) {
            Character ch = k.getCharacter();
            if (ch != null && ch >= ' ') {
                sourceSearch.query.append(ch.charValue());
                recomputeSourceSearch();
            }
        }
    }

    // ---- open-file browser (§17.7) ----

    /**
     * Opens the {@code F} open-file browser. The file index (a {@link FileTree} over the
     * source roots) is walked once, lazily, on the first open; the dialog state — filter,
     * selection, scroll, per-directory expansion — is persistent like the {@code /} dialog
     * (§15.5), so reopening restores it exactly.
     */
    private void openFileDialog() {
        if (fileDialog == null) {
            FileTree tree = FileTree.build(sourcePath.roots());
            fileDialog = new FileDialog(tree);
            tree.setFilter(fileDialog.query.toString());
        }
        fileDialogOpen = true;
    }

    /**
     * One key while the {@code F} browser is open — it consumes all keys (Ctrl-C still the
     * emergency quit); Esc closes from either §15.2 focus, keeping the state for reopen.
     */
    private void handleFileDialog(final KeyStroke k) {
        if (isCtrlC(k)) {
            running = false;
            return;
        }
        if (k.getKeyType() == KeyType.Escape) {
            fileDialogOpen = false; // §17.7: state persists across close/reopen
            return;
        }
        if (fileDialog.focus == DialogFocus.QUERY) {
            handleFileQueryKey(k);
        } else {
            handleFileListKey(k);
        }
    }

    /**
     * QUERY-focus keys of the {@code F} browser — the §15.2 model exactly: printables edit the
     * regex filter (recomputed per keystroke), Backspace deletes, Ctrl-Backspace/Ctrl-U/Ctrl-W
     * clear, and ↑/↓ hand the focus to the tree list (when it has rows).
     */
    private void handleFileQueryKey(final KeyStroke k) {
        KeyType t = k.getKeyType();
        boolean ctrl = k.isCtrlDown();
        if ((t == KeyType.Backspace && ctrl) || (ctrl && (isChar(k, 'u') || isChar(k, 'w')))) {
            if (fileDialog.query.length() > 0) {
                fileDialog.query.setLength(0);
                applyFileFilter();
            }
            return;
        }
        if (t == KeyType.Backspace) {
            if (fileDialog.query.length() > 0) {
                fileDialog.query.deleteCharAt(fileDialog.query.length() - 1);
                applyFileFilter();
            }
            return;
        }
        if (t == KeyType.ArrowUp || t == KeyType.ArrowDown) {
            if (!fileDialog.tree.rows().isEmpty()) {
                // §15.2: the first arrow only ENTERS the list; the selection stays put.
                fileDialog.focus = DialogFocus.LIST;
            }
            return;
        }
        if (t == KeyType.Character && !ctrl && !k.isAltDown()) {
            Character ch = k.getCharacter();
            if (ch != null && ch >= ' ') {
                fileDialog.query.append(ch.charValue());
                applyFileFilter();
            }
        }
    }

    /**
     * Applies the edited {@code file:} regex to the tree (per keystroke, §17.7) and resets
     * selection/scroll (§13.1). Expansion state is deliberately untouched — it is remembered
     * across filter changes.
     */
    private void applyFileFilter() {
        fileDialog.tree.setFilter(fileDialog.query.toString());
        fileDialog.sel = 0;
        fileDialog.scroll = 0;
    }

    /**
     * LIST-focus keys of the {@code F} browser (§15.2/§17.7): ↑/↓ move, Ctrl-F/Ctrl-B and
     * PgDn/PgUp page, {@code g}/{@code G} jump to the ends, →/↵ expand a directory (↵ on a
     * FILE opens it in browse mode and closes the dialog), ← collapses (or moves to the
     * parent row), {@code /} returns to the query line; other printables are ignored.
     */
    private void handleFileListKey(final KeyStroke k) {
        KeyType t = k.getKeyType();
        boolean ctrl = k.isCtrlDown();
        List<FileTree.Row> rows = fileDialog.tree.rows();
        int last = Math.max(0, rows.size() - 1);
        int page = Math.max(1, fileDialog.listViewH - 1);
        if (!ctrl && isChar(k, '/')) {
            fileDialog.focus = DialogFocus.QUERY;
            return;
        }
        if (t == KeyType.ArrowUp) {
            fileDialog.sel = Math.max(0, fileDialog.sel - 1);
            return;
        }
        if (t == KeyType.ArrowDown) {
            fileDialog.sel = Math.min(last, fileDialog.sel + 1);
            return;
        }
        if (t == KeyType.PageUp || (ctrl && isChar(k, 'b'))) {
            fileDialog.sel = Math.max(0, fileDialog.sel - page);
            return;
        }
        if (t == KeyType.PageDown || (ctrl && isChar(k, 'f'))) {
            fileDialog.sel = Math.min(last, fileDialog.sel + page);
            return;
        }
        if (!ctrl && isChar(k, 'g')) {
            fileDialog.sel = 0;
            return;
        }
        if (!ctrl && isChar(k, 'G')) {
            fileDialog.sel = last;
            return;
        }
        if (rows.isEmpty()) {
            return;
        }
        FileTree.Row row = rows.get(clamp(fileDialog.sel, 0, last));
        if (t == KeyType.ArrowRight) {
            expandOrDescendFileRow(row, rows);
        } else if (t == KeyType.Enter) {
            if (row.directory()) {
                expandOrDescendFileRow(row, rows);
            } else {
                openFileRow(row);
            }
        } else if (t == KeyType.ArrowLeft) {
            collapseOrParentFileRow(row, rows);
        }
    }

    /** →/↵ on a directory row: expand it, or step onto its first child when already open. */
    private void expandOrDescendFileRow(final FileTree.Row row, final List<FileTree.Row> rows) {
        if (!row.directory()) {
            return;
        }
        if (!row.expanded()) {
            fileDialog.tree.expand(row.path());
        } else if (fileDialog.sel + 1 < rows.size()) {
            fileDialog.sel++; // a visible expanded dir always has a visible first child
        }
    }

    /** ← on a row: collapse an expanded directory, else move the selection to its parent row. */
    private void collapseOrParentFileRow(final FileTree.Row row, final List<FileTree.Row> rows) {
        if (row.directory() && row.expanded()) {
            fileDialog.tree.collapse(row.path());
            return;
        }
        for (int i = fileDialog.sel - 1; i >= 0; i--) {
            if (rows.get(i).depth() < row.depth()) {
                fileDialog.sel = i;
                return;
            }
        }
    }

    /** ↵ on a file row: open it in browse mode (§11.8 path) and close the dialog (§17.7). */
    private void openFileRow(final FileTree.Row row) {
        Optional<List<String>> lines = sourcePath.linesOfFile(row.path());
        if (lines.isEmpty()) {
            status = "cannot read: " + row.path();
            return;
        }
        fileDialogOpen = false;
        enterBrowse(row.path(), lines.get(), -1);
    }

    /**
     * Draws the §17.7 {@code Open file [F]} browser: the {@code file:} regex line (terminal
     * cursor on it in QUERY focus), an {@code invalid regex} note when the filter does not
     * compile, the flattened directory tree (▸/▾ directories, {@code .java} leaves, §13.1
     * edge-only scrolling) and a focus-sensitive hint row.
     */
    private void drawFileDialog(final TextGraphics g, final int cols, final int rows) {
        int w = clamp(cols - 6, 24, 74);
        int h = clamp(rows - 6, 8, 20);
        int x = Math.max(0, (cols - w) / 2);
        int y = Math.max(0, (rows - h) / 2);
        g.setForegroundColor(Palette.FG);
        g.setBackgroundColor(Palette.DIALOG_BG);
        g.disableModifiers(SGR.BOLD);
        g.fillRectangle(new TerminalPosition(x, y), new TerminalSize(w, h), ' ');
        drawBox(g, new Layout.Rect(x, y, w, h), "Open file [F]", Palette.DIALOG_BG, Palette.FOCUS, true);

        int ix = x + 1;
        int iy = y + 1;
        int iw = w - 2;
        String prompt = "file: ";
        put(g, ix, iy, clipPad(prompt + fileDialog.query, iw), Palette.FG, Palette.DIALOG_BG, false);
        if (fileDialog.focus == DialogFocus.QUERY) {
            cursorPos = new TerminalPosition(
                    Math.min(x + w - 2, ix + prompt.length() + fileDialog.query.length()), iy);
        }
        if (!fileDialog.tree.filterValid()) {
            put(g, ix, iy + 1, clip("invalid regex", iw), Palette.DIM, Palette.DIALOG_BG, false);
        }

        int hintRow = y + h - 2;
        String hint = fileDialog.focus == DialogFocus.QUERY
                ? "type to filter file names (regex)  ↑↓ tree  ^U clear  Esc close"
                : "↑↓ move  →/↵ expand  ↵ open file  ← collapse  ^F/^B pg  g/G  / query  Esc";
        put(g, ix, hintRow, clip(hint, iw), Palette.DIM, Palette.DIALOG_BG, false);

        int listTop = iy + 2;
        int listH = hintRow - listTop;
        fileDialog.listViewH = Math.max(1, listH);
        List<FileTree.Row> rows2 = fileDialog.tree.rows();
        if (rows2.isEmpty()) {
            String empty = !fileDialog.tree.filterValid() || fileDialog.query.length() > 0
                    ? "(no matches)"
                    : "(no .java files under the source roots)";
            put(g, ix, listTop, clip(empty, iw), Palette.DIM, Palette.DIALOG_BG, false);
            return;
        }
        fileDialog.sel = clamp(fileDialog.sel, 0, rows2.size() - 1);
        fileDialog.scroll = ScrollMath.adjustScroll(fileDialog.scroll, fileDialog.sel, listH, rows2.size());
        for (int r = 0; r < listH; r++) {
            int idx = fileDialog.scroll + r;
            if (idx >= rows2.size()) {
                break;
            }
            FileTree.Row row = rows2.get(idx);
            String glyph = row.directory() ? (row.expanded() ? "▾ " : "▸ ") : "  ";
            String text = "  ".repeat(row.depth()) + glyph + row.name();
            boolean s = idx == fileDialog.sel;
            put(g, ix, listTop + r, clipPad(text, iw),
                    s ? Palette.SELECTION_FG : Palette.FG, s ? Palette.SELECTION_BG : Palette.DIALOG_BG, false);
        }
    }

    private void enterBrowse(final Path file, final List<String> lines, final int line) {
        browseMode = true;
        browseKey = "browse:" + file.toAbsolutePath().normalize();
        Path name = file.getFileName();
        browseName = name != null ? name.toString() : file.toString();
        browseLines = lines;
        int target = line >= 1 ? line : 1;
        browseLine = Math.min(Math.max(target, 1), Math.max(1, lines.size()));
        browseCenterPending = true;
        srcHcol = 0;
        focus = Win.SOURCE;
        status = "browsing " + browseName + " (line " + browseLine + ") — press o to return";
    }

    /**
     * Positions the cursor on the nearest execution record (§17.1 set) at/after
     * {@code targetSeq} in thread {@code tid} by stepping (used by the {@code .} object-usage
     * jump — §17.2): {@code selectThread} and every step land on exec records, so the final
     * landing is one by construction. Capped so a huge gap cannot hang the UI.
     */
    private void seekToSeq(final int tid, final long targetSeq) {
        if (engine.currentTid() != tid) {
            engine.selectThread(tid);
        }
        long guard = 0;
        if (engine.currentSeq() < targetSeq) {
            while (engine.currentSeq() < targetSeq && guard++ < SEEK_CAP) {
                if (!engine.stepInto().moved()) {
                    break;
                }
            }
        } else if (engine.currentSeq() > targetSeq) {
            while (engine.currentSeq() > targetSeq && guard++ < SEEK_CAP) {
                if (!engine.reverseStep().moved()) {
                    break;
                }
                if (engine.currentSeq() < targetSeq) {
                    engine.stepInto();
                    break;
                }
            }
        }
    }

    // ---- navigation glue ----

    private void doStep(final StepResult r) {
        status = r.message();
        if (r.moved()) {
            afterMove();
            appendHistory();
        }
    }

    /**
     * Runs a step/run/exit-frame command honouring the frame selection (§17.5): while a
     * non-innermost RECORDED frame is selected, the cursor is first seeked to that frame's
     * as-of record ({@link Engine#frameAsOf}) and the command applies from there — the cursor
     * genuinely moves. The combined action flows through the normal {@link #doStep} append,
     * so history gets exactly ONE entry, recording the FINAL landing; a seek that moved makes
     * the whole action count as moved even when the follow-up step could not go further (the
     * previous position stays reachable from the Records window).
     */
    private void doStepFromSelected(final Supplier<StepResult> step) {
        boolean seeked = seekToSelectedFrameAsOf();
        StepResult r = step.get();
        if (seeked && !r.moved()) {
            r = new StepResult(true, r.message());
        }
        doStep(r);
    }

    /**
     * §17.5: when the SELECTED frame is a recorded, non-innermost frame, move the cursor to its
     * as-of record and report {@code true}; otherwise (innermost selected, unrecorded/grey
     * frame, or nothing to select) leave the cursor alone and report {@code false}.
     */
    private boolean seekToSelectedFrameAsOf() {
        int sf = engine.selectedFrame();
        if (sf < 0 || sf >= engine.stack().size() - 1) {
            return false; // innermost (or no frames): today's behaviour
        }
        SeqLocation loc = engine.frameAsOf(sf);
        if (!loc.found()) {
            return false; // unrecorded frame: stepping stays on the execution cursor
        }
        return engine.seek(loc.tid(), loc.index()).moved();
    }

    private void afterMove() {
        // §20.8: any action that moves the execution cursor yields browse mode to the execution
        // source — clear the browsed file and force a re-centre on the new execution line
        // (srcCursorPending is reset below). Every step and jump routes through here, so this single
        // spot covers doStep, the Records seek, and the var-history/V/object/first-mention jumps.
        if (browseMode) {
            browseMode = false;
            recenterSource = true;
        }
        // §16.2: a navigation landing in a DIFFERENT thread collapses the Locals tree, so it opens
        // fresh rather than replaying a stale expansion path against unrelated locals. A plain
        // in-thread step keeps the same tid and does NOT collapse (the reader is following state).
        if (engine.currentTid() != lastShownTid) {
            localsTree.clearExpansion();
        }
        lastShownTid = engine.currentTid();
        stackSel = 0;
        threadSel = Math.max(0, tids.indexOf(engine.currentTid()));
        followPending = true;
        stackHcol = 0;
        localsHcol = 0;
        recordsHcol = 0;
        srcCursorPending = true; // §13.4: cursor back to the execution line
    }

    /** Records the current cursor position in the history trail and selects it (§11.5). */
    private void appendHistory() {
        history.add(currentEntry());
        recordsSel = Math.max(0, history.size() - 1);
    }

    private HistoryTrail.HistoryEntry currentEntry() {
        int tid = engine.currentTid();
        long index = engine.currentIndex();
        long seq = engine.currentSeq();
        List<Frame> frames = engine.stack();
        Frame f = frames.isEmpty() ? null : frames.get(frames.size() - 1);
        String cls = f != null ? f.classBinary() : null;
        String method = f != null ? f.methodName() : null;
        String file = f != null ? f.fileName() : null;
        int line = f != null && f.currentLine() >= 0 ? f.currentLine() : 0;
        String module = sourcePath.module(cls, file).orElse("?");
        String pkg = HistoryTrail.packageOf(cls);
        return new HistoryTrail.HistoryEntry(tid, index, seq, line, cls, method, module, pkg);
    }

    /** Returns Source to following the execution cursor and leaves browse mode (§11.2). */
    private void origin() {
        browseMode = false;
        recenterSource = true;
        srcHcol = 0;
        srcCursorPending = true; // §13.4: cursor back to the execution line
        status = "origin: following the execution line";
    }

    /**
     * §20.13 {@code Ctrl-P}/{@code Ctrl-N} viewport scroll of the focused window by {@code delta}
     * lines: Source moves {@code srcTop} (the render's {@code clampTop} keeps it in range); the list
     * windows nudge their scroll offset without moving the selection ({@link ScrollMath#adjustScroll}
     * / {@link TreeView#ensureVisible} still keep the selection on-screen at render, so the offset
     * only sticks while the selection stays within the viewport).
     */
    private void scrollFocused(final int delta) {
        switch (focus) {
            case SOURCE -> {
                if (srcResolved) {
                    srcTop = Math.max(1, srcTop + delta);
                }
            }
            case THREADS -> threadScroll = Math.max(0, threadScroll + delta);
            case STACK -> stackScroll = Math.max(0, stackScroll + delta);
            case LOCALS -> localsTree.scrollBy(delta);
            case RECORDS -> recordsScroll = Math.max(0, recordsScroll + delta);
        }
    }

    private void hscrollFocused(final int delta) {
        switch (focus) {
            case SOURCE -> {
                if (delta < 0) {
                    srcHcol = Math.max(0, srcHcol + delta);
                } else if (srcResolved) {
                    srcHcol += delta;
                }
            }
            case THREADS -> threadsHcol = Math.max(0, threadsHcol + delta);
            case STACK -> stackHcol = Math.max(0, stackHcol + delta);
            case LOCALS -> localsHcol = Math.max(0, localsHcol + delta);
            case RECORDS -> recordsHcol = Math.max(0, recordsHcol + delta);
        }
    }

    private void cycleFocus(final int dir) {
        Win[] order = Win.values();
        int idx = (focus.ordinal() + dir + order.length) % order.length;
        focus = order[idx];
    }

    // ---- small helpers ----

    /** Ctrl+C — the immediate (unconfirmed) quit, §12.4. */
    private static boolean isCtrlC(final KeyStroke k) {
        return k.isCtrlDown() && isChar(k, 'c');
    }

    /** Ctrl+X — the §20.11c synonym for {@code q} (opens the §12.4 quit-confirm; help-window-only). */
    private static boolean isCtrlX(final KeyStroke k) {
        return k.isCtrlDown() && isChar(k, 'x');
    }

    private static boolean isChar(final KeyStroke k, final char c) {
        return k.getKeyType() == KeyType.Character && k.getCharacter() != null && k.getCharacter() == c;
    }

    private static int clamp(final int v, final int lo, final int hi) {
        return Math.min(Math.max(v, lo), hi);
    }

    private static String clip(final String s, final int w) {
        if (w <= 0) {
            return "";
        }
        return s.length() <= w ? s : s.substring(0, w);
    }

    private static String clipPad(final String s, final int w) {
        if (w <= 0) {
            return "";
        }
        if (s.length() >= w) {
            return s.substring(0, w);
        }
        StringBuilder b = new StringBuilder(s);
        while (b.length() < w) {
            b.append(' ');
        }
        return b.toString();
    }

    private static String describe(final Throwable e) {
        String m = e.getMessage();
        return m != null ? m : e.getClass().getSimpleName();
    }

    private String simpleClass(final String binary) {
        return binary == null ? "?" : Format.simpleName(binary);
    }

    private String formatSiteText(final Rec r) {
        Site s = recording.site(r.location());
        if (s == null) {
            return Format.typeName(r.type());
        }
        String cls = recording.symbolText(s.classSym());
        String file = recording.symbolText(s.fileSym());
        String method = s.memberSym() != 0 ? recording.symbolText(s.memberSym()) : null;
        String methodPart = method != null && !method.isEmpty() ? "." + method : "";
        return simpleClass(cls) + methodPart + " (" + (file != null ? file : "?") + ":" + s.line() + ")";
    }

    private long count(final int tid) {
        try {
            return recording.recordCount(tid);
        } catch (IOException e) {
            return 0;
        }
    }

    private Rec rec(final int tid, final long index) {
        try {
            return recording.recordAt(tid, index);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
