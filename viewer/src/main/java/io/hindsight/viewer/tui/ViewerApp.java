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
import io.hindsight.reader.LineIndex;
import io.hindsight.reader.Rec;
import io.hindsight.reader.Recording;
import io.hindsight.reader.Site;
import io.hindsight.viewer.engine.Engine;
import io.hindsight.viewer.engine.FieldWriteHit;
import io.hindsight.viewer.engine.Frame;
import io.hindsight.viewer.engine.ClassSite;
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
import java.util.BitSet;
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
    private static final int VAR_HISTORY_CAP = 500;
    // §23.2: the `.` find-object-usage dialog scans ALL records (unlike the site-domain dialogs),
    // so a degenerate broad substring can match millions — a high safety cap keeps the whole usage
    // list for any real query while preventing an out-of-memory on a pathological one.
    private static final int OBJECT_RESULT_CAP = 100_000;
    private static final String DOT = " · ";

    /** The five focusable windows, in Tab-cycle order (VIEWER.md §5, §11.3/§11.5, §12.1). */
    private enum Win {
        SOURCE, THREADS, STACK, LOCALS, RECORDS
    }

    /** Which query modal is open (§9, §11.7 regex method browse); the §17.7 file browser is separate. */
    private enum DialogKind {
        METHOD, OBJECT
    }

    /**
     * Which part of a dialog receives keys (§15.2): the query line or the result list — plus
     * {@code THREAD} for the §21.6 {@code thread:} field of the {@code /} browser (the other
     * dialogs never enter it).
     */
    private enum DialogFocus {
        QUERY, THREAD, LIST
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
        // §21.6: the `/` browser's second field — a thread id (digits) that filters results to that
        // thread (blank = all threads). Edited in THREAD focus; persists across reopen like `query`.
        final StringBuilder threadQuery = new StringBuilder();
        // §22.4: a caret index into each field ([0, len]); ←/→ move it, typing/Backspace act at it,
        // and it drives the terminal cursor column + the regex↔thread edge crossing.
        int caretRegex;
        int caretThread;
        List<String> rows = List.of();
        List<long[]> targets = List.of();
        // §20.3: for the `.` (OBJECT) dialog, the object id each result row references (0 = none),
        // so Enter on a lambda row can jump to its first mention instead of the plain record seek.
        List<Long> objectIds = List.of();
        // §24.3: lazily-computed ' -> parent' chain suffix per `.`-row index (only visible rows
        // are ever resolved — see ensureObjectChains); cleared on every recompute.
        final Map<Integer, String> chains = new HashMap<>();
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
     * Live state of the §17.7/§21.3 open-file browser: the once-built {@link FileTree} (index +
     * expansion memory + filter), the incrementally-edited query, the §15.2 QUERY/LIST focus, and
     * the list selection/scroll. §21.3 gives it two modes driven by whether {@code query} is empty:
     * BROWSE (the directory tree, {@link FileTree#rows()}) when empty, FLAT FILTER (the recursive
     * flat matching-file list, {@link FileTree#flatRows()}) when not; {@code hcol} is the
     * flat-list's horizontal scroll (revealing a clipped row's path prefix, §21.3). The one instance
     * is persistent like the {@code /} dialog (§15.5): {@code ViewerApp.fileDialog} survives Esc, so
     * reopening with {@code F} restores filter, selection, scroll and expansion exactly;
     * {@code fileDialogOpen} is the only open/closed switch. {@code listViewH} is the visible row
     * count captured at render so paging knows the page size.
     */
    private static final class FileDialog {
        final FileTree tree;
        final StringBuilder query = new StringBuilder();
        DialogFocus focus = DialogFocus.QUERY;
        int sel;
        int scroll;
        // §21.3/§24.6: row horizontal scroll — reveals a flat row's clipped path prefix and,
        // in BROWSE mode, a deep tree row's clipped right side.
        int hcol;
        int listViewH = 1;

        FileDialog(final FileTree tree) {
            this.tree = tree;
        }

        /** §21.3: FLAT-filter mode when a filter is set, BROWSE (tree) mode when the query is empty. */
        boolean flatMode() {
            return query.length() > 0;
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
     * Live state of the §16.4/§19.4/§21.2 Source Code in-file search: the {@code active} flag
     * (search mode on/off), the incrementally-edited {@code query}, the {@link InFileSearch#occurrences}
     * table ({@code {line, col}} 1-based per matching line), the {@code current} index into it (or
     * {@code -1}) and the §19.4 pending-boundary direction {@code boundary} ({@code null} =
     * disarmed; non-{@code null} inverts the whole field colour and arms the two-press wrap — §20.7
     * dropped the old red no-match state, so the field is always yellow otherwise).
     *
     * <p>§21.2 gives the field an explicit direction: {@code reverse} ({@code false} = forward/↓,
     * the default) that {@code Tab} flips and every {@code Enter} searches in (the old ↑/↓ and
     * Alt/Shift/Ctrl-Enter motion keys are gone). {@code justFlipped} is set by {@code Tab} to draw
     * the arrow INVERTED as a transient cue and cleared as soon as the user types or presses Enter.
     * One instance lives for the app; {@code active} toggles it.
     */
    private static final class SourceSearch {
        boolean active;
        final StringBuilder query = new StringBuilder();
        List<int[]> occurrences = List.of();
        int current = -1;
        InFileSearch.Dir boundary;
        boolean reverse; // §21.2: false = forward (↓, default), true = reverse (↑)
        boolean justFlipped; // §21.2: transient arrow-invert cue, cleared on type/Enter
    }

    /**
     * Live state of the §26.8 find-in-window search for the four LIST windows (Threads, Stack,
     * Locals, Records) — the Source window keeps its own §16.4 {@link SourceSearch}. Same field,
     * same keys, same two-press boundary semantics; occurrences are per ROW ({@code {row+1, col}}
     * pseudo-lines, so the §16.4 pure helpers apply unchanged) and a match moves the window's
     * SELECTION rather than a source cursor.
     */
    private static final class ListSearch {
        Win target = Win.THREADS;
        boolean active;
        final StringBuilder query = new StringBuilder();
        List<int[]> occurrences = List.of();
        int current = -1;
        InFileSearch.Dir boundary;
        boolean reverse;
        boolean justFlipped;
    }

    /**
     * Live state of the §20.9/§22.11 global variable-change window ({@code V}): a case-insensitive
     * field-name {@code filter} (empty = all) plus a §22.11 {@code thread:} id filter, the
     * currently-built rows/hits/count-note (rebuilt on every filter/thread keystroke via
     * {@link #rebuildVarChanges}, UNTRIMMED so every write is explorable), the §15.2/§22.4-style
     * QUERY/THREAD/LIST {@code focus} (the two input fields edit vs. the result list navigates), the
     * list selection/scroll/hscroll and the visible row count captured at render for paging. Rows are
     * formatted by the shared {@link VarHistory#row} so they render identically to the v1.6
     * variable-history window. One instance lives while the window is open ({@code varChanges != null}).
     */
    private static final class VarChangeWindow {
        final StringBuilder filter = new StringBuilder();
        // §22.11: a thread-id filter field (digits) to the right of the name filter (blank = all),
        // with the §22.4 edge-crossing caret behaviour; one caret per field.
        final StringBuilder threadQuery = new StringBuilder();
        int caretFilter;
        int caretThread;
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
    // §24.5: the FORMAT.md §12.1 visited-line index — drives the Source gutter's white
    // recorded-line numbers and the §24.6 file-browser coverage colours.
    private final LineIndex lineIndex;
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
    // §26.1: the one persistent `.` dialog — same treatment as the `/` dialog.
    private SearchDialog objectDialog;
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
    // §26.8: the one find-in-window state, retargeted to whichever list window opened it.
    private final ListSearch listSearch = new ListSearch();
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
    // §24.5: the browsed file's recording-visited lines (computed once on entering browse).
    private BitSet browseVisited = new BitSet();

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
        this(screen, engine, recording, sourcePath, recordingDir, initialTid,
                LineIndex.openOrBuildUnchecked(recording), OptionalLong.empty(), false);
    }

    /**
     * Full-injection constructor (§24.5/§26.7): {@code lineIndex} is the FORMAT.md §12.1
     * visited-line index — normally pre-built by {@code Main} before the UI starts, injectable
     * for tests. {@code initialSeq}/{@code startAtLast} pick the opening record (§26.7):
     * {@code --seq N} lands on the nearest exec record at/before that seq; {@code --last} lands
     * on the recording's LAST line-traced record; by default the viewer opens on the FIRST
     * line-traced record of the initial thread ({@code --tid}, else thread 1) so the Source
     * window shows a project file immediately — falling back to the engine's default position
     * when the thread has no line records.
     */
    public ViewerApp(
            final Screen screen,
            final Engine engine,
            final Recording recording,
            final SourcePath sourcePath,
            final Path recordingDir,
            final OptionalInt initialTid,
            final LineIndex lineIndex,
            final OptionalLong initialSeq,
            final boolean startAtLast) {
        this.screen = screen;
        this.engine = engine;
        this.recording = recording;
        this.sourcePath = sourcePath;
        this.lineIndex = lineIndex;
        Path absDir = recordingDir != null ? recordingDir.toAbsolutePath().normalize() : null;
        this.recordingPath = absDir != null ? absDir.toString() : "";
        Path dirName = absDir != null ? absDir.getFileName() : null;
        this.recordingName = dirName != null ? dirName.toString() : this.recordingPath;
        this.tids = engine.threads();
        this.localsTree = new TreeView(engine::children);
        boolean tidOk = true;
        if (initialTid.isPresent()) {
            StepResult r = engine.selectThread(initialTid.getAsInt());
            if (!r.moved()) {
                status = r.message(); // §26.7: a rejected --tid must stay visible
                tidOk = false;
            }
        }
        openAtInitialPosition(initialTid.isPresent() && tidOk, initialSeq, startAtLast);
        threadSel = Math.max(0, tids.indexOf(engine.currentTid()));
        stackSel = 0;
        lastShownTid = engine.currentTid(); // §16.2: seed so the first in-thread step never collapses
        appendHistory(); // seed the trail with the initial landing so Records is never empty (§11.5)
    }

    /** §26.7: position the engine on the requested opening record (see the constructor javadoc). */
    private void openAtInitialPosition(
            final boolean tidPinned, final OptionalLong initialSeq, final boolean startAtLast) {
        if (initialSeq.isPresent()) {
            // §26.7: nearest record at/before the seq — real recordings have in-range seq holes
            // (unflushed counter ticks), so an exact match must not be required.
            SeqLocation loc = engine.findSeqAtOrBefore(initialSeq.getAsLong());
            if (loc.found()) {
                landOnNearestExecAtOrBefore(loc.tid(), loc.index());
                status = "opened at seq " + engine.currentSeq() + " (t" + engine.currentTid() + ")";
            } else {
                status = "no record at or before seq " + initialSeq.getAsLong()
                        + " — opened at the default position";
            }
            return;
        }
        if (startAtLast) {
            // The recording's LAST line-traced record: each line-bearing thread's last LINE is
            // one binary search away from its final record; the greatest seq across threads wins.
            long bestSeq = -1;
            int bestTid = 0;
            long bestIndex = 0;
            for (int t : tids) {
                if (!engine.threadHasLines(t)) {
                    continue;
                }
                engine.seek(t, count(t) - 1);
                if (rec(engine.currentTid(), engine.currentIndex()).type() != Format.LINE
                        && !engine.toLineTrace(false).moved()) {
                    continue;
                }
                if (engine.currentSeq() > bestSeq) {
                    bestSeq = engine.currentSeq();
                    bestTid = engine.currentTid();
                    bestIndex = engine.currentIndex();
                }
            }
            if (bestSeq >= 0) {
                engine.seek(bestTid, bestIndex);
                status = "opened at the last line-traced record: seq " + engine.currentSeq()
                        + " (t" + engine.currentTid() + ")";
            } else {
                status = "no line-traced records — opened at the default position";
            }
            return;
        }
        // Default: the FIRST line-traced record of the initial thread (an accepted --tid, else
        // thread 1), so the Source window opens on a project file. Threads without lines keep
        // the engine default; a rejected --tid keeps its error status visible.
        int target = tidPinned ? engine.currentTid() : 1;
        if (tids.contains(target) && engine.threadHasLines(target)) {
            engine.seek(target, 0);
            if (rec(engine.currentTid(), engine.currentIndex()).type() != Format.LINE) {
                engine.toLineTrace(true);
            }
            if (status.isEmpty()) {
                status = "opened at the first line-traced record of t" + target
                        + ": seq " + engine.currentSeq();
            }
        }
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
                sourceCursorLine(), searchQuery(), lineIndex.linesOf(LineIndex.fileKey(cls, file)));
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
                Palette.FRAME_LINE_BG, sourceCursorLine(), searchQuery(), browseVisited);
    }

    /** The §13.4 cursor line to render, or -1 — the cursor shows only while Source is focused. */
    private int sourceCursorLine() {
        return focus == Win.SOURCE ? srcCursor : -1;
    }

    /** §20.7: the in-file search query to highlight in the source body, or {@code null} when not searching. */
    private String searchQuery() {
        return sourceSearch.active ? sourceSearch.query.toString() : null;
    }

    /**
     * The §20.7/§21.2 in-file search field drawn over the Source title row: a FULL-WIDTH, symmetric
     * box {@code [ Search ↓: <query………> ]} whose {@code [} sits at {@code rect.col()+2} and {@code ]}
     * at {@code rect.col()+width-3} (two columns inside each corner), so it covers the whole title
     * row — including any browse-mode {@code — <file>} title. Foreground is ALWAYS yellow on black;
     * the §19.4 pending-boundary flag still inverts the WHOLE field (fg/bg swap) to signal
     * end-of-file. §21.2: the label carries a direction arrow ({@code ↓} forward / {@code ↑}
     * reverse, {@link InFileSearch#searchPrefix}); right after a {@code Tab} flip ({@code justFlipped})
     * that ONE arrow cell is drawn inverted (black on the yellow field) as a transient cue. A query
     * longer than the input area scrolls to show its TAIL ({@link InFileSearch#visibleInput}). The
     * single border cell between {@code ]} and the corner is restored so no title leaks there. The
     * terminal cursor stays visible at the input caret — one column after the prefix, clamped in.
     */
    private void renderSourceSearchField(
            final TextGraphics g, final Layout.Rect rect, final TextColor border, final boolean bold) {
        renderSearchFieldOver(g, rect, border, bold, sourceSearch.reverse,
                sourceSearch.boundary != null, sourceSearch.justFlipped, sourceSearch.query.toString());
    }

    /**
     * The shared title-row search-field painter (§20.7/§26.8) — the Source in-file search and the
     * list-window find draw the identical {@code [ Search ↓: … ]} field over their window's title.
     */
    private void renderSearchFieldOver(
            final TextGraphics g, final Layout.Rect rect, final TextColor border, final boolean bold,
            final boolean reverse, final boolean boundaryArmed, final boolean justFlipped,
            final String query) {
        int startX = rect.col() + 2; // the '[' column
        int endBracket = rect.col() + rect.width() - 3; // the ']' column: 2 cols inside the corner
        String prefix = InFileSearch.searchPrefix(reverse);
        int prefixLen = prefix.length();
        int inputW = endBracket - startX - prefixLen; // the input area fills the rest of the row
        if (inputW <= 0) {
            return; // too narrow to draw a sensible field
        }
        String visible = InFileSearch.visibleInput(query, inputW);
        StringBuilder box = new StringBuilder(prefix).append(visible);
        while (box.length() < prefixLen + inputW) {
            box.append(' ');
        }
        box.append(']'); // ends exactly at endBracket
        // §20.7: always yellow-on-black; the §19.4 armed-boundary state inverts the whole field fg/bg.
        boolean invert = boundaryArmed;
        TextColor fieldFg = invert ? TextColor.ANSI.BLACK : TextColor.ANSI.YELLOW;
        TextColor fieldBg = invert ? TextColor.ANSI.YELLOW : TextColor.ANSI.BLACK;
        put(g, startX, rect.row(), box.toString(), fieldFg, fieldBg, false);
        // §21.2: on a fresh Tab flip, repaint the ONE arrow cell inverted relative to the field as a
        // transient cue (cleared as soon as the user types or presses Enter).
        if (justFlipped) {
            put(g, startX + InFileSearch.ARROW_OFFSET, rect.row(),
                    String.valueOf(InFileSearch.directionArrow(reverse)), fieldBg, fieldFg, false);
        }
        // Restore the ONE border cell between ']' (endBracket) and the corner (endBracket+2) so a
        // long title cannot leak through it — the field itself covers everything up to ']'.
        put(g, endBracket + 1, rect.row(), "─", border, Palette.BG, bold);
        // §19.4 visible cursor: the caret column, clamped into the input area.
        int caret = startX + prefixLen + Math.min(visible.length(), inputW - 1);
        cursorPos = new TerminalPosition(caret, rect.row());
    }

    /** §26.8: paints the find field over {@code win}'s title when the list search targets it. */
    private void maybeRenderListSearchField(
            final TextGraphics g, final Layout.Rect rect, final boolean focused, final Win win) {
        if (listSearch.active && listSearch.target == win) {
            renderSearchFieldOver(g, rect, focused ? Palette.FOCUS : Palette.UNFOCUS, focused,
                    listSearch.reverse, listSearch.boundary != null, listSearch.justFlipped,
                    listSearch.query.toString());
        }
    }

    /** §26.8: the highlight query for {@code win}'s rows, or {@code null} when not searching it. */
    private String listSearchQuery(final Win win) {
        return listSearch.active && listSearch.target == win && listSearch.query.length() > 0
                ? listSearch.query.toString()
                : null;
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
            final String searchQuery,
            final BitSet visitedLines) {
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
            // §22: the code-cell background — an actual record/frame position (isHl) always wins;
            // otherwise the cursor line gets its own darker-than-frame tint (§13.4 kept the gutter
            // marker, now the whole line is tinted); else the default.
            TextColor lineBg = isHl ? highlightBg : (isCursor ? Palette.CURSOR_LINE_BG : Palette.BG);

            // §13.4: the cursor line's GUTTER keeps the bright selection marker regardless of lineBg.
            // §24.5/§25.1: a line the recording touched at least once (the §12.1 index) gets a
            // WHITE number; an unvisited line's number is darker than the DIM chrome grey so the
            // two read apart at a glance. Cursor inversion still wins.
            boolean visited = visitedLines != null && visitedLines.get(lineNo);
            put(g, ix, y, String.format("%" + numW + "d ", lineNo),
                    isCursor ? Palette.SELECTION_FG : (visited ? Palette.FG : Palette.UNVISITED_FG),
                    isCursor ? Palette.SELECTION_BG : lineBg, false);
            if (contentW <= 0) {
                continue;
            }
            // Pre-fill the content background for any tinted line (record highlight OR cursor tint)
            // so the tint covers the whole row, not just the code characters.
            if (isHl || isCursor) {
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
        List<String> rows = threadRowTexts();
        List<TextColor> rowFg = new ArrayList<>();
        for (int tid : tids) {
            // §18.3: threads with no LINE records at all render light green; '*' text unchanged.
            rowFg.add(engine.threadHasLines(tid) ? Palette.FG : Palette.CALLS_ONLY_FG);
        }
        threadSel = clamp(threadSel, 0, Math.max(0, rows.size() - 1));
        threadScroll = ScrollMath.adjustScroll(threadScroll, threadSel, ih, rows.size());
        drawRows(g, ix, iy, iw, ih, rows, threadSel, threadScroll, threadsHcol, true, rowFg,
                listSearchQuery(Win.THREADS));
        maybeRenderListSearchField(g, rect, focused, Win.THREADS); // §26.8: over title + name
    }

    /** §26.8: the Threads rows exactly as rendered (search matches what the user sees). */
    private List<String> threadRowTexts() {
        int cur = engine.currentTid();
        List<String> rows = new ArrayList<>();
        for (int tid : tids) {
            String mark = tid == cur ? "* " : "  ";
            rows.add(mark + "t" + tid + "  \"" + engine.threadName(tid) + "\"  (" + count(tid) + ")");
        }
        return rows;
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
        List<String> rows = stackRowTexts();
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
            overlayRowMatches(g, ix, iy + r, rows.get(idx), stackHcol, iw, listSearchQuery(Win.STACK));
        }
        maybeRenderListSearchField(g, rect, focused, Win.STACK);
    }

    /** §26.8: the Stack rows (innermost first) exactly as rendered. */
    private List<String> stackRowTexts() {
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
        return rows;
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
            maybeRenderListSearchField(g, rect, focused, Win.LOCALS); // §26.8: never an invisible field
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
        drawTree(g, ix, iy, iw, ih, localsTree, localsHcol, listSearchQuery(Win.LOCALS));
        maybeRenderListSearchField(g, rect, focused, Win.LOCALS);
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
        List<String> rows = recordRowTexts();
        if (rows.isEmpty()) {
            put(g, ix, iy, clip("(no navigation yet — step to record history)", iw), Palette.DIM, Palette.BG, false);
            maybeRenderListSearchField(g, rect, focused, Win.RECORDS); // §26.8: never an invisible field
            return;
        }
        recordsSel = clamp(recordsSel, 0, rows.size() - 1);
        recordsScroll = ScrollMath.adjustScroll(recordsScroll, recordsSel, ih, rows.size());
        drawRows(g, ix, iy, iw, ih, rows, recordsSel, recordsScroll, recordsHcol, true, null,
                listSearchQuery(Win.RECORDS));
        maybeRenderListSearchField(g, rect, focused, Win.RECORDS);
    }

    /** §26.8: the Records rows exactly as rendered. */
    private List<String> recordRowTexts() {
        List<HistoryTrail.HistoryEntry> entries = history.entries();
        List<String> rows = new ArrayList<>(entries.size());
        for (HistoryTrail.HistoryEntry e : entries) {
            rows.add(HistoryTrail.render(e));
        }
        return rows;
    }

    /** §19.3 green info-line suffix appended (after 10 spaces) to the position text in a green context. */
    private static final String GREEN_INFO_SUFFIX = "No line trace info available";

    private void renderStatus(final TextGraphics g, final int cols, final int row, final RecordInfo ri) {
        // §22.2: while the F file browser is open, its selected row's full path (folder in browse
        // mode, file in flat-filter mode) — or the invalid-regex note — is shown on the global INFO
        // line, not inside the dialog. This wins over the transient status while the browser is open;
        // closing it restores the normal line.
        if (fileDialogOpen && fileDialog != null) {
            String info = fileDialog.tree.filterValid() ? selectedFilePath() : "invalid regex";
            put(g, 0, row, clipPad(info, cols), Palette.FG, Palette.BG, false);
            return;
        }
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
                // §26.6/§27.4: the explored NODE's type and value (scalar/boxed/atomic/string)
                // ride after the owning instance's class#id, so values peek without expanding.
                String base = (row.classBinary() != null ? row.classBinary() : "?") + "#" + row.objectId();
                Value nodeVal = selectedInstanceNodeValue();
                String suffix = exploredValueSuffix(nodeVal);
                if (suffix.isEmpty()) {
                    return base;
                }
                String type = TypeName.of(nodeVal, null, recording::objectClassName);
                // §27.4: skip the type when it IS the base (a wrapper ROOT's own class#id) or
                // the suffix already carries the same text — never the same thing twice.
                return type != null && !type.equals(base) && !suffix.equals("  =  " + type)
                        ? base + DOT + type + suffix
                        : base + suffix;
            }
        } else if (dialog == null && focus == Win.LOCALS) {
            String type = TypeName.of(localsSelValue, localsSelDescriptor, recording::objectClassName);
            if (type != null) {
                // §26.6: append the value unless the "type" text already IS the rendered value
                // (the descriptor-less primitive fallthrough) — no point printing it twice.
                String suffix = exploredValueSuffix(localsSelValue);
                return suffix.equals("  =  " + type) ? type : type + suffix;
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
     * §26.6: the info-line value suffix {@code "  =  <value>"} for an explored scalar-ish node —
     * a native/boxed primitive ({@link Value.Primitive}, whose rendered text already carries the
     * §20.5 hex and the §26.4 date when the number reads as one), a string ({@link Value.Str} —
     * it simply fills the line to its end), or a scalar wrapper reference
     * ({@code StringBuilder}/{@code StringBuffer}/{@code AtomicInteger}/{@code AtomicLong}/
     * {@code AtomicBoolean}, whose display embeds the inline {@code [ value ]}). Anything else
     * (plain refs, null, absent) yields no suffix.
     */
    private String exploredValueSuffix(final Value v) {
        return switch (v) {
            case Value.Primitive p -> "  =  " + p.rendered();
            case Value.Str s -> "  =  " + s.display();
            case Value.Ref ref when engine.isScalarWrapper(ref.objectId()) -> "  =  " + ref.display();
            case null, default -> "";
        };
    }

    /** §26.6: the value of the SELECTED instance-tree node (any depth), or {@code null}. */
    private Value selectedInstanceNodeValue() {
        if (dialog == null || dialog.instanceTree == null) {
            return null;
        }
        List<TreeView.Row> rows = dialog.instanceTree.rows();
        if (rows.isEmpty()) {
            return null;
        }
        int sel = clamp(dialog.instanceTree.selection(), 0, rows.size() - 1);
        return rows.get(sel).node().value();
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
        // §20.11a/§21.10: the status help omits the run-to-line family (^R/^Q/^T/^W), V, ^X and the
        // ^U/^N/^K/^L scroll keys — all live only in the F1 help window — and ends with "F1 help".
        String base = "F7 step  F8 over  ⇧F7/⇧F8 reverse  " + exitOrJump + "  o origin  "
                + "/ calls  . obj  F file  ^D recording  c/s/t/l/r focus  q quit  F1 help";
        String ctx = switch (focus) {
            case SOURCE -> browseMode
                    ? "↑↓ cursor  v var-history  Alt-F search  <> hscroll  o return  g/G/Home/End"
                    : "↑↓ cursor  v var-history  Alt-F search  ←→/<> hscroll  g/G/Home/End";
            case THREADS -> "↑↓ select  ↵ switch  Alt-F find  ←→/<> hscroll";
            case STACK -> "↑↓ select frame  Alt-F find  ←→/<> hscroll";
            case LOCALS -> "↑↓ move  →/↵ expand  ← collapse  Alt-F find  <> hscroll";
            case RECORDS -> // §22.13: r (was c); §26.9: +/- cross-thread exec step
                    "↑↓ select  ↵ jump  r record#  +/- next/prev exec any-thread  Alt-F find  ←→/<> hscroll";
        };
        put(g, 0, row, clipPad(ctx + "  |  " + base, cols), Palette.DIM, Palette.BG, false);
    }

    // ---- drawing primitives ----

    private void drawTree(
            final TextGraphics g, final int ix, final int iy, final int iw, final int ih,
            final TreeView tree, final int hcol, final String matchQuery) {
        List<String> text = treeRowTexts(tree);
        tree.ensureVisible(ih, text.size());
        drawRows(g, ix, iy, iw, ih, text, tree.selection(), tree.scroll(), hcol, true, null, matchQuery);
    }

    /** §26.8: a TreeView's visible rows exactly as {@link #drawTree} renders them. */
    private static List<String> treeRowTexts(final TreeView tree) {
        List<TreeView.Row> rows = tree.rows();
        List<String> text = new ArrayList<>(rows.size());
        for (TreeView.Row r : rows) {
            String glyph = r.expandable() ? (r.expanded() ? "▾ " : "▸ ") : "  ";
            text.add("  ".repeat(r.depth()) + glyph + r.node().label());
        }
        return text;
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
        drawRows(g, ix, iy, iw, ih, rows, sel, scroll, hcol, showSel, null, null);
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
        drawRows(g, ix, iy, iw, ih, rows, sel, scroll, hcol, showSel, rowFg, null);
    }

    /**
     * The full variant (§18.3 per-row foreground + §26.8 find-in-window highlight): every visible
     * occurrence of {@code matchQuery} within a row's clipped text repaints reverse-video on top,
     * selected and unselected rows alike (the §20.7 style).
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
            final List<TextColor> rowFg,
            final String matchQuery) {
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
            overlayRowMatches(g, ix, iy + r, rows.get(idx), hcol, iw, matchQuery);
        }
    }

    /**
     * §26.8: repaints {@code query} matches of one already-painted row, reverse-video. Matches are
     * found on the FULL row text and each span clipped into the {@code [hcol, hcol+iw)} viewport
     * (the §20.7 Source behavior), so a match half-scrolled off an edge keeps its visible part.
     */
    private void overlayRowMatches(final TextGraphics g, final int x, final int y,
            final String fullRow, final int hcol, final int iw, final String query) {
        if (query == null || query.isEmpty()) {
            return;
        }
        for (int[] span : InFileSearch.matchColumns(fullRow, query, TAB_WIDTH)) {
            int from = Math.max(span[0], hcol);
            int to = Math.min(Math.min(span[1], hcol + iw), fullRow.length());
            if (to > from) {
                put(g, x + (from - hcol), y, fullRow.substring(from, to),
                        Palette.SEARCH_MATCH_FG, Palette.SEARCH_MATCH_BG, false);
            }
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
        // §22.10/§24.1: BOTH query dialogs (`/` and `.`) are 2/3 of the terminal width and 3/4 of
        // its height, and share the whole §15.2/§21.6 layout — query + thread: fields, the §15.3
        // count line under them, and the LIST-focus-only selection highlight.
        int w = clamp(cols * 2 / 3, 30, cols - 2);
        int h = clamp(rows * 3 / 4, 8, rows - 2);
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
        // §21.6/§24.1: two fields share the query row — the query (left) and a `thread:` filter
        // (right, fixed width). → moves QUERY → THREAD and ← moves back (handleSearchQueryKey/
        // handleSearchThreadKey); the `/` browser's Tab additionally toggles calls ↔ instances.
        String threadPrompt = "thread: ";
        int threadFieldW = Math.min(threadPrompt.length() + 9, Math.max(0, iw / 2));
        int queryW = Math.max(1, iw - threadFieldW - 1); // one-column gap between the fields
        int threadX = ix + iw - threadFieldW;
        put(g, ix, iy, clipPad(prompt + dialog.query, queryW), Palette.FG, Palette.DIALOG_BG, false);
        put(g, threadX, iy, clipPad(threadPrompt + dialog.threadQuery, threadFieldW),
                Palette.FG, Palette.DIALOG_BG, false);
        if (dialog.focus == DialogFocus.QUERY) {
            // §22.4: the terminal cursor sits at the field's CARET column, not its end.
            cursorPos = new TerminalPosition(
                    Math.min(ix + queryW - 1, ix + prompt.length() + dialog.caretRegex), iy);
        } else if (dialog.focus == DialogFocus.THREAD) {
            cursorPos = new TerminalPosition(
                    Math.min(threadX + threadFieldW - 1,
                            threadX + threadPrompt.length() + dialog.caretThread), iy);
        }
        // §15.2: the cursor stays hidden (cursorPos == null) while the LIST has the focus.

        int hintRow = y + h - 2;
        put(g, ix, hintRow, clip(searchHint(), iw), Palette.DIM, Palette.DIALOG_BG, false);

        // §15.3/§24.1: the count line (or "invalid regex") sits DIM directly under the query for
        // both dialogs, so the list always runs down to the hint row.
        if (!dialog.note.isEmpty()) {
            put(g, ix, iy + 1, clip(dialog.note, iw), Palette.DIM, Palette.DIALOG_BG, false);
        }
        int listTop = iy + 2;
        int listH = hintRow - listTop;
        dialog.listViewH = Math.max(1, listH); // §15.2: page size for Ctrl-F/Ctrl-B, PgDn/PgUp

        if (dialog.rows.isEmpty()) {
            put(g, ix, listTop, clip(dialog.query.length() == 0 ? "(type to search)" : "(no matches)", iw),
                    Palette.DIM, Palette.DIALOG_BG, false);
            return;
        }
        if (dialog.kind == DialogKind.METHOD && dialog.instancesMode) {
            drawInstanceTree(g, ix, listTop, iw, listH);
            return;
        }
        dialog.sel = clamp(dialog.sel, 0, dialog.rows.size() - 1);
        // §13.1: adjust the persisted offset incrementally — never recompute from 0.
        dialog.scroll = ScrollMath.adjustScroll(dialog.scroll, dialog.sel, listH, dialog.rows.size());
        if (dialog.kind == DialogKind.OBJECT) {
            // §24.3: resolve the ' -> parent' chains for exactly the rows about to be shown.
            ensureObjectChains(dialog.scroll, dialog.scroll + listH);
        }
        String query = dialog.query.toString();
        for (int r = 0; r < listH; r++) {
            int idx = dialog.scroll + r;
            if (idx >= dialog.rows.size()) {
                break;
            }
            // §22.3/§24.1: both dialogs clear the list highlight while an input field has focus.
            boolean s = idx == dialog.sel && dialog.focus == DialogFocus.LIST;
            // §15.4/§26.1: both flat lists number their rows from 1.
            String text = DialogFormat.number(idx, dialog.rows.size()) + dialog.rows.get(idx)
                    + (dialog.kind == DialogKind.OBJECT ? dialog.chains.getOrDefault(idx, "") : "");
            // §24.1/§27.2: rows participate in the <>/←→/^K horizontal scroll, whole row included.
            String visible = HScroll.clip(text, dialog.hcol, iw);
            TextColor fg = s ? Palette.SELECTION_FG : Palette.FG;
            TextColor bg = s ? Palette.SELECTION_BG : Palette.DIALOG_BG;
            put(g, ix, listTop + r, clipPad(visible, iw), fg, bg, false);
            if (dialog.kind == DialogKind.OBJECT && !query.isEmpty()) {
                // §24.2/§26.1: every occurrence of the typed filter repaints reverse-video —
                // matched on the row CONTENT (past the §26.1 number prefix, so a digits query
                // does not light the number column), spans clipped into the hcol viewport.
                int prefixLen = DialogFormat.number(idx, dialog.rows.size()).length();
                for (int[] span : InFileSearch.matchColumns(text.substring(prefixLen), query, TAB_WIDTH)) {
                    int from = Math.max(prefixLen + span[0], dialog.hcol);
                    int to = Math.min(Math.min(prefixLen + span[1], dialog.hcol + iw), text.length());
                    if (to > from) {
                        put(g, ix + (from - dialog.hcol), listTop + r, text.substring(from, to),
                                Palette.SEARCH_MATCH_FG, Palette.SEARCH_MATCH_BG, false);
                    }
                }
            }
        }
    }

    /** The `/` and `.` dialogs' hint line, reflecting the active §15.2/§21.6 focus and mode. */
    private String searchHint() {
        if (dialog.kind == DialogKind.OBJECT) {
            if (dialog.focus == DialogFocus.QUERY) {
                return "type #id, Class#id or class  ⇥/^T thread  ↓ results  ^U clear  Esc";
            }
            if (dialog.focus == DialogFocus.THREAD) {
                return "digits: filter by thread id  ⇥/^L query  ↓ results  ^U clear  Esc";
            }
            return "↑↓ select  ↵ jump  ^U/^N scroll  <>/^K hscroll  ^F/^B pg  ^L filter  ^T thread  g/G  Esc";
        }
        if (dialog.focus == DialogFocus.QUERY) {
            return dialog.instancesMode
                    ? "type filter  → thread  ↑↓ results  ⇥ calls  ^U clear  Esc"
                    : "type filter  → thread  ↑↓ results  ⇥ instances  ^U clear  Esc";
        }
        if (dialog.focus == DialogFocus.THREAD) {
            return "digits: filter by thread id  ← regex  ↓ results  ^U clear  Esc";
        }
        return dialog.instancesMode
                ? "↑↓ move  →/↵ expand  ← collapse  f first-mention  ^F/^B pg  ^L/^T fields  / query  Esc"
                : "↑↓ select  ↵ jump  ^U/^N scroll  <>/^K hscroll  ^F/^B pg  ^L filter  ^T thread  g/G  Esc";
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
            // §22.3: clear the list highlight while the input (regex/thread) field has focus.
            boolean s = dialog.focus == DialogFocus.LIST && idx == tree.selection();
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
     * The §20.9/§22.11 global variable-change window: a centered modal (75% width, like the §16.1
     * variable-history window) with a {@code filter:} line and a §22.11 {@code thread:} field sharing
     * the top row (a visible caret in the focused field, §22.4), a DIM {@code N results} count note
     * (§22.11 dropped the cap), the ascending-seq field-write rows (shared {@link VarHistory#row}
     * formatting, §13.1 edge-only scrolling, Left/Right {@code hcol} clip) and a hint row.
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
        // §22.11: two fields share the row — the name filter (left) and a `thread:` id (right, fixed
        // width), with the §22.4 caret + edge crossing (see handleVarChangesQuery/Thread).
        String prompt = "filter: ";
        String threadPrompt = "thread: ";
        int threadFieldW = Math.min(threadPrompt.length() + 9, Math.max(0, iw / 2));
        int filterW = Math.max(1, iw - threadFieldW - 1); // one-column gap between the fields
        int threadX = ix + iw - threadFieldW;
        put(g, ix, iy, clipPad(prompt + w.filter, filterW), Palette.FG, Palette.DIALOG_BG, false);
        put(g, threadX, iy, clipPad(threadPrompt + w.threadQuery, threadFieldW),
                Palette.FG, Palette.DIALOG_BG, false);
        if (w.focus == DialogFocus.QUERY) {
            cursorPos = new TerminalPosition(
                    Math.min(ix + filterW - 1, ix + prompt.length() + w.caretFilter), iy);
        } else if (w.focus == DialogFocus.THREAD) {
            cursorPos = new TerminalPosition(
                    Math.min(threadX + threadFieldW - 1, threadX + threadPrompt.length() + w.caretThread), iy);
        }
        put(g, ix, iy + 1, clip(w.note, iw), Palette.DIM, Palette.DIALOG_BG, false);
        int hintRow = y + h - 2;
        put(g, ix, hintRow,
                clip("filter  → thread  ↑↓ select  <> hscroll  ^F/^B pg  g/G ends  ↵ jump  Esc", iw),
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
            // §22.3: clear the list highlight while an input field (filter/thread) has focus.
            boolean s = w.focus == DialogFocus.LIST && idx == w.sel;
            put(g, ix, listTop + r, HScroll.clip(w.rows.get(idx), w.hcol, iw),
                    s ? Palette.SELECTION_FG : Palette.FG, s ? Palette.SELECTION_BG : Palette.DIALOG_BG, false);
        }
    }

    private void drawHelpOverlay(final TextGraphics g, final int cols, final int rows) {
        // §20.11b/§21.10: the help WINDOW lists EVERY command, including the ones kept off the
        // trimmed status line — the run-to-line family (^R/^Q this thread, ^T/^W any thread), V,
        // Ctrl-X quit and the ^U/^N/^K/^L scroll keys. The immediate Ctrl-C quit is gone (§21.8) and
        // viewport scroll-up is now Ctrl-U (§21.9), so neither old key appears below.
        String[] lines = {
            "Hindsight Viewer — keys",
            "",
            "F7 / Shift+F7         step into / reverse step",
            "F8 / Shift+F8         step over / reverse step over",
            "                      (Apple Terminal sends ⇧F7/⇧F8 as F15/F16)",
            "e / E                 exit current frame: forward / backward",
            "j / J                 jump line-trace next/prev (green context)",
            "Ctrl+R / Ctrl+Q       run to selected line fwd/back (this thread)",
            "Ctrl+T / Ctrl+W       run to selected line fwd/back (any thread)",
            "o                     origin — return Source to the exec line",
            "Ctrl+D                show the recording directory's full path",
            "c t s l r             focus Source/Threads/Stack/Locals/Records",
            "r (in Records)        jump to a record by seq  (l fills the last)",
            "+/= -/_ (in Records)  next / prev record with line info or",
            "                      method enter/exit — ANY thread (unlike F7)",
            "Tab / Shift+Tab       cycle focus forward / backward",
            "Arrows PgUp PgDn      navigate the focused window",
            "Ctrl+U / Ctrl+N       scroll focused window up / down one line",
            "Ctrl+K / Ctrl+L       hscroll left / right (= < / > and ←/→)",
            "< / >                 horizontal-scroll the focused window",
            "^F / ^B  Home End     page / jump to ends;  g / G  top/bottom",
            "^L ^T (in dialogs)    focus the filter / thread field (⇥ in .)",
            "v                     variable history for the cursor line",
            "V                     global variable-change list (field filter)",
            "Alt-F                 find in Source / the focused list window",
            "                      (Tab flips ↓/↑; ↵ searches; Esc closes)",
            "/  .  F               browse calls / find object / open file",
            "Tab in /              calls ↔ instances;  → thread;  f first-mention",
            "Tab in .              query ↔ thread;  ↵ jump to the usage record",
            "green                 enter/exit-only (no line records)",
            "?  F1                 toggle this help window",
            "q  Ctrl+X             quit (open the confirm box)",
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
        List<String> lines = new ArrayList<>(RecordingName.wrap(recordingPath, cols));
        // §21.4: the source commit the recording was made against (empty when the target had no .git).
        String commit = recording.manifest().getProperty("sourceCommit", "");
        lines.add(commit.isEmpty() ? "(working tree — no .git commit recorded)" : "commit " + commit);
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
     * closes it (§21.8: Ctrl-C no longer quits anywhere).
     */
    private void handleRecordingPopup(final KeyStroke k) {
        if (k.getKeyType() == KeyType.Escape) {
            recordingPopup = false;
        }
    }

    /** The §16.3 "jump to record number" modal: a digit input line and (when present) an error line. */
    private void drawRecordJump(final TextGraphics g, final int cols, final int rows) {
        String prompt = "seq: " + recordJump.text();
        // §22.13: the range hint shows the highest record seq (engine.maxSeq()) as `1..<max>`.
        String range = "record # (1.." + engine.maxSeq() + ")";
        String err = recordJump.error();
        String hint = "l last  digits then ↵ jump  Esc close";
        int content = Math.max(Math.max(prompt.length(), range.length()), Math.max(err.length(), hint.length()));
        int w = Math.min(cols - 2, Math.max(24, content + 6));
        int h = Math.min(rows - 2, err.isEmpty() ? 6 : 7);
        int x = Math.max(0, (cols - w) / 2);
        int y = Math.max(0, (rows - h) / 2);
        g.setForegroundColor(Palette.FG);
        g.setBackgroundColor(Palette.DIALOG_BG);
        g.disableModifiers(SGR.BOLD);
        g.fillRectangle(new TerminalPosition(x, y), new TerminalSize(w, h), ' ');
        drawBox(g, new Layout.Rect(x, y, w, h), "Jump to record", Palette.DIALOG_BG, Palette.FOCUS, true);
        put(g, x + 3, y + 1, clip(prompt, w - 6), Palette.FG, Palette.DIALOG_BG, false);
        put(g, x + 3, y + 2, clip(range, w - 6), Palette.DIM, Palette.DIALOG_BG, false);
        int hintRow = y + h - 2;
        if (!err.isEmpty()) {
            put(g, x + 3, y + 3, clip(err, w - 6), Palette.ERROR, Palette.DIALOG_BG, false);
        }
        put(g, x + 3, hintRow, clip(hint, w - 6), Palette.DIM, Palette.DIALOG_BG, false);
        cursorPos = new TerminalPosition(Math.min(x + w - 2, x + 3 + prompt.length()), y + 1);
    }

    // ---- key dispatch ----

    private void handleKey(final KeyStroke k) {
        try {
            TerminalSize sz = screen.getTerminalSize();
            if (sz.getColumns() < MIN_COLS || sz.getRows() < MIN_ROWS) {
                // No room for the confirm box (§12.4) — q / Ctrl-X quit immediately (§20.11c;
                // §21.8 removed the Ctrl-C fallback).
                if ((isChar(k, 'q') && !k.isCtrlDown()) || isCtrlX(k)) {
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
                } else if ((isChar(k, 'q') && !k.isCtrlDown()) || isCtrlX(k)) {
                    helpOverlay = false;
                    quitConfirm = true; // §20.11c Ctrl-X is a q synonym
                }
                return;
            }
            if (recordingPopup) {
                // §17.6: the popup consumes all keys; Esc closes (§21.8: Ctrl-C no longer quits).
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
                // §20.9: the global variable-change window consumes all keys.
                handleVarChanges(k);
                return;
            }
            if (recordJump != null) {
                // §16.3: the modal consumes all keys.
                handleRecordJump(k);
                return;
            }
            if (sourceSearch.active) {
                // §16.4: intercept BEFORE global/nav so typing does not trigger focus/step keys.
                handleSourceSearch(k);
                return;
            }
            if (listSearch.active) {
                handleListSearch(k); // §26.8: same interception for the list-window search
                return;
            }
            // §12.4/§21.8 quit: only q / Ctrl-X open the confirm box (Ctrl-C no longer quits). Ctrl-Q
            // is NOT quit (it is the §21.7 backward run-to-line), so plain-q must exclude a Ctrl mod.
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
        // §21.7 run-to-line to the SELECTED source line (srcCursor) in the shown frame's file:
        // Ctrl-R forward / Ctrl-Q backward in the CURRENT thread; Ctrl-T forward / Ctrl-W backward
        // in ANY thread (by global seq). Supersedes §20.10's runToCurrentLine binding. (Ctrl-Q is
        // XOFF/flow-control on some terminals; lanterna's raw mode disables IXON so it arrives as a
        // Ctrl-'q' character here.) Routed through doStep so a move appends one history entry and a
        // non-move shows the engine's message; browse mode / no frame is reported by the helper.
        if (isChar(k, 'r') && k.isCtrlDown()) {
            runToSelectedLine(true, true);
            return true;
        }
        if (isChar(k, 'q') && k.isCtrlDown()) {
            runToSelectedLine(false, true);
            return true;
        }
        if (isChar(k, 't') && k.isCtrlDown()) {
            runToSelectedLine(true, false);
            return true;
        }
        if (isChar(k, 'w') && k.isCtrlDown()) {
            runToSelectedLine(false, false);
            return true;
        }
        // §20.13/§21.9 per-window scroll keys (global; act on the focused window): Ctrl-U scrolls the
        // viewport up, Ctrl-N down one line (selection unmoved where the window has its own selection;
        // Source moves srcTop), Ctrl-K/Ctrl-L horizontal-scroll one HSTEP (synonyms for ←/→ and </>).
        // §21.9 moved scroll-up from Ctrl-P to Ctrl-U; the input modes (dialogs, the Alt-F field)
        // consume Ctrl-U as clear-query BEFORE this, since handleKey dispatches them first.
        if (isChar(k, 'u') && k.isCtrlDown()) {
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
        // §16.4/§26.8: Alt-F opens find — the Source in-file search, or the focused list
        // window's row search (Threads/Stack/Locals/Records).
        if (isChar(k, 'f') && k.isAltDown()) {
            if (focus == Win.SOURCE) {
                enterSourceSearch();
            } else {
                enterListSearch();
            }
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
                        // §22.13: `c` focuses Source from EVERY window, including Records — undoing
                        // the §20.12 yield now that the seq-jump dialog moved to `r` (see navRecords).
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
                        // §22.13: from the Records window `r` opens the jump-to-record modal (handled
                        // in navRecords) instead of re-focusing Records — break out so handleNav gets
                        // it (mirroring the §20.12 focus-letter yield, now on `r`). Elsewhere `r`
                        // focuses Records.
                        if (focus == Win.RECORDS) {
                            break;
                        }
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
        // §22.13: in the Records window `r` opens the seq-jump modal (was `c` in §20.12, was `j` in
        // §16.3). handleGlobal yields `r` to Records rather than re-focusing Records; `c` now focuses
        // Source from here like everywhere else.
        if (isChar(k, 'r') && !k.isCtrlDown() && !k.isAltDown()) {
            recordJump = new RecordJump(); // §16.3
            return;
        }
        // §26.9: +/= jump to the NEXT and -/_ to the PREVIOUS record with line info or method
        // enter/exit by GLOBAL seq — crossing threads freely (unlike F7/⇧F7, which stay put).
        if (isChar(k, '+') || isChar(k, '=')) {
            doStep(engine.toExecAnyThread(true));
            return;
        }
        if (isChar(k, '-') || isChar(k, '_')) {
            doStep(engine.toExecAnyThread(false));
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
            // as-of the current seq at render time).
            if (searchDialog == null) {
                searchDialog = new SearchDialog(kind);
                searchDialog.instanceTree = new TreeView(engine::children);
                dialog = searchDialog;
                recomputeDialog();
            } else {
                dialog = searchDialog;
                // §22.4: reopen with each field's caret at its end (least-surprising for the persisted
                // query/thread text; §15.5 keeps the rest of the state).
                searchDialog.caretRegex = searchDialog.query.length();
                searchDialog.caretThread = searchDialog.threadQuery.length();
            }
            return;
        }
        // §26.1: the `.` dialog is persistent too — close/reopen continues from the same query,
        // thread filter, results, selection, scroll and focus (the chains cache rides along, its
        // row-index keys still matching the preserved result list).
        if (objectDialog == null) {
            objectDialog = new SearchDialog(kind);
            dialog = objectDialog;
            recomputeDialog();
        } else {
            dialog = objectDialog;
            objectDialog.caretRegex = objectDialog.query.length();
            objectDialog.caretThread = objectDialog.threadQuery.length();
        }
    }

    private void handleDialog(final KeyStroke k) {
        if (k.getKeyType() == KeyType.Escape) {
            // §15.2: Esc closes from either focus; §15.5: searchDialog keeps the `/` state.
            dialog = null;
            return;
        }
        handleSearchDialog(k); // §24.1: `/` and `.` share the whole focus model and key set
    }

    /**
     * Keys of the {@code /} and {@code .} dialogs under the §15.2 focus model (which replaced
     * the §14.2 list-active heuristic): Tab toggles the {@code /} browser's calls ↔ instances
     * (§13.2 — query kept, selection/scroll/expansion reset; the {@code .} dialog has no modes);
     * everything else goes to the QUERY line, the {@code thread:} field or the result LIST
     * depending on {@code dialog.focus}.
     */
    private void handleSearchDialog(final KeyStroke k) {
        if (k.getKeyType() == KeyType.Tab) {
            if (dialog.kind == DialogKind.METHOD) {
                dialog.instancesMode = !dialog.instancesMode;
                dialog.sel = 0;
                dialog.scroll = 0;
                recomputeDialog();
            } else {
                // §26.2: in the `.` dialog Tab toggles the query ↔ thread fields (from the LIST
                // it enters the primary query field first; the `/` browser keeps Tab for its
                // calls ↔ instances mode — use ^T/^L there).
                focusDialogField(dialog.focus == DialogFocus.QUERY ? DialogFocus.THREAD : DialogFocus.QUERY);
            }
            return;
        }
        // §27.2: ^L focuses the filter/query field and ^T the thread field, from ANY focus —
        // both dialogs. (^F stays PageDown, like everywhere else; ^L therefore no longer
        // horizontal-scrolls inside these dialogs — ^K, </> and ←/→ still do.)
        if (k.isCtrlDown() && isChar(k, 'l')) {
            focusDialogField(DialogFocus.QUERY);
            return;
        }
        if (k.isCtrlDown() && isChar(k, 't')) {
            focusDialogField(DialogFocus.THREAD);
            return;
        }
        if (dialog.focus == DialogFocus.QUERY) {
            handleSearchQueryKey(k);
        } else if (dialog.focus == DialogFocus.THREAD) {
            handleSearchThreadKey(k);
        } else {
            handleSearchListKey(k);
        }
    }

    /** §26.2/§27.2: focus a dialog input field with its caret at the end (Tab / ^L / ^T jumps). */
    private void focusDialogField(final DialogFocus field) {
        dialog.focus = field;
        if (field == DialogFocus.THREAD) {
            dialog.caretThread = dialog.threadQuery.length();
        } else {
            dialog.caretRegex = dialog.query.length();
        }
    }

    /** §22.4: the regex field as an editable {@code (text, caret)}; {@link #setRegexField} stores back. */
    private FieldEdit regexField() {
        return new FieldEdit(dialog.query.toString(), dialog.caretRegex);
    }

    private void setRegexField(final FieldEdit fe) {
        dialog.query.setLength(0);
        dialog.query.append(fe.text());
        dialog.caretRegex = fe.caret();
    }

    /** §22.4: the {@code thread:} field as an editable {@code (text, caret)}; {@link #setThreadField} stores back. */
    private FieldEdit threadField() {
        return new FieldEdit(dialog.threadQuery.toString(), dialog.caretThread);
    }

    private void setThreadField(final FieldEdit fe) {
        dialog.threadQuery.setLength(0);
        dialog.threadQuery.append(fe.text());
        dialog.caretThread = fe.caret();
    }

    /**
     * QUERY-focus keys of the {@code /} regex field (§15.2 + the §22.4 caret): printables INSERT at
     * the caret and Backspace deletes the character BEFORE it (not just the tail); Ctrl-Backspace
     * (with Ctrl-U/Ctrl-W as portable synonyms — classic terminals deliver Ctrl-Backspace as a plain
     * {@code Backspace}) clears the query, results and count line in one stroke. {@code ←}/{@code →}
     * move the caret; {@code →} at the right-most caret CROSSES to the {@code thread:} field (caret at
     * its start). {@code ↓} enters the result list (§22.3 {@code ↑}-at-top returns); {@code ↑} here is
     * a no-op (nothing sits above the regex field).
     */
    private void handleSearchQueryKey(final KeyStroke k) {
        KeyType t = k.getKeyType();
        boolean ctrl = k.isCtrlDown();
        if ((t == KeyType.Backspace && ctrl) || (ctrl && (isChar(k, 'u') || isChar(k, 'w')))) {
            dialog.query.setLength(0);
            dialog.caretRegex = 0;
            dialog.sel = 0;
            dialog.scroll = 0;
            recomputeDialog(); // empties rows and note, so the §15.3 count line disappears too
            return;
        }
        if (t == KeyType.Backspace) {
            FieldEdit fe = regexField();
            if (!fe.atStart()) {
                setRegexField(fe.backspace());
                dialog.sel = 0;
                dialog.scroll = 0;
                recomputeDialog();
            }
            return;
        }
        if (t == KeyType.ArrowLeft) {
            setRegexField(regexField().left());
            return;
        }
        if (t == KeyType.ArrowRight) {
            FieldEdit fe = regexField();
            if (fe.atEnd()) {
                dialog.focus = DialogFocus.THREAD; // §22.4: cross to the thread: field (caret at 0)
                dialog.caretThread = 0;
            } else {
                setRegexField(fe.right());
            }
            return;
        }
        if (t == KeyType.ArrowDown) {
            if (!dialog.rows.isEmpty()) {
                // §15.2/§22.4: ↓ only ENTERS the list — the already-highlighted row stays selected.
                dialog.focus = DialogFocus.LIST;
            }
            return;
        }
        if (t == KeyType.ArrowUp) {
            return; // §22.4: nothing above the regex field
        }
        if (t == KeyType.Character && !ctrl && !k.isAltDown()) {
            Character ch = k.getCharacter();
            if (ch != null && ch >= ' ') {
                setRegexField(regexField().insert(ch.charValue())); // §22.4: insert at the caret
                dialog.sel = 0;
                dialog.scroll = 0;
                recomputeDialog();
            }
        }
    }

    /**
     * THREAD-focus keys of the {@code /} browser's {@code thread:} field (§21.6 + the §22.4 caret):
     * DIGITS insert at the caret (capped at 9 digits so it always parses as an {@code int}), Backspace
     * deletes the digit before it, Ctrl-U/Ctrl-W/Ctrl-Backspace clear it. {@code ←}/{@code →} move the
     * caret; {@code ←} at the left-most caret returns to the regex field (caret at its END, §22.4).
     * {@code ↓} enters the result list (§22.3 {@code ↑}-at-top returns); {@code ↑} here is a no-op.
     * Every edit recomputes with the new thread filter (blank = all threads).
     */
    private void handleSearchThreadKey(final KeyStroke k) {
        KeyType t = k.getKeyType();
        boolean ctrl = k.isCtrlDown();
        if ((t == KeyType.Backspace && ctrl) || (ctrl && (isChar(k, 'u') || isChar(k, 'w')))) {
            if (dialog.threadQuery.length() > 0) {
                dialog.threadQuery.setLength(0);
                dialog.caretThread = 0;
                dialog.sel = 0;
                dialog.scroll = 0;
                recomputeDialog();
            }
            return;
        }
        if (t == KeyType.Backspace) {
            FieldEdit fe = threadField();
            if (!fe.atStart()) {
                setThreadField(fe.backspace());
                dialog.sel = 0;
                dialog.scroll = 0;
                recomputeDialog();
            }
            return;
        }
        if (t == KeyType.ArrowLeft) {
            FieldEdit fe = threadField();
            if (fe.atStart()) {
                dialog.focus = DialogFocus.QUERY; // §22.4: back to the regex field, caret at its end
                dialog.caretRegex = dialog.query.length();
            } else {
                setThreadField(fe.left());
            }
            return;
        }
        if (t == KeyType.ArrowRight) {
            setThreadField(threadField().right()); // §22.4: caret right (no further crossing)
            return;
        }
        if (t == KeyType.ArrowDown) {
            if (!dialog.rows.isEmpty()) {
                dialog.focus = DialogFocus.LIST;
            }
            return;
        }
        if (t == KeyType.ArrowUp) {
            return; // §22.4: nothing above the thread field
        }
        if (t == KeyType.Character && !ctrl && !k.isAltDown()) {
            Character ch = k.getCharacter();
            if (ch != null && ch >= '0' && ch <= '9' && dialog.threadQuery.length() < 9) {
                setThreadField(threadField().insert(ch.charValue())); // digits only, at the caret
                dialog.sel = 0;
                dialog.scroll = 0;
                recomputeDialog();
            }
        }
    }

    /** §21.6: the {@code /} dialog's thread filter — the parsed {@code thread:} id, or empty for all. */
    private OptionalInt threadFilter() {
        String s = dialog.threadQuery.toString();
        return s.isEmpty() ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(s));
    }

    /**
     * LIST-focus keys (§15.2/§24.1, shared by {@code /} and {@code .}): ↑/↓ move, Ctrl-F/Ctrl-B
     * and PgDn/PgUp page — Ctrl-L/Ctrl-T focus the filter/thread fields (§27.2) —
     * {@code g}/{@code G} jump to the first/last row; the four §20.13
     * per-window scroll keys work like in every other window — Ctrl-U/Ctrl-N scroll the viewport
     * one line and Ctrl-K (with {@code <}/{@code >} and ←/→, §27.2) scrolls horizontally. In instances
     * mode →/↵ expand, ← collapses, {@code f} jumps to the selected row's owning instance's first
     * mention (dialog stays open); in the flat lists (calls / object usage) ←/→ scroll
     * horizontally and ↵ jumps and closes. {@code /} returns the focus to the query line; all
     * other printables are deliberately ignored.
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
            // §22.3: ↑ on the FIRST list row returns focus to the LEFT (regex) field and clears the
            // list highlight; otherwise it moves the selection up.
            boolean atTop = dialog.instancesMode ? dialog.instanceTree.selection() == 0 : dialog.sel == 0;
            if (atTop) {
                dialog.focus = DialogFocus.QUERY;
            } else {
                moveSearchSelection(-1);
            }
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
        if (ctrl && (isChar(k, 'u') || isChar(k, 'n'))) {
            // §24.1/§27.1: viewport scroll, selection dragged along only at the view's edge —
            // the instances tree included, so scrolling never sticks at the selection.
            int delta = isChar(k, 'u') ? -1 : 1;
            if (dialog.instancesMode) {
                dialog.instanceTree.scrollBy(delta, Math.max(1, dialog.listViewH));
            } else {
                dialog.scroll = clamp(dialog.scroll + delta,
                        0, Math.max(0, dialog.rows.size() - dialog.listViewH));
                dialog.sel = clamp(dialog.sel, dialog.scroll,
                        dialog.scroll + Math.max(0, dialog.listViewH - 1));
            }
            return;
        }
        if (ctrl && isChar(k, 'k')) {
            dialog.hcol = Math.max(0, dialog.hcol - HSTEP); // §27.2: ^L now focuses the filter
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
        if (isChar(k, '<')) {
            dialog.hcol = Math.max(0, dialog.hcol - HSTEP);
            return;
        }
        if (isChar(k, '>')) {
            dialog.hcol += HSTEP;
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
            }
        } else if (t == KeyType.Enter) {
            jumpDialog(); // flat lists: jump and close (unchanged)
        } else if (t == KeyType.ArrowLeft) {
            dialog.hcol = Math.max(0, dialog.hcol - HSTEP); // §24.1: flat rows scroll horizontally
        } else if (t == KeyType.ArrowRight) {
            dialog.hcol += HSTEP;
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
        // §24.1: every recompute (query/thread edit, mode toggle) resets the horizontal scroll —
        // new results start at column 0, like the F browser's filter (§21.3).
        dialog.hcol = 0;
        switch (dialog.kind) {
            case METHOD -> {
                if (dialog.instancesMode) {
                    recomputeInstanceResults();
                } else {
                    recomputeMethodResults();
                }
                // §15.2: an empty result list cannot hold LIST focus — editing resumes. THREAD
                // focus (§21.6) is left alone so a thread filter that matches nothing keeps the
                // caret in the thread field instead of bouncing back to the regex.
                if (dialog.rows.isEmpty() && dialog.focus == DialogFocus.LIST) {
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
            // §21.6: uncapped (Integer.MAX_VALUE — no 500 trim, no "showing first 500 of N" note)
            // and filtered to the thread: field's id when set (blank = all threads).
            OptionalInt tid = threadFilter();
            MethodBrowse.Result res = MethodBrowse.filter(
                    engine.methodSites(),
                    sid -> engine.callsOf(sid, Integer.MAX_VALUE),
                    q,
                    Integer.MAX_VALUE,
                    tid);
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
            // §21.6: uncapped (Integer.MAX_VALUE — no 500 trim, no "showing first 500 of N" note; a
            // §14.1 id query already needed the full per-class list) and filtered to the thread:
            // field's id when set (blank = all threads).
            OptionalInt tid = threadFilter();
            InstanceBrowse.Result res = InstanceBrowse.filter(
                    engine.classSites(),
                    cls -> engine.instancesOf(cls, Integer.MAX_VALUE),
                    q,
                    Integer.MAX_VALUE,
                    tid);
            List<String> rows = new ArrayList<>(res.rows().size());
            List<ObjectNode> roots = new ArrayList<>(res.rows().size());
            for (InstanceBrowse.Row r : res.rows()) {
                // §22.8: build the root from engine.objectTree(id).value() (a sized, correctly-
                // expandable Ref) instead of a bare Value.Ref, so a map/set/list instance shows its
                // ", size=N" suffix and gets the right expandable flag; the "seq N · tX · " prefix
                // stays, only the trailing display now carries the size.
                Value rootValue = r.objectId() != 0
                        ? engine.objectTree(r.objectId()).value()
                        : new Value.Ref(0, r.display(), false);
                String label = "seq " + r.seq() + DOT + "t" + r.tid() + DOT + rootValue.display();
                rows.add(label);
                // §14.2: the row itself is an expandable object-tree root (expandability from the ref).
                roots.add(ObjectNode.of(label, rootValue));
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
        dialog.chains.clear(); // §24.3: chains are per-result — recompute invalidates them
        dialog.note = "";
        if (q.isEmpty()) {
            dialog.rows = rows;
            dialog.targets = targets;
            dialog.objectIds = objectIds;
            return;
        }
        // §26.1: the `/` instances grammar decides id-vs-class — so "Timestamp#123" (and "#123")
        // selects the specific object exactly like the slash command; bare digits stay an id too.
        Long id = InstanceBrowse.parseQuery(q).objectId();
        if (id == null && q.matches("\\d+")) {
            try {
                id = Long.parseLong(q);
            } catch (NumberFormatException e) {
                id = null;
            }
        }
        // §23.2: a class-name query resolves the matching instance ids ONCE from the engine's
        // class index (cheap: ~thousands of classes), then the record scan is O(1) set membership
        // per slot — instead of resolving each record's object class 3× (the §22 per-record cost).
        String needle = q.toLowerCase(Locale.ROOT);
        java.util.Set<Long> classIds = id != null ? null : instanceIdsMatchingClass(needle);
        // §23.2: no artificial 200-cap — the whole usage list is shown; a high safety cap only
        // guards against a degenerate broad substring matching millions of records (OOM).
        // §24.1: the thread: field restricts the scan to one thread (blank = all threads).
        OptionalInt tidFilter = threadFilter();
        long total = 0;
        for (int tid : tids) {
            if (tidFilter.isPresent() && tid != tidFilter.getAsInt()) {
                continue;
            }
            long n = count(tid);
            for (long i = 0; i < n; i++) {
                Rec r = rec(tid, i);
                // An id query lists every record referencing that id (usage); a class query lists
                // every record referencing an INSTANCE of the class — ENTER this, ARG values,
                // FIELD_WRITE owners/values, snapshots and first-seen alike, not just creations.
                long matchedId = id != null ? (referencesId(r, id) ? id : 0L) : firstReferencedIn(r, classIds);
                if (matchedId != 0) {
                    total++;
                    if (rows.size() < OBJECT_RESULT_CAP) {
                        // §24.2: the object metadata records (first-seen/snapshot) resolve to the
                        // OBJECT itself — their location is no source site, so the old site text
                        // degraded to a bare record-type name, which told the user nothing.
                        String site = r.type() == Format.OBJECT_FIRST_SEEN
                                ? objLabel(matchedId) + " first seen"
                                : r.type() == Format.SNAPSHOT
                                        ? objLabel(matchedId) + " snapshot"
                                        : formatSiteText(r);
                        rows.add("seq " + r.seq() + DOT + "t" + tid + DOT + site);
                        targets.add(new long[] {tid, i, r.seq()});
                        objectIds.add(matchedId); // §20.3: lets Enter detect a lambda
                    }
                }
            }
        }
        dialog.note = total == 0 ? ""
                : total > rows.size() ? "showing first " + OBJECT_RESULT_CAP + " of " + total + " — narrow the filter"
                : total + " results";
        dialog.rows = rows;
        dialog.targets = targets;
        dialog.objectIds = objectIds;
    }

    /** The object ids whose runtime class (simple or binary name) contains {@code needle} (§23.2). */
    private java.util.Set<Long> instanceIdsMatchingClass(final String needle) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (ClassSite cs : engine.classSites()) {
            String cb = cs.classBinary();
            if (cb != null
                    && (Format.simpleName(cb).toLowerCase(Locale.ROOT).contains(needle)
                            || cb.toLowerCase(Locale.ROOT).contains(needle))) {
                for (Instance inst : engine.instancesOf(cb, Integer.MAX_VALUE)) {
                    ids.add(inst.objectId());
                }
            }
        }
        return ids;
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

    /**
     * §23.2: the first object id this record references (target, or a before/after {@code OBJECT_ID}
     * slot) that is in {@code ids}, or {@code 0} if none — the class-usage match via a precomputed
     * instance-id set, so the hot record scan does O(1) lookups instead of per-record class resolution.
     */
    private long firstReferencedIn(final Rec r, final java.util.Set<Long> ids) {
        if (r.target() != 0 && ids.contains(r.target())) {
            return r.target();
        }
        if (r.beforeKind() == Format.KIND_OBJECT_ID && r.before() != 0 && ids.contains(r.before())) {
            return r.before();
        }
        if (r.afterKind() == Format.KIND_OBJECT_ID && r.after() != 0 && ids.contains(r.after())) {
            return r.after();
        }
        return 0L;
    }

    // ---- §24.3: the `.` dialog's ' -> parent' accessibility chains ----

    /** §24.3: how deep the parent search descends from the frame's accessible roots. */
    private static final int CHAIN_DEPTH_MAX = 5;

    /**
     * §24.3: how many objects the parent search may expand per row (keeps rendering snappy).
     * The budget counts DEQUEUES; each expansion's fan-out is itself bounded by the recorder's
     * container/field capture caps, so queue and parent-map stay small (measured ≤ a few hundred
     * entries on real recordings).
     */
    private static final int CHAIN_NODE_BUDGET = 200;

    /**
     * §24.3: ensures the visible {@code .}-dialog rows {@code [from, to)} have their chain
     * suffixes cached. Computing a chain must reconstruct state AT THAT ROW'S RECORD, so the
     * engine is seeked per uncached row and restored once at the end — callers see no movement.
     * Each chain answers: is the row's object directly accessible in the record's own frame
     * (a local, an argument or {@code this})? If yes the suffix is empty; if not, it is
     * {@code " -> parent -> … -> name=Class#id"} — the object's containment chain, walked
     * upward until an object that IS directly accessible; empty when no chain to an accessible
     * object was found within the §24.3 depth/budget limits (§26.1 dropped the old
     * {@code " -> ?"} marker as noise).
     */
    private void ensureObjectChains(final int from, final int to) {
        if (dialog == null || dialog.kind != DialogKind.OBJECT) {
            return;
        }
        List<Integer> missing = new ArrayList<>();
        for (int i = Math.max(0, from); i < Math.min(to, dialog.targets.size()); i++) {
            if (!dialog.chains.containsKey(i)) {
                missing.add(i);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        int curTid = engine.currentTid();
        long curIndex = engine.currentIndex();
        int curFrame = engine.selectedFrame();
        try {
            for (int i : missing) {
                String chain;
                try {
                    chain = computeObjectChain(dialog.targets.get(i), dialog.objectIds.get(i));
                } catch (RuntimeException e) {
                    chain = ""; // best-effort: an unreconstructable row simply shows no chain
                }
                dialog.chains.put(i, chain);
            }
        } finally {
            engine.seek(curTid, curIndex);
            engine.selectFrame(curFrame);
        }
    }

    /**
     * §24.3: the chain suffix for one row — BFS from the record's accessible roots (the top
     * frame's locals/args/{@code this}) DOWN the object graph as-of that record until the row's
     * object is found; the discovered path, reversed, is exactly the object's parent chain up to
     * an accessible root. Leaves the engine AT the row's record (the caller restores).
     */
    private String computeObjectChain(final long[] target, final long objId) {
        if (objId == 0) {
            return "";
        }
        engine.seek((int) target[0], target[1]);
        int top = engine.stack().size() - 1;
        if (top < 0) {
            return ""; // §26.1: no frame context at all — the row simply carries no chain
        }
        Map<Long, Long> parent = new HashMap<>(); // child object id -> parent object id (0 = a root)
        Map<Long, String> rootName = new HashMap<>(); // root object id -> local/arg/this name
        java.util.ArrayDeque<long[]> queue = new java.util.ArrayDeque<>(); // {objectId, depth}
        for (Local l : engine.locals(top)) {
            if (l.value() instanceof Value.Ref ref && ref.objectId() != 0) {
                long id = ref.objectId();
                if (id == objId) {
                    return ""; // §24.3: directly accessible from the record's own frame
                }
                if (!parent.containsKey(id)) {
                    parent.put(id, 0L);
                    rootName.put(id, l.name());
                    queue.add(new long[] {id, 0});
                }
            }
        }
        int budget = CHAIN_NODE_BUDGET;
        while (!queue.isEmpty() && budget-- > 0) {
            long[] cur = queue.poll();
            long id = cur[0];
            int depth = (int) cur[1];
            if (depth >= CHAIN_DEPTH_MAX) {
                continue;
            }
            for (long child : referencedChildIds(engine.objectTree(id), true)) {
                if (child == objId) {
                    return renderChain(id, parent, rootName);
                }
                if (!parent.containsKey(child)) {
                    parent.put(child, id);
                    queue.add(new long[] {child, depth + 1});
                }
            }
        }
        return ""; // §26.1: unreachable within the limits — no suffix (the old " -> ?" was noise)
    }

    /**
     * The object ids one level beneath {@code node} as-of the current seq: every child whose
     * value is a non-null reference, plus — one pass-through level ({@code structural}) — the
     * refs inside expandable children that carry no id themselves (§17.3 map ENTRY nodes, whose
     * key/value pair sits one level further down).
     */
    private List<Long> referencedChildIds(final ObjectNode node, final boolean structural) {
        List<Long> ids = new ArrayList<>();
        for (ObjectNode child : engine.children(node)) {
            if (child.value() instanceof Value.Ref ref && ref.objectId() != 0) {
                ids.add(ref.objectId());
            } else if (structural && child.expandable()) {
                ids.addAll(referencedChildIds(child, false));
            }
        }
        return ids;
    }

    /**
     * §24.3: renders the found containment chain, innermost parent first, ending at the
     * accessible root annotated with its variable name: {@code " -> Body#42 -> txn=Txn#7"}.
     */
    private String renderChain(final long firstParent, final Map<Long, Long> parent, final Map<Long, String> rootName) {
        StringBuilder sb = new StringBuilder();
        long id = firstParent;
        int guard = 0;
        while (id != 0 && guard++ <= CHAIN_DEPTH_MAX + 1) {
            Long up = parent.get(id);
            sb.append(" -> ");
            if (up != null && up == 0L) {
                sb.append(rootName.getOrDefault(id, "?")).append('=').append(objLabel(id));
            } else {
                sb.append(objLabel(id));
            }
            id = up == null ? 0 : up;
        }
        return sb.toString();
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
        if (kind == DialogKind.METHOD) {
            jumpToCall(tid, index);
        } else if (objId != 0 && engine.isLambda(objId)) {
            // §20.3: Enter on a lambda object jumps to its first mention (definition) instead of the
            // normal record seek — jumpToFirstMentionOf sets its own status, so return here.
            jumpToFirstMentionOf(objId, lambdaLabel(objId));
            return;
        } else {
            // §22 fix: land on the nearest exec record AT/BEFORE the selected record (the same
            // helper the V/Records/var-history jumps use) so Enter actually transitions the cursor
            // to the picked record — the old seekToSeq stepping could leave the cursor unmoved.
            landOnNearestExecAtOrBefore(tid, index);
            afterMove();
            appendHistory();
        }
        status = "jumped to seq " + engine.currentSeq() + " (t" + engine.currentTid() + ")";
    }

    /** A short {@code SimpleClass#id} object label (§24.2 rows, §24.3 chains, §20.3 statuses). */
    private String objLabel(final long objectId) {
        return simpleClass(recording.objectClassName(objectId)) + "#" + objectId;
    }

    /** A short {@code SimpleClass#id} label for a §20.3 lambda first-mention status message. */
    private String lambdaLabel(final long objectId) {
        return objLabel(objectId);
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
     * §20.9/§22.11: rebuilds the window's rows from {@link Engine#fieldWriteChanges} for the current
     * name filter AND {@code thread:} id ({@code 0} = all threads), UNTRIMMED ({@code
     * Integer.MAX_VALUE} cap) so every matching write is explorable, formats them with the shared
     * {@link VarHistory#row}, sets the DIM count note ({@code N results} — the §22.11 uncap dropped
     * the old {@code showing first 1000} note; the {@code showing first N of M} branch is now only a
     * defensive guard that never fires) and resets the selection/scroll/hscroll — called on open and
     * on every filter/thread keystroke.
     */
    private void rebuildVarChanges(final VarChangeWindow w) {
        String filter = w.filter.toString();
        int tid = varChangeThread(w);
        List<FieldWriteHit> hits = engine.fieldWriteChanges(filter, tid, Integer.MAX_VALUE);
        long total = engine.countFieldWriteChanges(filter, tid);
        List<String> rows = new ArrayList<>(hits.size());
        for (FieldWriteHit hit : hits) {
            rows.add(VarHistory.row(hit));
        }
        w.hits = hits;
        w.rows = rows;
        w.note = total > rows.size()
                ? "showing first " + rows.size() + " of " + total
                : rows.size() + " results";
        w.sel = 0;
        w.scroll = 0;
        w.hcol = 0;
    }

    /** §22.11: the V window's {@code thread:} filter as an int ({@code 0} = all threads). */
    private int varChangeThread(final VarChangeWindow w) {
        String s = w.threadQuery.toString();
        return s.isEmpty() ? 0 : Integer.parseInt(s);
    }

    /**
     * One key while the §20.9 variable-change window is open: Esc closes, Enter jumps to the
     * selected write's record from either focus, and everything else goes to the §15.2-style QUERY
     * (filter line) or LIST (result list) handler depending on {@code focus}.
     */
    private void handleVarChanges(final KeyStroke k) {
        KeyType t = k.getKeyType();
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
        } else if (varChanges.focus == DialogFocus.THREAD) {
            handleVarChangesThread(k);
        } else {
            handleVarChangesList(k);
        }
    }

    /** §22.11: the V window's name-filter field as an editable {@code (text, caret)}. */
    private FieldEdit varChangeFilterField() {
        return new FieldEdit(varChanges.filter.toString(), varChanges.caretFilter);
    }

    private void setVarChangeFilterField(final FieldEdit fe) {
        varChanges.filter.setLength(0);
        varChanges.filter.append(fe.text());
        varChanges.caretFilter = fe.caret();
    }

    /** §22.11: the V window's {@code thread:} field as an editable {@code (text, caret)}. */
    private FieldEdit varChangeThreadField() {
        return new FieldEdit(varChanges.threadQuery.toString(), varChanges.caretThread);
    }

    private void setVarChangeThreadField(final FieldEdit fe) {
        varChanges.threadQuery.setLength(0);
        varChanges.threadQuery.append(fe.text());
        varChanges.caretThread = fe.caret();
    }

    /**
     * §20.9 QUERY focus, with the §22.11/§22.4 caret: printables INSERT at the caret (recompute per
     * keystroke), Backspace deletes the character before it, Ctrl-U/Ctrl-W/Ctrl-Backspace clear it.
     * {@code ←}/{@code →} move the caret; {@code →} at the right-most caret CROSSES to the {@code
     * thread:} field. {@code ↓} (and the paging keys) hand focus to the result LIST — mirroring the
     * §15.2 {@code /} dialog so field names containing {@code g}/{@code <} type instead of triggering
     * the list commands. {@code ↑} here is a no-op (nothing above the filter field).
     */
    private void handleVarChangesQuery(final KeyStroke k) {
        KeyType t = k.getKeyType();
        boolean ctrl = k.isCtrlDown();
        VarChangeWindow w = varChanges;
        if ((t == KeyType.Backspace && ctrl) || (ctrl && (isChar(k, 'u') || isChar(k, 'w')))) {
            w.filter.setLength(0);
            w.caretFilter = 0;
            rebuildVarChanges(w);
            return;
        }
        if (t == KeyType.Backspace) {
            FieldEdit fe = varChangeFilterField();
            if (!fe.atStart()) {
                setVarChangeFilterField(fe.backspace());
                rebuildVarChanges(w);
            }
            return;
        }
        if (t == KeyType.ArrowLeft) {
            setVarChangeFilterField(varChangeFilterField().left());
            return;
        }
        if (t == KeyType.ArrowRight) {
            FieldEdit fe = varChangeFilterField();
            if (fe.atEnd()) {
                w.focus = DialogFocus.THREAD; // §22.11/§22.4: cross to the thread: field (caret at 0)
                w.caretThread = 0;
            } else {
                setVarChangeFilterField(fe.right());
            }
            return;
        }
        if (t == KeyType.ArrowDown || t == KeyType.PageUp || t == KeyType.PageDown
                || (ctrl && (isChar(k, 'f') || isChar(k, 'b')))) {
            if (!w.rows.isEmpty()) {
                w.focus = DialogFocus.LIST; // §15.2/§22.4: ↓ ENTERS; paging applies now
                if (t != KeyType.ArrowDown) {
                    handleVarChangesList(k);
                }
            }
            return;
        }
        if (t == KeyType.ArrowUp) {
            return; // §22.4: nothing above the filter field
        }
        if (t == KeyType.Character && !ctrl && !k.isAltDown()) {
            Character ch = k.getCharacter();
            if (ch != null && ch >= ' ') {
                setVarChangeFilterField(varChangeFilterField().insert(ch.charValue()));
                rebuildVarChanges(w);
            }
        }
    }

    /**
     * §22.11 THREAD focus of the V window's {@code thread:} field (the §22.4 caret): DIGITS insert at
     * the caret (≤ 9 digits so it parses as an {@code int}), Backspace deletes, Ctrl-U/Ctrl-W/Ctrl-
     * Backspace clear it. {@code ←}/{@code →} move the caret; {@code ←} at the left-most caret returns
     * to the filter field (caret at its END). {@code ↓} enters the list; {@code ↑} is a no-op. Every
     * edit recomputes with the new thread filter (blank = all threads).
     */
    private void handleVarChangesThread(final KeyStroke k) {
        KeyType t = k.getKeyType();
        boolean ctrl = k.isCtrlDown();
        VarChangeWindow w = varChanges;
        if ((t == KeyType.Backspace && ctrl) || (ctrl && (isChar(k, 'u') || isChar(k, 'w')))) {
            if (w.threadQuery.length() > 0) {
                w.threadQuery.setLength(0);
                w.caretThread = 0;
                rebuildVarChanges(w);
            }
            return;
        }
        if (t == KeyType.Backspace) {
            FieldEdit fe = varChangeThreadField();
            if (!fe.atStart()) {
                setVarChangeThreadField(fe.backspace());
                rebuildVarChanges(w);
            }
            return;
        }
        if (t == KeyType.ArrowLeft) {
            FieldEdit fe = varChangeThreadField();
            if (fe.atStart()) {
                w.focus = DialogFocus.QUERY; // §22.4: back to the filter field, caret at its end
                w.caretFilter = w.filter.length();
            } else {
                setVarChangeThreadField(fe.left());
            }
            return;
        }
        if (t == KeyType.ArrowRight) {
            setVarChangeThreadField(varChangeThreadField().right());
            return;
        }
        if (t == KeyType.ArrowDown) {
            if (!w.rows.isEmpty()) {
                w.focus = DialogFocus.LIST;
            }
            return;
        }
        if (t == KeyType.ArrowUp) {
            return; // §22.4: nothing above the thread field
        }
        if (t == KeyType.Character && !ctrl && !k.isAltDown()) {
            Character ch = k.getCharacter();
            if (ch != null && ch >= '0' && ch <= '9' && w.threadQuery.length() < 9) {
                setVarChangeThreadField(varChangeThreadField().insert(ch.charValue()));
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
            w.caretFilter = w.filter.length(); // §22.4: caret at the (new) end of the filter
            return;
        }
        if (t == KeyType.ArrowUp) {
            // §22.3: ↑ at the top returns focus to the input (filter field), clearing the highlight.
            if (w.sel <= 0) {
                w.sel = 0;
                w.focus = DialogFocus.QUERY;
            } else {
                w.sel--;
            }
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

    /** One key while the §16.3 record-jump modal is open — it consumes all keys; Esc closes. */
    private void handleRecordJump(final KeyStroke k) {
        KeyType t = k.getKeyType();
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
        if ((isChar(k, 'l') || isChar(k, 'L')) && !k.isCtrlDown() && !k.isAltDown()) {
            // §22.13: `l` jumps straight to the last record — the highest seq (the modal's
            // `1..<max>` hint). Kept inside the modal so the global `l` = focus Locals is unbroken.
            recordJump.setDigits(engine.maxSeq());
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

    // ---- find-in-window (§16.4 Source, §26.8 list windows) ----

    /** §26.8: open find-in-window on the focused list window (Alt-F outside Source). */
    private void enterListSearch() {
        listSearch.target = focus;
        listSearch.active = true;
        listSearch.query.setLength(0);
        listSearch.reverse = false;
        listSearch.justFlipped = false;
        listSearch.boundary = null;
        recomputeListSearch();
    }

    /**
     * §26.8: the searched window's rows as rendered — except Stack, whose 2-char {@code "* "}
     * selected-frame marker is blanked for MATCHING: the marker moves with every landing, and
     * occurrences must not depend on which row currently carries it.
     */
    private List<String> listRowTexts(final Win w) {
        return switch (w) {
            case THREADS -> threadRowTexts();
            case STACK -> {
                List<String> rows = new ArrayList<>(stackRowTexts());
                rows.replaceAll(t -> t.length() >= 2 ? "  " + t.substring(2) : t);
                yield rows;
            }
            case LOCALS -> treeRowTexts(localsTree);
            case RECORDS -> recordRowTexts();
            case SOURCE -> List.of(); // Source uses its own §16.4 search
        };
    }

    /** §26.8: the searched window's current selection row index. */
    private int listSelection(final Win w) {
        return switch (w) {
            case THREADS -> threadSel;
            case STACK -> stackSel;
            case LOCALS -> localsTree.selection();
            case RECORDS -> recordsSel;
            case SOURCE -> 0;
        };
    }

    /**
     * §26.8: moves the searched window's selection to {@code row} — through the SAME path the
     * window's own ↑/↓ use, so a Stack landing selects the frame (source re-centred) and the
     * others just move their selection.
     */
    private void setListSelection(final Win w, final int row) {
        switch (w) {
            case THREADS -> threadSel = clamp(row, 0, Math.max(0, tids.size() - 1));
            case STACK -> selectStackRow(clamp(row, 0, Math.max(0, engine.stack().size() - 1)));
            case LOCALS -> localsTree.select(row);
            case RECORDS -> recordsSel = clamp(row, 0, Math.max(0, history.size() - 1));
            case SOURCE -> { }
        };
    }

    /** §26.8: recompute occurrences after an edit; the row at/after the selection is current. */
    private void recomputeListSearch() {
        listSearch.boundary = null;
        List<String> rows = listRowTexts(listSearch.target);
        listSearch.occurrences = InFileSearch.occurrences(rows, listSearch.query.toString());
        listSearch.current = InFileSearch.currentIndex(
                listSearch.occurrences, listSelection(listSearch.target) + 1);
        focusListOccurrence();
    }

    private void focusListOccurrence() {
        if (listSearch.current >= 0 && listSearch.current < listSearch.occurrences.size()) {
            setListSelection(listSearch.target, listSearch.occurrences.get(listSearch.current)[0] - 1);
        }
    }

    /** §26.8: Enter — the §16.4 two-press boundary state machine over row pseudo-lines. */
    private void moveListSearch(final InFileSearch.Dir dir) {
        int refRow = listSearch.current >= 0 && listSearch.current < listSearch.occurrences.size()
                ? listSearch.occurrences.get(listSearch.current)[0]
                : listSelection(listSearch.target) + 1;
        InFileSearch.Step st = InFileSearch.step(listSearch.occurrences, refRow, dir, listSearch.boundary);
        listSearch.boundary = st.armed();
        if (st.index() >= 0) {
            listSearch.current = st.index();
            focusListOccurrence();
        }
    }

    /** §26.8: the find field's keys — identical to the Source search field (§16.4/§21.2). */
    private void handleListSearch(final KeyStroke k) {
        KeyType t = k.getKeyType();
        if (t == KeyType.Tab) {
            listSearch.reverse = !listSearch.reverse;
            listSearch.justFlipped = true;
            listSearch.boundary = null;
            return;
        }
        listSearch.justFlipped = false;
        if (t == KeyType.Escape) {
            listSearch.active = false; // the selection stays on the last match
            return;
        }
        if (k.isCtrlDown() && (isChar(k, 'u') || isChar(k, 'w'))) {
            listSearch.query.setLength(0);
            recomputeListSearch();
            return;
        }
        if (t == KeyType.Backspace) {
            if (listSearch.query.length() > 0) {
                listSearch.query.deleteCharAt(listSearch.query.length() - 1);
                recomputeListSearch();
            }
            return;
        }
        if (t == KeyType.Enter) {
            moveListSearch(listSearch.reverse ? InFileSearch.Dir.REVERSE : InFileSearch.Dir.FORWARD);
            return;
        }
        if (t == KeyType.Character && !k.isCtrlDown() && !k.isAltDown()) {
            Character ch = k.getCharacter();
            if (ch != null && ch >= ' ') {
                listSearch.query.append(ch.charValue());
                recomputeListSearch();
            }
        }
    }

    /** §16.4: enter in-file search (empty query; the cursor stays where it is until a match). */
    private void enterSourceSearch() {
        sourceSearch.active = true;
        sourceSearch.query.setLength(0);
        sourceSearch.reverse = false; // §21.2: forward (↓) by default
        sourceSearch.justFlipped = false;
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
     * One key while §16.4/§19.4/§21.2 in-file search is active — consumes all keys (§21.8: Ctrl-C
     * no longer quits): {@code Tab} flips the search direction (arrow ↓⇄↑, drawn inverted until the
     * next key — {@code justFlipped}); {@code Enter} searches in the arrow's current direction;
     * {@code Esc} exits (restoring the title; cursor stays on the last occurrence); {@code Ctrl-U}
     * clears the query (§21.9 — input modes consume Ctrl-U before the global scroll); Backspace
     * edits; printables extend the query. §19.4 motion is non-cyclic with a two-press
     * boundary-invert-then-wrap ({@link #moveSearch}); a query edit clears that state. The old ↑/↓
     * and Alt/Shift/Ctrl-Enter motion keys are removed (§21.2 — the arrow owns the direction).
     */
    private void handleSourceSearch(final KeyStroke k) {
        KeyType t = k.getKeyType();
        if (t == KeyType.Tab) {
            // §21.2: flip the direction; a direction change also clears the §19.4 pending boundary.
            sourceSearch.reverse = !sourceSearch.reverse;
            sourceSearch.justFlipped = true;
            sourceSearch.boundary = null;
            return;
        }
        // §21.2: any key other than Tab returns the arrow to its normal (non-inverted) rendering.
        sourceSearch.justFlipped = false;
        if (t == KeyType.Escape) {
            sourceSearch.active = false;
            return;
        }
        if (k.isCtrlDown() && (isChar(k, 'u') || isChar(k, 'w'))) {
            // §21.2/§21.9: Ctrl-U clears the query (Ctrl-W kept as the portable synonym).
            if (sourceSearch.query.length() > 0) {
                sourceSearch.query.setLength(0);
                recomputeSourceSearch();
            }
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
            // §21.2: Enter searches in the field's current arrow direction (Tab flips it).
            moveSearch(sourceSearch.reverse ? InFileSearch.Dir.REVERSE : InFileSearch.Dir.FORWARD);
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
            // §24.6: trees start at the project roots (--repo dirs) with every directory down to
            // the package roots visible; the recording's §12.1 file keys drive white-vs-dim rows.
            // §26.3: a commit-pinned repo lists the COMMIT's files (virtual tree from git ls-tree)
            // instead of walking its drifted working tree.
            List<FileTree.VirtualTree> virtual = new ArrayList<>();
            for (var gs : sourcePath.gitSources()) {
                List<FileTree.VirtualFile> files = new ArrayList<>(gs.fileCount());
                for (var e : gs.files()) {
                    files.add(new FileTree.VirtualFile(e.getKey(), e.getValue()));
                }
                virtual.add(new FileTree.VirtualTree(gs.repo(), files));
            }
            FileTree tree = FileTree.build(sourcePath.roots(), sourcePath.bases(), lineIndex.files(), virtual);
            fileDialog = new FileDialog(tree);
            tree.setFilter(fileDialog.query.toString());
        }
        fileDialogOpen = true;
    }

    /**
     * One key while the §21.3 {@code F} browser is open — it consumes all keys (§21.8: Ctrl-C no
     * longer quits); Esc closes from either §15.2 focus, keeping the state for reopen (§15.5).
     */
    private void handleFileDialog(final KeyStroke k) {
        if (k.isCtrlDown() && isChar(k, 'l')) {
            fileDialog.focus = DialogFocus.QUERY; // §27.2: ^L jumps to the filter field
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

    /** The current mode's row count (§21.3): flat matching files when filtering, else tree rows. */
    private int fileRowCount() {
        return fileDialog.flatMode()
                ? fileDialog.tree.flatRows().size()
                : fileDialog.tree.rows().size();
    }

    /**
     * QUERY-focus keys of the {@code F} browser (§15.2/§21.3): printables edit the filter
     * (recomputed per keystroke — a non-empty filter switches BROWSE → FLAT), Backspace deletes,
     * Ctrl-Backspace/Ctrl-U/Ctrl-W clear (FLAT → BROWSE), and ↓ hands the focus to the result list
     * (when it has rows). ↑ stays in the query (nothing sits above it).
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
        if (t == KeyType.ArrowDown) {
            if (fileRowCount() > 0) {
                // §15.2/§21.3: ↓ enters the list at the top row; the selection stays put.
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
     * Applies the edited filter to the tree (per keystroke) and resets selection/scroll/hscroll
     * (§13.1/§21.3). Expansion state is deliberately untouched — it is remembered across filter
     * changes and used again when the filter is cleared back to BROWSE mode.
     */
    private void applyFileFilter() {
        fileDialog.tree.setFilter(fileDialog.query.toString());
        fileDialog.sel = 0;
        fileDialog.scroll = 0;
        fileDialog.hcol = 0;
    }

    /**
     * LIST-focus keys of the {@code F} browser (§15.2/§21.3): ↑/↓ move (↑ at the TOP row returns
     * focus to the filter field, clearing the highlight), Ctrl-F/Ctrl-B and PgDn/PgUp page,
     * {@code g}/{@code G} jump to the ends, {@code /} returns to the filter line. In BROWSE mode
     * →/↵ expand a directory (↵ on a FILE opens it), ← collapses (or moves to the parent). In FLAT
     * mode expand/collapse is hidden: ↵ opens the selected file and ←/→ (and {@code <}/{@code >})
     * horizontally scroll the selected row to reveal its clipped path prefix. Ctrl-F/Ctrl-B and
     * PgDn/PgUp page; Ctrl-L focuses the filter field (§27.2).
     */
    private void handleFileListKey(final KeyStroke k) {
        KeyType t = k.getKeyType();
        boolean ctrl = k.isCtrlDown();
        boolean flat = fileDialog.flatMode();
        int count = fileRowCount();
        int last = Math.max(0, count - 1);
        int page = Math.max(1, fileDialog.listViewH - 1);
        if (!ctrl && isChar(k, '/')) {
            fileDialog.focus = DialogFocus.QUERY;
            return;
        }
        if (t == KeyType.ArrowUp) {
            if (fileDialog.sel <= 0) {
                // §21.3: ↑ at the top returns focus to the filter field (clears the list highlight).
                fileDialog.sel = 0;
                fileDialog.focus = DialogFocus.QUERY;
            } else {
                fileDialog.sel--;
            }
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
        // §24.6: the four §20.13 per-window scroll keys work here like in every other window —
        // Ctrl-U/Ctrl-N scroll the viewport one line (selection dragged along the nearest edge
        // only when it would leave the view), Ctrl-K (and </> below, §27.2) scrolls horizontally.
        if (ctrl && (isChar(k, 'u') || isChar(k, 'n'))) {
            fileDialog.scroll = clamp(fileDialog.scroll + (isChar(k, 'u') ? -1 : 1),
                    0, Math.max(0, count - fileDialog.listViewH));
            fileDialog.sel = clamp(fileDialog.sel, fileDialog.scroll,
                    fileDialog.scroll + Math.max(0, fileDialog.listViewH - 1));
            return;
        }
        if (ctrl && isChar(k, 'k')) {
            fileDialog.hcol = Math.max(0, fileDialog.hcol - HSTEP); // §27.2: ^L focuses the filter
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
        if (isChar(k, '<')) {
            fileDialog.hcol = Math.max(0, fileDialog.hcol - HSTEP);
            return;
        }
        if (isChar(k, '>')) {
            fileDialog.hcol += HSTEP;
            return;
        }
        if (count == 0) {
            return;
        }
        int sel = clamp(fileDialog.sel, 0, last);
        if (flat) {
            if (t == KeyType.Enter) {
                openBrowseFile(fileDialog.tree.flatRows().get(sel).path());
            } else if (t == KeyType.ArrowLeft) {
                fileDialog.hcol = Math.max(0, fileDialog.hcol - HSTEP);
            } else if (t == KeyType.ArrowRight) {
                fileDialog.hcol += HSTEP;
            }
            return;
        }
        List<FileTree.Row> rows = fileDialog.tree.rows();
        FileTree.Row row = rows.get(sel);
        if (t == KeyType.ArrowRight) {
            expandOrDescendFileRow(row, rows);
        } else if (t == KeyType.Enter) {
            if (row.directory()) {
                expandOrDescendFileRow(row, rows);
            } else {
                openBrowseFile(row.path());
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

    /** ↵ on a file row (BROWSE or FLAT): open it in browse mode (§11.8 path) and close the dialog. */
    private void openBrowseFile(final Path path) {
        Optional<List<String>> lines = sourcePath.linesOfFile(path);
        if (lines.isEmpty()) {
            status = "cannot read: " + path;
            return;
        }
        fileDialogOpen = false;
        enterBrowse(path, lines.get(), -1);
    }

    /** The §21.3 info-line path: the selected row's absolute path (folder in BROWSE, file in FLAT). */
    private String selectedFilePath() {
        if (fileDialog.flatMode()) {
            List<FileTree.FlatRow> rows = fileDialog.tree.flatRows();
            return rows.isEmpty() ? "" : rows.get(clamp(fileDialog.sel, 0, rows.size() - 1)).path().toString();
        }
        List<FileTree.Row> rows = fileDialog.tree.rows();
        return rows.isEmpty() ? "" : rows.get(clamp(fileDialog.sel, 0, rows.size() - 1)).path().toString();
    }

    /**
     * Draws the §21.3 {@code Open file [F]} browser (window width 2/3 of the terminal): the
     * {@code file:} filter line (terminal cursor on it in QUERY focus), an info line showing the
     * selected row's full path (or {@code invalid regex}), then either the BROWSE directory tree
     * (▸/▾ directories, {@code .java} leaves) when the filter is empty or the FLAT matching-file
     * list (each row the horizontally-scrollable path suffix, {@link FileTree#suffixView}) when it
     * is not, with §13.1 edge-only scrolling and a mode/focus-sensitive hint. The list highlight is
     * drawn only in LIST focus (QUERY focus clears it).
     */
    private void drawFileDialog(final TextGraphics g, final int cols, final int rows) {
        int w = clamp(cols * 2 / 3, 30, cols - 2); // §21.3: 2/3 of the terminal width
        int h = clamp(rows * 3 / 4, 8, rows - 2); // §22.10: 3/4 of the terminal height
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
        boolean flat = fileDialog.flatMode();
        String prompt = "file: ";
        put(g, ix, iy, clipPad(prompt + fileDialog.query, iw), Palette.FG, Palette.DIALOG_BG, false);
        if (fileDialog.focus == DialogFocus.QUERY) {
            cursorPos = new TerminalPosition(
                    Math.min(x + w - 2, ix + prompt.length() + fileDialog.query.length()), iy);
        }
        // §22.2: the selected path moved OUT of the dialog to the global INFO line (renderStatus), so
        // the list now starts directly under the filter line (one more visible row).

        int hintRow = y + h - 2;
        String hint;
        if (fileDialog.focus == DialogFocus.QUERY) {
            hint = "type to filter (regex)  ↓ list  ^U clear  Esc close";
        } else if (flat) {
            hint = "↑↓ move  ↵ open  ←→/<>/^K hscroll  ^U/^N scroll  ^F/^B pg  / or ^L filter  g/G  Esc";
        } else {
            hint = "↑↓ move  →/↵ open/expand  ← collapse  <>/^K hscroll  ^U/^N scroll  ^F/^B pg  / or ^L filter  g/G  Esc";
        }
        put(g, ix, hintRow, clip(hint, iw), Palette.DIM, Palette.DIALOG_BG, false);

        int listTop = iy + 1; // §22.2: no in-dialog info line anymore
        int listH = hintRow - listTop;
        fileDialog.listViewH = Math.max(1, listH);
        int count = fileRowCount();
        if (count == 0) {
            String empty = !fileDialog.tree.filterValid() ? "(invalid regex)"
                    : flat ? "(no matching files)"
                    : "(no .java files under the source roots)";
            put(g, ix, listTop, clip(empty, iw), Palette.DIM, Palette.DIALOG_BG, false);
            return;
        }
        fileDialog.sel = clamp(fileDialog.sel, 0, count - 1);
        fileDialog.scroll = ScrollMath.adjustScroll(fileDialog.scroll, fileDialog.sel, listH, count);
        boolean listFocus = fileDialog.focus == DialogFocus.LIST;
        List<FileTree.Row> browseRows = flat ? List.of() : fileDialog.tree.rows();
        List<FileTree.FlatRow> flatRows = flat ? fileDialog.tree.flatRows() : List.of();
        String query = fileDialog.query.toString();
        for (int r = 0; r < listH; r++) {
            int idx = fileDialog.scroll + r;
            if (idx >= count) {
                break;
            }
            boolean s = listFocus && idx == fileDialog.sel; // §21.3: highlight only in LIST focus
            // §24.6: rows the recording touched (a covered file, or a directory holding one) are
            // WHITE; everything else uses the Source gutter's dim grey. Selection inversion wins.
            boolean covered = flat ? flatRows.get(idx).covered() : browseRows.get(idx).covered();
            TextColor fg = s ? Palette.SELECTION_FG : (covered ? Palette.FG : Palette.DIM);
            TextColor bg = s ? Palette.SELECTION_BG : Palette.DIALOG_BG;
            if (flat) {
                drawFlatFileRow(g, ix, listTop + r, iw, flatRows.get(idx).rel(), query, fg, bg);
            } else {
                // §24.6/§27.2: BROWSE rows participate in the ^K / </> horizontal scroll like every
                // other list window (deep trees clip on the right otherwise).
                put(g, ix, listTop + r, clipPad(HScroll.clip(browseRowText(browseRows.get(idx)),
                        fileDialog.hcol, iw), iw), fg, bg, false);
            }
        }
    }

    /**
     * §22.2 flat-filter row: the root-relative path {@link FileTree#rightAlign right-aligned} into
     * {@code iw} columns (file name flush-right, prefix clips/scrolls on the left) painted in the
     * row's base colours, then EVERY occurrence of the filter {@code query} within the visible text
     * repainted reverse-video ({@link Palette#SEARCH_MATCH_FG}/{@link Palette#SEARCH_MATCH_BG}), so
     * matches stand out on selected and unselected rows alike (matches are found case-insensitively
     * on the already-clipped text, so a match scrolled partly off the left edge simply is not shown).
     */
    private void drawFlatFileRow(final TextGraphics g, final int ix, final int y, final int iw,
            final String rel, final String query, final TextColor fg, final TextColor bg) {
        String text = FileTree.rightAlign(rel, iw, fileDialog.hcol);
        put(g, ix, y, text, fg, bg, false);
        for (int[] span : InFileSearch.regexMatchColumns(text, query, TAB_WIDTH)) {
            int from = Math.max(0, span[0]);
            int to = Math.min(text.length(), span[1]);
            if (to > from) {
                put(g, ix + from, y, text.substring(from, to),
                        Palette.SEARCH_MATCH_FG, Palette.SEARCH_MATCH_BG, false);
            }
        }
    }

    /** The BROWSE-mode tree row's rendered text: indentation + a ▸/▾ (dir) or blank (file) glyph. */
    private static String browseRowText(final FileTree.Row row) {
        String glyph = row.directory() ? (row.expanded() ? "▾ " : "▸ ") : "  ";
        return "  ".repeat(row.depth()) + glyph + row.name();
    }

    private void enterBrowse(final Path file, final List<String> lines, final int line) {
        browseMode = true;
        browseKey = "browse:" + file.toAbsolutePath().normalize();
        Path name = file.getFileName();
        browseName = name != null ? name.toString() : file.toString();
        browseVisited = visitedLinesOfFile(file);
        browseLines = lines;
        int target = line >= 1 ? line : 1;
        browseLine = Math.min(Math.max(target, 1), Math.max(1, lines.size()));
        browseCenterPending = true;
        srcHcol = 0;
        focus = Win.SOURCE;
        status = "browsing " + browseName + " (line " + browseLine + ") — press o to return";
    }

    /**
     * §24.5: the visited lines of an arbitrary (on-disk or §26.3 virtual) file — its §12.1 key
     * comes from {@link SourcePath#coverageKey} (the owning commit-pinned repo, else the path
     * relative to the deepest containing source root), so a browsed file lights up its recorded
     * line numbers exactly like a frame-resolved one. Empty when the file lies outside every
     * source root or the recording never touched it.
     */
    private BitSet visitedLinesOfFile(final Path file) {
        return sourcePath.coverageKey(file).map(lineIndex::linesOf).orElseGet(BitSet::new);
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
     * §22.5 run-to-line on the SHOWN file (supersedes §21.7's frame-only resolution): run to the
     * next ({@code forward}) / previous record passing through the SELECTED source line — the
     * highlighted-number line {@code srcCursor} in the shown file — in the current thread
     * ({@code sameThread}) or any thread. The engine resolves LINE sites by the file's BASE NAME
     * (§22.14), so it works in BOTH modes: in exec mode the shown file is the selected frame's
     * {@link Frame#fileName()}, in browse mode it is the browsed file ({@code browseName}) — browse
     * mode no longer bails, which (together with passing the file name rather than the binary class
     * the old code wrongly fed the file-name parameter) is the {@code Ctrl-Q} fix. Only "no shown
     * file / no resolvable line" is reported by us; the engine's own {@link StepResult} (a move, or
     * "no earlier/later pass"/"no LINE record for this line") flows through {@link #doStep} so a move
     * appends exactly one history entry and leaves browse mode via {@link #afterMove}.
     *
     * @param forward {@code true} to run to the next pass, {@code false} to the previous one
     * @param sameThread {@code true} to stay in the current thread ({@code Ctrl-R}/{@code Ctrl-Q}),
     *     {@code false} to search all threads ({@code Ctrl-T}/{@code Ctrl-W})
     */
    private void runToSelectedLine(final boolean forward, final boolean sameThread) {
        String fileName;
        if (browseMode) {
            fileName = browseName; // the browsed file's base name (browse has no frame)
        } else {
            Frame f = selectedFrameOrNull();
            fileName = f != null ? f.fileName() : null;
        }
        if (fileName == null || fileName.isEmpty()) {
            status = "run-to-line: no shown file to resolve the selected line";
            return;
        }
        doStep(engine.runToLine(fileName, srcCursor, forward, sameThread));
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
     * §20.13/§21.9 {@code Ctrl-U}/{@code Ctrl-N} viewport scroll of the focused window by {@code delta}
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
