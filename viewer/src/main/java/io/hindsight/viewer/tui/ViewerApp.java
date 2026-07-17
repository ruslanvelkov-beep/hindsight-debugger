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
 * </ul>
 */
public final class ViewerApp {

    private static final int MIN_COLS = 80;
    private static final int MIN_ROWS = 24;
    private static final int TAB_WIDTH = 4;
    private static final int HSTEP = 4;
    private static final int RESULT_CAP = 200;
    private static final int VAR_HISTORY_CAP = 500;
    private static final long SEEK_CAP = 5_000_000L;
    private static final String DOT = " · ";

    /** The five focusable windows, in Tab-cycle order (VIEWER.md §5, §11.3/§11.5, §12.1). */
    private enum Win {
        SOURCE, THREADS, STACK, LOCALS, RECORDS
    }

    /** Which modal dialog is open (§9, §11.7 regex method browse, §11.8 open file). */
    private enum DialogKind {
        METHOD, OBJECT, FILE
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
     * Live state of the §13.5 variable-history overlay: the matching field writes (rows rendered
     * once at open — values are as-of the write, not the cursor), a §13.1 persistent scroll, and a
     * cap note.
     */
    private static final class VarHistoryWindow {
        final List<String> rows;
        final List<FieldWriteHit> hits;
        final String note;
        int sel;
        int scroll;
        int hcol;

        VarHistoryWindow(final List<String> rows, final List<FieldWriteHit> hits, final String note) {
            this.rows = rows;
            this.hits = hits;
            this.note = note;
        }
    }

    private final Screen screen;
    private final Engine engine;
    private final Recording recording;
    private final SourcePath sourcePath;
    private final JavaHighlighter highlighter = new JavaHighlighter();
    private final List<Integer> tids;

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
    private VarHistoryWindow varHist;
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
     * @param sourcePath resolves class/file to source lines and modules
     * @param initialTid the {@code --tid} thread to focus, or empty for the engine's default
     */
    public ViewerApp(
            final Screen screen,
            final Engine engine,
            final Recording recording,
            final SourcePath sourcePath,
            final OptionalInt initialTid) {
        this.screen = screen;
        this.engine = engine;
        this.recording = recording;
        this.sourcePath = sourcePath;
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
        if (varHist != null) {
            drawVarHistory(g, cols, rows);
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

    /**
     * The Source window title (§15.7): browse mode keeps {@code Source Code [c] — <fileName>};
     * exec mode appends the SELECTED frame's {@code SimpleClass.method} after exactly ten spaces
     * (clipped by the border like any title), or nothing when there is no frame.
     */
    private String sourceTitle() {
        if (browseMode) {
            return "Source Code [c] — " + browseName;
        }
        Frame f = selectedFrameOrNull();
        return f == null ? "Source Code [c]" : "Source Code [c]" + " ".repeat(10) + FrameText.simple(f);
    }

    /** The currently selected stack frame — recorded or unrecorded (§14.5) — or {@code null}. */
    private Frame selectedFrameOrNull() {
        List<Frame> frames = engine.stack();
        int sf = engine.selectedFrame();
        return sf >= 0 && sf < frames.size() ? frames.get(sf) : null;
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
        boolean exec = sf == frames.size() - 1 && frame.recorded();
        renderCodeLines(g, ix, iy, iw, ih, lines, spans, srcTop, srcHcol, line, exec, sourceCursorLine());
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
        renderCodeLines(g, ix, iy, iw, ih, browseLines, spans, srcTop, srcHcol, line, false, sourceCursorLine());
    }

    /** The §13.4 cursor line to render, or -1 — the cursor shows only while Source is focused. */
    private int sourceCursorLine() {
        return focus == Win.SOURCE ? srcCursor : -1;
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
            final boolean execHighlight,
            final int cursorLine) {
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
            boolean isExec = isHl && execHighlight;
            boolean isCursor = lineNo == cursorLine;
            TextColor lineBg = isHl ? (isExec ? Palette.EXEC_LINE_BG : Palette.FRAME_LINE_BG) : Palette.BG;

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
        }
    }

    private void renderThreads(final TextGraphics g, final Layout.Rect rect) {
        boolean focused = focus == Win.THREADS;
        drawBox(g, rect, "Threads [t]", Palette.BG, focused ? Palette.FOCUS : Palette.UNFOCUS, focused);
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
        for (int tid : tids) {
            String mark = tid == cur ? "* " : "  ";
            rows.add(mark + "t" + tid + "  \"" + engine.threadName(tid) + "\"  (" + count(tid) + ")");
        }
        threadSel = clamp(threadSel, 0, Math.max(0, rows.size() - 1));
        threadScroll = ScrollMath.adjustScroll(threadScroll, threadSel, ih, rows.size());
        drawRows(g, ix, iy, iw, ih, rows, threadSel, threadScroll, threadsHcol, true);
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
        for (int r = 0; r < ih; r++) {
            int idx = stackScroll + r;
            if (idx < 0 || idx >= rows.size()) {
                continue;
            }
            boolean s = idx == stackSel;
            boolean recorded = frames.get(frames.size() - 1 - idx).recorded();
            put(g, ix, iy + r, HScroll.clip(rows.get(idx), stackHcol, iw),
                    s ? Palette.SELECTION_FG : (recorded ? Palette.FG : Palette.DIM),
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

    private void renderStatus(final TextGraphics g, final int cols, final int row, final RecordInfo ri) {
        String pos = positionText(ri);
        String line = status.isEmpty() ? pos : status + "   —   " + pos;
        put(g, 0, row, clipPad(line, cols), Palette.FG, Palette.BG, false);
    }

    /**
     * The focus-sensitive position part of the status row (§14.4/§15.6): the selected instance's
     * full class + id while the {@code /} dialog is in instances mode, the selected Locals row's
     * full type while Locals is focused, the selected stack frame's fully-qualified
     * {@code Class.method} while Stack is focused (grey §14.5 frames included), and the compact
     * record info everywhere else (or as fallback when the focused window has no selection).
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
        }
        return "seq " + ri.seq() + DOT + "t" + ri.tid() + DOT + ri.siteText();
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
        String base = "F7 step  F8 over  ⇧/F9 F10 rev  e/E exit-frame  ^R run  o origin  "
                + "/ calls  . obj  F file  c/s/t/l/r focus  q quit";
        String ctx = switch (focus) {
            case SOURCE -> browseMode
                    ? "↑↓ cursor  v var-history  <> hscroll  o return  g/G/Home/End"
                    : "↑↓ cursor  v var-history  ←→/<> hscroll  g/G/Home/End";
            case THREADS -> "↑↓ select  ↵ switch  ←→/<> hscroll";
            case STACK -> "↑↓ select frame  ←→/<> hscroll";
            case LOCALS -> "↑↓ move  →/↵ expand  ← collapse  <> hscroll";
            case RECORDS -> "↑↓ select  ↵ jump  ←→/<> hscroll";
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
        for (int r = 0; r < ih; r++) {
            int idx = scroll + r;
            if (idx < 0 || idx >= rows.size()) {
                continue;
            }
            boolean s = showSel && idx == sel;
            put(g, ix, iy + r, HScroll.clip(rows.get(idx), hcol, iw),
                    s ? Palette.SELECTION_FG : Palette.FG, s ? Palette.SELECTION_BG : Palette.BG, false);
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
            case FILE -> "Open source file [F]";
        };
        drawBox(g, new Layout.Rect(x, y, w, h), title, Palette.DIALOG_BG, Palette.FOCUS, true);

        int ix = x + 1;
        int iy = y + 1;
        int iw = w - 2;
        String prompt = switch (dialog.kind) {
            case METHOD -> "regex: ";
            case FILE -> "file:  ";
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
            case FILE -> "class[:line] | path.java[:line]   ↵ open  Esc close";
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

        if (dialog.kind == DialogKind.FILE) {
            put(g, ix, listTop, clip("Opens a file in browse mode; press o to return to execution.", iw),
                    Palette.DIM, Palette.DIALOG_BG, false);
            return;
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

    /** The §13.5 variable-history overlay, drawn as a centered modal like {@link #drawDialog}. */
    private void drawVarHistory(final TextGraphics g, final int cols, final int rows) {
        int w = clamp(cols - 6, 24, 74);
        int h = clamp(rows - 6, 8, 20);
        int x = Math.max(0, (cols - w) / 2);
        int y = Math.max(0, (rows - h) / 2);
        g.setForegroundColor(Palette.FG);
        g.setBackgroundColor(Palette.DIALOG_BG);
        g.disableModifiers(SGR.BOLD);
        g.fillRectangle(new TerminalPosition(x, y), new TerminalSize(w, h), ' ');
        drawBox(g, new Layout.Rect(x, y, w, h), "Variable history [v]", Palette.DIALOG_BG, Palette.FOCUS, true);
        int ix = x + 1;
        int iy = y + 1;
        int iw = w - 2;
        int hintRow = y + h - 2;
        put(g, ix, hintRow, clip("↑↓ select  ↵ jump  <> hscroll  Esc close", iw),
                Palette.DIM, Palette.DIALOG_BG, false);
        int listBottom = hintRow;
        if (!varHist.note.isEmpty()) {
            listBottom = hintRow - 1;
            put(g, ix, listBottom, clip(varHist.note, iw), Palette.DIM, Palette.DIALOG_BG, false);
        }
        int listH = listBottom - iy;
        varHist.sel = clamp(varHist.sel, 0, Math.max(0, varHist.rows.size() - 1));
        varHist.scroll = ScrollMath.adjustScroll(varHist.scroll, varHist.sel, listH, varHist.rows.size());
        for (int r = 0; r < listH; r++) {
            int idx = varHist.scroll + r;
            if (idx >= varHist.rows.size()) {
                break;
            }
            boolean s = idx == varHist.sel;
            put(g, ix, iy + r, HScroll.clip(varHist.rows.get(idx), varHist.hcol, iw),
                    s ? Palette.SELECTION_FG : Palette.FG, s ? Palette.SELECTION_BG : Palette.DIALOG_BG, false);
        }
    }

    private void drawHelpOverlay(final TextGraphics g, final int cols, final int rows) {
        String[] lines = {
            "Hindsight Viewer — keys",
            "",
            "F7 / Shift+F7 (F9)    step into / reverse step",
            "F8 / Shift+F8 (F10)   step over / reverse step over",
            "e / E                 exit current frame: forward / backward",
            "Ctrl+R / Alt+R        run to current line: forward / backward",
            "o                     origin — return Source to the execution line",
            "c t s l r             focus Source Code/Threads/Stack/Locals/Records",
            "Tab / Shift+Tab       cycle focus forward / backward",
            "Arrows PgUp PgDn      navigate the focused window",
            "< / >                 horizontal-scroll the focused window",
            "^F / ^B  Home End      page / jump to ends     g / G  top / bottom",
            "v                     variable history for the Source cursor line",
            "/  .  F               browse calls/instances / find object / open file",
            "Tab in /              toggle calls ↔ instances;  ↑↓ enters the result",
            "                      list, / returns to the query, f = first mention",
            "?  F1                 toggle this help",
            "q  Ctrl+C             quit (q asks to confirm)",
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

    // ---- key dispatch ----

    private void handleKey(final KeyStroke k) {
        try {
            TerminalSize sz = screen.getTerminalSize();
            if (sz.getColumns() < MIN_COLS || sz.getRows() < MIN_ROWS) {
                // No room for the confirm box (§12.4) — quit immediately.
                if (isCtrlC(k) || isChar(k, 'q')) {
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
                } else if (isChar(k, 'q')) {
                    helpOverlay = false;
                    quitConfirm = true;
                }
                return;
            }
            if (dialog != null) {
                handleDialog(k);
                return;
            }
            if (varHist != null) {
                handleVarHistory(k);
                return;
            }
            if (isCtrlC(k)) {
                running = false;
                return;
            }
            if (isChar(k, 'q')) {
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
        // Stepping via function keys.
        switch (t) {
            case F7 -> {
                doStep(k.isShiftDown() ? engine.reverseStep() : engine.stepInto());
                return true;
            }
            case F8 -> {
                doStep(k.isShiftDown() ? engine.reverseStepOver() : engine.stepOver());
                return true;
            }
            case F9 -> {
                doStep(engine.reverseStep());
                return true;
            }
            case F10 -> {
                doStep(engine.reverseStepOver());
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
        // Run-to-line (Ctrl+R forward, Ctrl+Shift+R or Alt+R backward).
        if (isChar(k, 'r') && k.isCtrlDown()) {
            doStep(engine.runToCurrentLine(!k.isShiftDown()));
            return true;
        }
        if (isChar(k, 'r') && k.isAltDown()) {
            doStep(engine.runToCurrentLine(false));
            return true;
        }
        // Exit current frame (§11.6). Check E first so a shifted 'e' is not eaten by 'e'.
        if (isChar(k, 'E') || (isChar(k, 'e') && k.isShiftDown())) {
            doStep(engine.exitFrameBackward());
            return true;
        }
        if (isChar(k, 'e')) {
            doStep(engine.exitFrameForward());
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
            openDialog(DialogKind.FILE);
            return true;
        }
        // Focus letters (unmodified only, so Ctrl/Alt+letter reach navigation). §11.3 swaps c/s.
        if (t == KeyType.Character && !k.isCtrlDown() && !k.isAltDown()) {
            Character ch = k.getCharacter();
            if (ch != null) {
                switch (ch) {
                    case 'c' -> {
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
        if (engine.selectFrame(frameIndex)) {
            stackSel = row;
            followPending = true;
            srcHcol = 0;
            localsHcol = 0;
            srcCursorPending = true; // §13.4: cursor to the selected frame's line
        }
    }

    private void navTree(final KeyStroke k, final TreeView tree, final int viewH) {
        KeyType t = k.getKeyType();
        Character ch = k.getCharacter();
        int page = Math.max(1, viewH - 1);
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

    /** Keys of the transient {@code .}/{@code F} dialogs (§9/§11.8) — unchanged by §15.2. */
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
     * LINE (the §11.7 jump pattern), one history entry — and keep the dialog open; the expanded
     * trees re-resolve against the new seq on the next draw. If the engine has no first-mention
     * record (defensive), report and stay put.
     */
    private void jumpToFirstMention() {
        InstanceBrowse.Row row = selectedInstanceRow();
        if (row == null) {
            return;
        }
        Instance c = engine.firstMentionOf(row.objectId());
        if (c == null) {
            status = "no first mention recorded for " + row.display();
            return;
        }
        engine.seek(c.tid(), c.index());
        engine.stepInto();
        afterMove();
        appendHistory();
        status = "jumped to first mention of " + row.display() + " (seq " + c.seq() + ")";
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
            case FILE -> {
                dialog.rows = List.of();
                dialog.targets = List.of();
                dialog.note = "";
            }
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
        dialog.note = "";
        if (q.isEmpty()) {
            dialog.rows = rows;
            dialog.targets = targets;
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
                    if (rows.size() >= RESULT_CAP) {
                        dialog.note = "showing first " + RESULT_CAP;
                        break outer;
                    }
                }
            }
        }
        dialog.rows = rows;
        dialog.targets = targets;
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
        if (dialog.kind == DialogKind.FILE) {
            openFile();
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
        dialog = null;
        int tid = (int) target[0];
        long index = target[1];
        long seq = target[2];
        if (kind == DialogKind.METHOD) {
            jumpToCall(tid, index);
        } else {
            seekToSeq(tid, seq);
            afterMove();
            appendHistory();
        }
        status = "jumped to seq " + engine.currentSeq() + " (t" + engine.currentTid() + ")";
    }

    /**
     * Jumps to a method call (§11.7): seek to its {@code METHOD_ENTER} record, then advance to the
     * nearest following LINE so the cursor sits on a line; appends exactly one history entry.
     */
    private void jumpToCall(final int tid, final long index) {
        engine.seek(tid, index);
        engine.stepInto();
        afterMove();
        appendHistory();
    }

    // ---- variable history (§13.5) ----

    /**
     * The {@code v} key (Source focused): parse the cursor line of the shown file for field
     * declarations and open the history of the first candidate with recorded writes.
     */
    private void varHistory() {
        List<String> lines;
        String fileName;
        if (browseMode) {
            lines = browseLines;
            fileName = browseName;
        } else {
            List<Frame> frames = engine.stack();
            int sf = engine.selectedFrame();
            Frame frame = sf >= 0 && sf < frames.size() ? frames.get(sf) : null;
            fileName = frame != null ? frame.fileName() : null;
            lines = frame != null
                    ? sourcePath.lines(frame.classBinary(), fileName).orElse(null)
                    : null;
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
        for (String name : names) {
            // Ask for one extra hit so an exactly-full window is distinguishable from a capped one.
            List<FieldWriteHit> hits = engine.fieldWrites(fileName, name, VAR_HISTORY_CAP + 1);
            if (!hits.isEmpty()) {
                boolean capped = hits.size() > VAR_HISTORY_CAP;
                List<FieldWriteHit> shown = capped ? hits.subList(0, VAR_HISTORY_CAP) : hits;
                List<String> rows = new ArrayList<>(shown.size());
                for (FieldWriteHit hit : shown) {
                    rows.add(varHistoryRow(hit));
                }
                varHist = new VarHistoryWindow(
                        rows,
                        List.copyOf(shown),
                        capped
                                ? "showing first " + VAR_HISTORY_CAP + " of "
                                        + engine.countFieldWrites(fileName, name)
                                : "");
                return;
            }
        }
        status = "no recorded writes for '" + names.get(0) + "'";
    }

    /** {@code seq N · t<tid> · Owner#oid.field: old → new  (File:line)}; statics omit {@code #oid}. */
    private String varHistoryRow(final FieldWriteHit h) {
        String owner = h.ownerClass() == null ? "?" : Format.simpleName(h.ownerClass());
        String target = h.isStatic() ? owner : owner + "#" + h.ownerId();
        String file = h.fileName() != null ? h.fileName() : "?";
        return "seq " + h.seq() + DOT + "t" + h.tid() + DOT + target + "." + h.fieldName()
                + ": " + h.before() + " → " + h.after() + "  (" + file + ":" + h.line() + ")";
    }

    /** One key while the variable-history overlay is open — it consumes all keys, like a dialog. */
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
        if (t == KeyType.ArrowUp) {
            varHist.sel = Math.max(0, varHist.sel - 1);
        } else if (t == KeyType.ArrowDown) {
            varHist.sel = Math.min(last, varHist.sel + 1);
        } else if (isChar(k, '<')) {
            varHist.hcol = Math.max(0, varHist.hcol - HSTEP);
        } else if (isChar(k, '>')) {
            varHist.hcol += HSTEP;
        } else if (t == KeyType.Enter) {
            jumpToWrite();
        }
    }

    /**
     * Jump to a field write (§13.5): seek to the {@code FIELD_WRITE} record, then position on the
     * nearest LINE at/before it (the write belongs to that line); fall forward only if no LINE
     * precedes. One history entry.
     */
    private void jumpToWrite() {
        FieldWriteHit hit = varHist.hits.get(clamp(varHist.sel, 0, varHist.hits.size() - 1));
        varHist = null;
        engine.seek(hit.tid(), hit.index());
        if (!engine.reverseStep().moved()) {
            engine.stepInto();
        }
        afterMove();
        appendHistory();
        status = "jumped to seq " + engine.currentSeq() + " (t" + engine.currentTid() + ")";
    }

    private void openFile() {
        String input = dialog.query.toString();
        dialog = null;
        OpenFileTarget.Target target = OpenFileTarget.parse(input);
        if (target == null) {
            status = "open file: nothing to open";
            return;
        }
        Optional<Path> resolved = target.path()
                ? resolvePathTarget(target.rawPath())
                : sourcePath.resolveClass(target.classBinary(), target.fileName());
        if (resolved.isEmpty()) {
            status = "file not found: " + input;
            return;
        }
        Path file = resolved.get();
        Optional<List<String>> lines = sourcePath.linesOfFile(file);
        if (lines.isEmpty()) {
            status = "cannot read: " + file;
            return;
        }
        enterBrowse(file, lines.get(), target.line());
    }

    /** Resolves a {@code .java} path target (§11.8): absolute first, then relative to each root. */
    private Optional<Path> resolvePathTarget(final String raw) {
        Path p = Path.of(raw);
        if (p.isAbsolute() && sourcePath.linesOfFile(p).isPresent()) {
            return Optional.of(p);
        }
        return sourcePath.resolveClass(null, raw);
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
     * Positions the cursor on the nearest LINE at/after {@code targetSeq} in thread {@code tid} by
     * stepping (used by the object-usage jump). Capped so a huge gap cannot hang the UI.
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

    private void afterMove() {
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
