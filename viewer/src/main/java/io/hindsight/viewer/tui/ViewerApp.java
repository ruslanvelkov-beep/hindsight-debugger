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
import io.hindsight.viewer.engine.Frame;
import io.hindsight.viewer.engine.Local;
import io.hindsight.viewer.engine.ObjectNode;
import io.hindsight.viewer.engine.RecordInfo;
import io.hindsight.viewer.engine.StepResult;
import io.hindsight.viewer.engine.Value;
import io.hindsight.viewer.engine.WatchResult;
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
 * The lanterna terminal UI (VIEWER.md §5–§11): the one class that touches a
 * terminal. It owns the {@link Screen}, drives the render/read/dispatch loop, keeps
 * all per-window view state (scroll offsets, selections, expansion, the Watches
 * editor, the browse-mode source target, the navigation history trail and the
 * search dialogs), and translates key strokes into {@link Engine} navigation,
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
 * </ul>
 */
public final class ViewerApp {

    private static final int MIN_COLS = 80;
    private static final int MIN_ROWS = 24;
    private static final int TAB_WIDTH = 4;
    private static final int HSTEP = 4;
    private static final int RESULT_CAP = 200;
    private static final long SEEK_CAP = 5_000_000L;
    private static final String DOT = " · ";

    /** The six focusable windows, in Tab-cycle order (VIEWER.md §5, §11.3/§11.5). */
    private enum Win {
        SOURCE, THREADS, STACK, LOCALS, WATCHES, RECORDS
    }

    /** Which modal dialog is open (§9, §11.7 regex method browse, §11.8 open file). */
    private enum DialogKind {
        METHOD, OBJECT, FILE
    }

    /** Live state of an open modal dialog: a query, its result rows + jump targets, and a note. */
    private static final class SearchDialog {
        final DialogKind kind;
        final StringBuilder query = new StringBuilder();
        List<String> rows = List.of();
        List<long[]> targets = List.of();
        String note = "";
        int sel;

        SearchDialog(final DialogKind kind) {
            this.kind = kind;
        }
    }

    private final Screen screen;
    private final Engine engine;
    private final Recording recording;
    private final SourcePath sourcePath;
    private final JavaHighlighter highlighter = new JavaHighlighter();
    private final List<Integer> tids;

    private final TreeView localsTree;
    private final TreeView watchesTree;
    private final List<String> watches = new ArrayList<>();
    private final Map<String, List<List<JavaHighlighter.Span>>> highlightCache = new HashMap<>();
    private final HistoryTrail history = new HistoryTrail();

    private Win focus = Win.SOURCE;
    private boolean running = true;
    private String status = "";
    private boolean helpOverlay;
    private SearchDialog dialog;
    private TerminalPosition cursorPos;

    // Source window state.
    private int srcTop = 1;
    private int srcHcol;
    private String shownFileKey;
    private boolean followPending = true;
    private boolean recenterSource;
    private int srcViewH = 1;
    private int srcFileLines;
    private boolean srcResolved;

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
    private int watchesHcol;
    private int watchesViewH = 1;
    private int recordsSel;
    private int recordsScroll;
    private int recordsHcol;
    private int recordsViewH = 1;

    // Watches inline editor.
    private boolean watchEditing;
    private final StringBuilder watchBuffer = new StringBuilder();

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
        this.watchesTree = new TreeView(engine::children);
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
        renderWatches(g, r.get(3));
        renderRecords(g, r.get(4));
        renderStatus(g, cols, layout.statusRow(), ri);
        renderHelp(g, cols, layout.helpRow());
        if (helpOverlay) {
            drawHelpOverlay(g, cols, rows);
        }
        if (dialog != null) {
            drawDialog(g, cols, rows);
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
        String title = browseMode ? "Source Code [c] — " + browseName : "Source Code [c]";
        drawBox(g, rect, title, Palette.BG, focused ? Palette.FOCUS : Palette.UNFOCUS, focused);
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
        renderCodeLines(g, ix, iy, iw, ih, lines, spans, srcTop, srcHcol, line, sf == frames.size() - 1);
    }

    private void renderBrowse(final TextGraphics g, final int ix, final int iy, final int iw, final int ih) {
        srcResolved = true;
        srcFileLines = browseLines.size();
        int line = Math.min(Math.max(browseLine, 1), Math.max(1, srcFileLines));
        if (browseCenterPending) {
            srcTop = ScrollMath.center(line, ih, srcFileLines);
            srcHcol = 0;
            browseCenterPending = false;
        } else {
            srcTop = ScrollMath.clampTop(srcTop, srcFileLines, ih);
        }
        if (srcFileLines == 0) {
            put(g, ix, iy, clip("<empty file>", iw), Palette.DIM, Palette.BG, false);
            return;
        }
        List<List<JavaHighlighter.Span>> spans =
                highlightCache.computeIfAbsent(browseKey, k -> highlighter.highlight(browseLines));
        // Browse marks the requested line with the dim (frame) highlight, never the exec highlight.
        renderCodeLines(g, ix, iy, iw, ih, browseLines, spans, srcTop, srcHcol, line, false);
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
            final boolean execHighlight) {
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
            TextColor lineBg = isHl ? (isExec ? Palette.EXEC_LINE_BG : Palette.FRAME_LINE_BG) : Palette.BG;

            put(g, ix, y, String.format("%" + numW + "d ", lineNo), Palette.DIM, lineBg, false);
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
        threadScroll = adjustScroll(threadScroll, threadSel, ih, rows.size());
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
        stackScroll = adjustScroll(stackScroll, stackSel, ih, rows.size());
        drawRows(g, ix, iy, iw, ih, rows, stackSel, stackScroll, stackHcol, true);
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
        List<Local> locals = engine.locals(engine.selectedFrame());
        List<ObjectNode> roots = new ArrayList<>();
        for (Local l : locals) {
            roots.add(ObjectNode.of(l.name() + " = " + l.value().display(), l.value()));
        }
        localsTree.setRoots(roots);
        drawTree(g, ix, iy, iw, ih, localsTree, localsHcol);
    }

    private void renderWatches(final TextGraphics g, final Layout.Rect rect) {
        boolean focused = focus == Win.WATCHES;
        drawBox(g, rect, "Watches [w]", Palette.BG, focused ? Palette.FOCUS : Palette.UNFOCUS, focused);
        int ix = rect.col() + 1;
        int iy = rect.row() + 1;
        int iw = rect.width() - 2;
        int ih = rect.height() - 2;
        if (iw <= 0 || ih <= 0) {
            return;
        }
        List<ObjectNode> roots = new ArrayList<>();
        for (String expr : watches) {
            WatchResult wr = engine.resolveWatch(expr);
            if (wr.error() != null) {
                roots.add(new ObjectNode(expr + " = <" + wr.error() + ">", new Value.Absent(), false));
            } else {
                ObjectNode rr = wr.root();
                roots.add(new ObjectNode(expr + " = " + rr.value().display(), rr.value(), rr.expandable()));
            }
        }
        watchesTree.setRoots(roots);

        int treeH = watchEditing ? ih - 1 : ih;
        watchesViewH = Math.max(1, treeH);
        if (watches.isEmpty() && !watchEditing) {
            put(g, ix, iy, clip("(no watches — press a to add)", iw), Palette.DIM, Palette.BG, false);
        } else if (treeH > 0) {
            drawTree(g, ix, iy, iw, treeH, watchesTree, watchesHcol);
        }
        if (watchEditing) {
            int ey = iy + ih - 1;
            put(g, ix, ey, clipPad("+ " + watchBuffer, iw), Palette.SELECTION_FG, Palette.SELECTION_BG, false);
            cursorPos = new TerminalPosition(Math.min(ix + iw - 1, ix + 2 + watchBuffer.length()), ey);
        }
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
        recordsScroll = adjustScroll(recordsScroll, recordsSel, ih, rows.size());
        drawRows(g, ix, iy, iw, ih, rows, recordsSel, recordsScroll, recordsHcol, true);
    }

    private void renderStatus(final TextGraphics g, final int cols, final int row, final RecordInfo ri) {
        String pos = "seq " + ri.seq() + DOT + "t" + ri.tid() + DOT + ri.siteText();
        String line = status.isEmpty() ? pos : status + "   —   " + pos;
        put(g, 0, row, clipPad(line, cols), Palette.FG, Palette.BG, false);
    }

    private void renderHelp(final TextGraphics g, final int cols, final int row) {
        String base = "F7 step  F8 over  ⇧/F9 F10 rev  e/E exit-frame  ^R run  o origin  "
                + "/ calls  . obj  F file  c/s/t/l/w/r focus  q quit";
        String ctx = switch (focus) {
            case SOURCE -> browseMode
                    ? "↑↓ scroll  <> hscroll  o return  g/G/Home/End"
                    : "↑↓ scroll  ←→/<> hscroll  g/G/Home/End";
            case THREADS -> "↑↓ select  ↵ switch  ←→/<> hscroll";
            case STACK -> "↑↓ select frame  ←→/<> hscroll";
            case LOCALS -> "↑↓ move  →/↵ expand  ← collapse  <> hscroll";
            case WATCHES -> "a add  d del  ↑↓ move  →/← expand  <> hscroll";
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
            case METHOD -> "Browse method calls [/]";
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
        cursorPos = new TerminalPosition(Math.min(x + w - 2, ix + prompt.length() + dialog.query.length()), iy);

        int hintRow = y + h - 2;
        String hint = dialog.kind == DialogKind.FILE
                ? "class[:line] | path.java[:line]   ↵ open  Esc close"
                : "↑↓ select  ↵ jump  Esc close";
        put(g, ix, hintRow, clip(hint, iw), Palette.DIM, Palette.DIALOG_BG, false);

        int listBottom = hintRow;
        if (!dialog.note.isEmpty()) {
            listBottom = hintRow - 1;
            put(g, ix, listBottom, clip(dialog.note, iw), Palette.DIM, Palette.DIALOG_BG, false);
        }
        int listTop = iy + 2;
        int listH = listBottom - listTop;

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
        dialog.sel = clamp(dialog.sel, 0, dialog.rows.size() - 1);
        int scroll = adjustScroll(0, dialog.sel, listH, dialog.rows.size());
        for (int r = 0; r < listH; r++) {
            int idx = scroll + r;
            if (idx >= dialog.rows.size()) {
                break;
            }
            boolean s = idx == dialog.sel;
            put(g, ix, listTop + r, clipPad(dialog.rows.get(idx), iw),
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
            "c t s l w r           focus Source Code/Threads/Stack/Locals/Watches/Records",
            "Tab / Shift+Tab       cycle focus forward / backward",
            "Arrows PgUp PgDn      navigate the focused window",
            "< / >                 horizontal-scroll the focused window",
            "^F / ^B  Home End      page / jump to ends     g / G  top / bottom",
            "/  .  F               browse method calls / find object / open file",
            "a / d  (Watches)      add / delete a watch",
            "?  F1                 toggle this help",
            "q  Ctrl+C             quit",
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

    // ---- key dispatch ----

    private void handleKey(final KeyStroke k) {
        try {
            TerminalSize sz = screen.getTerminalSize();
            if (sz.getColumns() < MIN_COLS || sz.getRows() < MIN_ROWS) {
                if (isQuit(k)) {
                    running = false;
                }
                return;
            }
            if (watchEditing) {
                handleWatchEditor(k);
                return;
            }
            if (helpOverlay) {
                KeyType t = k.getKeyType();
                if (t == KeyType.F1 || t == KeyType.Escape || isChar(k, '?')) {
                    helpOverlay = false;
                } else if (isQuit(k)) {
                    running = false;
                }
                return;
            }
            if (dialog != null) {
                handleDialog(k);
                return;
            }
            if (isQuit(k)) {
                running = false;
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
                    case 'w' -> {
                        focus = Win.WATCHES;
                        return true;
                    }
                    case 'r' -> {
                        focus = Win.RECORDS;
                        return true;
                    }
                    default -> {
                        // not a focus letter; navigation may still want it (g/G/a/d)
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
            case WATCHES -> navWatches(k);
            case RECORDS -> navRecords(k);
        }
    }

    private void navSource(final KeyStroke k) {
        KeyType t = k.getKeyType();
        Character ch = k.getCharacter();
        int page = Math.max(1, srcViewH - 1);
        if (t == KeyType.ArrowUp) {
            srcScroll(-1);
        } else if (t == KeyType.ArrowDown) {
            srcScroll(1);
        } else if (t == KeyType.ArrowLeft) {
            srcHcol = Math.max(0, srcHcol - HSTEP);
        } else if (t == KeyType.ArrowRight) {
            if (srcResolved) {
                srcHcol += HSTEP;
            }
        } else if (t == KeyType.PageUp || (k.isCtrlDown() && ch != null && ch == 'b')) {
            srcScroll(-page);
        } else if (t == KeyType.PageDown || (k.isCtrlDown() && ch != null && ch == 'f')) {
            srcScroll(page);
        } else if (t == KeyType.Home) {
            srcTop = 1;
            srcHcol = 0;
        } else if (t == KeyType.End) {
            srcTop = ScrollMath.maxTop(srcFileLines, srcViewH);
        } else if (ch != null && ch == 'g') {
            srcTop = 1;
        } else if (ch != null && ch == 'G') {
            srcTop = ScrollMath.maxTop(srcFileLines, srcViewH);
        }
    }

    private void srcScroll(final int delta) {
        srcTop = ScrollMath.clampTop(srcTop + delta, srcFileLines, srcViewH);
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

    private void navWatches(final KeyStroke k) {
        if (isChar(k, 'a') || k.getKeyType() == KeyType.Insert) {
            watchEditing = true;
            watchBuffer.setLength(0);
            return;
        }
        if (isChar(k, 'd') || k.getKeyType() == KeyType.Delete) {
            int root = watchesTree.selectedRootIndex();
            if (root >= 0 && root < watches.size()) {
                String removed = watches.remove(root);
                watchesTree.clearExpansion();
                watchesHcol = 0;
                status = "removed watch: " + removed;
            }
            return;
        }
        navTree(k, watchesTree, watchesViewH);
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

    private void handleWatchEditor(final KeyStroke k) {
        KeyType t = k.getKeyType();
        if (t == KeyType.Escape) {
            watchEditing = false;
            watchBuffer.setLength(0);
            status = "watch cancelled";
        } else if (t == KeyType.Enter) {
            String expr = watchBuffer.toString().trim();
            watchEditing = false;
            watchBuffer.setLength(0);
            if (!expr.isEmpty()) {
                watches.add(expr);
                watchesTree.clearExpansion();
                watchesHcol = 0;
                status = "added watch: " + expr;
            }
        } else if (t == KeyType.Backspace) {
            if (watchBuffer.length() > 0) {
                watchBuffer.deleteCharAt(watchBuffer.length() - 1);
            }
        } else if (t == KeyType.Character) {
            Character ch = k.getCharacter();
            if (ch != null && ch >= ' ') {
                watchBuffer.append(ch.charValue());
            }
        }
    }

    // ---- dialogs (§9, §11.7, §11.8) ----

    private void openDialog(final DialogKind kind) {
        dialog = new SearchDialog(kind);
        recomputeDialog();
    }

    private void handleDialog(final KeyStroke k) {
        KeyType t = k.getKeyType();
        if (t == KeyType.Escape) {
            dialog = null;
        } else if (t == KeyType.Enter) {
            jumpDialog();
        } else if (t == KeyType.ArrowUp) {
            dialog.sel = Math.max(0, dialog.sel - 1);
        } else if (t == KeyType.ArrowDown) {
            dialog.sel = Math.min(Math.max(0, dialog.rows.size() - 1), dialog.sel + 1);
        } else if (t == KeyType.Backspace) {
            if (dialog.query.length() > 0) {
                dialog.query.deleteCharAt(dialog.query.length() - 1);
                dialog.sel = 0;
                recomputeDialog();
            }
        } else if (t == KeyType.Character) {
            Character ch = k.getCharacter();
            if (ch != null && ch >= ' ') {
                dialog.query.append(ch.charValue());
                dialog.sel = 0;
                recomputeDialog();
            }
        }
    }

    private void recomputeDialog() {
        switch (dialog.kind) {
            case METHOD -> recomputeMethodResults();
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
            long shown = rows.size();
            dialog.note = res.totalMatches() > shown
                    ? "showing first " + shown + " of " + res.totalMatches()
                    : "";
        } catch (PatternSyntaxException e) {
            dialog.rows = List.of();
            dialog.targets = List.of();
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
            case WATCHES -> watchesHcol = Math.max(0, watchesHcol + delta);
            case RECORDS -> recordsHcol = Math.max(0, recordsHcol + delta);
        }
    }

    private void cycleFocus(final int dir) {
        Win[] order = Win.values();
        int idx = (focus.ordinal() + dir + order.length) % order.length;
        focus = order[idx];
    }

    // ---- small helpers ----

    private boolean isQuit(final KeyStroke k) {
        return isChar(k, 'q') || (k.isCtrlDown() && isChar(k, 'c'));
    }

    private static boolean isChar(final KeyStroke k, final char c) {
        return k.getKeyType() == KeyType.Character && k.getCharacter() != null && k.getCharacter() == c;
    }

    private static int clamp(final int v, final int lo, final int hi) {
        return Math.min(Math.max(v, lo), hi);
    }

    private static int adjustScroll(final int scroll, final int sel, final int height, final int total) {
        if (height <= 0) {
            return 0;
        }
        int s = scroll;
        if (sel < s) {
            s = sel;
        } else if (sel >= s + height) {
            s = sel - height + 1;
        }
        return Math.min(Math.max(s, 0), Math.max(0, total - height));
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
