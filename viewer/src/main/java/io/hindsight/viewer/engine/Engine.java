// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

import io.hindsight.reader.Format;
import io.hindsight.reader.Rec;
import io.hindsight.reader.Recording;
import io.hindsight.reader.Site;
import io.hindsight.reader.ValueRenderer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The reconstruction engine (VIEWER.md §2): a headless, terminal-free replay of a
 * Hindsight recording that lets a caller move a cursor forward and backward
 * through one thread's execution and, at every position, reconstruct the call
 * stack, the live locals, arbitrary object trees and watch expressions — all
 * as-of that point on the timeline.
 *
 * <p>All heavy analysis happens once at {@link #open}: a bracket-matching pass per
 * thread builds a {@link ThreadIndex} (depths, frame links, exec/line/time search
 * structures, §2.1/§17.1) and a global object-event index (per-object ordered
 * SNAPSHOT/FIELD_WRITE records plus a static-write index for watches). Navigation
 * and reconstruction are then cheap lookups against those indices. Because the
 * public navigation/reconstruction API is not declared to throw, unexpected
 * {@link IOException} from the lazily-mapped reader is wrapped in
 * {@link UncheckedIOException}; a well-formed recording never triggers it.
 *
 * <p>The cursor rests only on <em>execution</em> records — the §17.1 exec set of
 * {@code LINE}, {@code METHOD_ENTER} and {@code METHOD_EXIT} — and every stepping
 * command lands on one; metadata records (first-seen, snapshots, thread-start,
 * time-sync, stack captures) and value records (ARG, LOCAL_WRITE, FIELD_WRITE,
 * BRANCH) are never step landings. On a {@code METHOD_ENTER} the entered frame is
 * on the stack with its seeded arguments as locals; on a {@code METHOD_EXIT} the
 * exiting frame is still on the stack, reconstructed as of the exit.
 *
 * <p>The engine has no dependency on the source, highlight or TUI packages and no
 * terminal imports, so it is fully unit-testable against synthetic recordings.
 */
public final class Engine implements AutoCloseable {

    static final String NO_NEXT_STEP = "no next step in this thread";
    static final String NO_EARLIER_STEP = "no earlier step in this thread";
    static final String NO_NEXT_APPEARANCE = "no next appearance of this line.";
    static final String NO_PREV_APPEARANCE = "no previous appearance of this line.";
    static final String ALREADY_HERE = "already here";
    static final String ALREADY_AT_FRAME_RETURN = "already at frame return";
    static final String ALREADY_AT_FRAME_ENTRY = "already at frame entry";
    static final String NO_FURTHER_LINE_TRACE = "no further line-trace record in this thread";
    static final String UNSUPPORTED_WATCH =
            "unsupported expression (v1 accepts #id or Class.field.path)";

    /** One SNAPSHOT or FIELD_WRITE touching an object id, ordered globally by {@code seq}. */
    private record ObjEvent(long seq, int tid, long index, boolean snapshot) {}

    /** One static FIELD_WRITE, ordered by {@code seq}, for watch resolution. */
    private record WriteRef(long seq, int tid, long index) {}

    /** A stack frame plus the record index whose state its locals reflect (§2.4). */
    private record FrameCtx(Frame frame, long asOfIndex) {}

    /**
     * Minimal growable {@code long[]} builder for index construction. A thread can hold
     * tens of millions of records (ThreadIndex's compactness contract), so the exec/line/
     * time-sync positions must accumulate unboxed — an {@code ArrayList<Long>} at that
     * scale costs ~4× the memory and overflows a default test heap.
     */
    private static final class LongList {
        private long[] a = new long[16];
        private int n;

        void add(final long v) {
            if (n == a.length) {
                a = Arrays.copyOf(a, a.length * 2);
            }
            a[n++] = v;
        }

        long[] toArray() {
            return Arrays.copyOf(a, n);
        }
    }

    /** Reconstructed object state as-of a seq: either named fields or an unfolded container. */
    private static final class Reconstructed {
        final boolean present;
        final int form;
        final LinkedHashMap<String, Value> fields;
        final List<Value> keys;
        final List<Value> values;
        final List<Value> elements;
        final long totalSize;

        private Reconstructed(
                final boolean present,
                final int form,
                final LinkedHashMap<String, Value> fields,
                final List<Value> keys,
                final List<Value> values,
                final List<Value> elements,
                final long totalSize) {
            this.present = present;
            this.form = form;
            this.fields = fields;
            this.keys = keys;
            this.values = values;
            this.elements = elements;
            this.totalSize = totalSize;
        }

        static Reconstructed absent() {
            return new Reconstructed(false, Format.FORM_FIELDS, new LinkedHashMap<>(),
                    List.of(), List.of(), List.of(), 0);
        }

        static Reconstructed fields(final LinkedHashMap<String, Value> f) {
            return new Reconstructed(true, Format.FORM_FIELDS, f, List.of(), List.of(), List.of(), f.size());
        }

        static Reconstructed container(
                final int form, final long totalSize, final List<Value> keys,
                final List<Value> values, final List<Value> elements) {
            return new Reconstructed(true, form, new LinkedHashMap<>(), keys, values, elements, totalSize);
        }
    }

    private final Recording recording;
    private final ValueRenderer renderer;
    private final int schemaVersion;
    private final Map<Integer, ThreadIndex> indices = new HashMap<>();
    private final Map<Long, List<ObjEvent>> objEvents = new HashMap<>();
    private final Map<String, List<WriteRef>> staticWrites = new HashMap<>();
    private final Set<String> staticOwners = new HashSet<>();
    private final Map<String, List<String>> ownersBySimpleName = new HashMap<>();
    private final long globalRecordCount;

    private int cursorTid;
    private long cursorIndex;
    private int selectedFrame;
    private List<FrameCtx> ctx = List.of();
    private List<Frame> publicFrames = List.of();

    // Lazily built once on first methodSites()/callsOf() call (§11.7): the distinct entered
    // METHOD sites and, per site id, its METHOD_ENTER occurrences ascending by seq.
    private List<MethodSite> methodSitesCache;
    private Map<Integer, List<Call>> callsBySite;

    // Lazily built once on first classSites()/instancesOf()/firstMentionOf()/fieldWrites() call
    // (§13.6, one shared full pass mirroring ensureMethodSites): per runtime class its
    // OBJECT_FIRST_SEEN instances ascending by seq, per object id its first-mention position,
    // and per field name its FIELD_WRITE positions (refs only — values render lazily at query
    // time, a recording can hold millions of field writes).
    private List<ClassSite> classSitesCache;
    private Map<String, List<Instance>> instancesByClass;
    private Map<Long, Instance> firstMentionById;
    private Map<String, List<WriteRef>> fieldWriteRefsByName;

    private Engine(final Recording recording) throws IOException {
        this.recording = recording;
        this.schemaVersion = recording.schemaVersion();
        this.renderer = new ValueRenderer(recording);
        long total = 0;
        for (int tid : recording.threadIds()) {
            indices.put(tid, buildIndex(tid));
            total += recording.recordCount(tid);
        }
        this.globalRecordCount = total;
        sortObjectIndices();
        buildStaticOwnerLookup();
        writeThreadKindCache();
        chooseInitialCursor();
    }

    /**
     * Writes the §18.4 {@code viewer-thread-kinds.tsv} cache into the recording directory once
     * the indices are built: one {@code tid<TAB>LINES|CALLS} row per thread, sorted by tid —
     * {@code LINES} iff the thread carries {@code LINE} records ({@link #threadHasLines}). The
     * file makes the classification available without replaying the recording; the live TUI
     * colours from the in-memory indices regardless. Best-effort by design: any
     * {@link IOException}/{@link SecurityException} is swallowed so a read-only recording
     * still opens, and an existing file with identical content is left untouched.
     */
    private void writeThreadKindCache() {
        try {
            StringBuilder sb = new StringBuilder();
            for (int tid : recording.threadIds()) { // threadIds() is ascending — rows sorted by tid
                sb.append(tid).append('\t').append(index(tid).hasLines() ? "LINES" : "CALLS").append('\n');
            }
            String content = sb.toString();
            Path file = recording.dir().resolve("viewer-thread-kinds.tsv");
            if (Files.exists(file) && content.equals(Files.readString(file))) {
                return; // present and consistent — do not rewrite (§18.4)
            }
            Files.writeString(file, content);
        } catch (IOException | SecurityException e) {
            // Best-effort (§18.4): the engine must still open read-only recordings.
        }
    }

    /**
     * Opens a recording directory and builds all indices eagerly (a few passes over
     * the mapped files — acceptable for an offline tool). No source dependencies.
     *
     * @throws IOException if the directory is unusable, or has no threads to position on
     */
    public static Engine open(final Path recordingDir) throws IOException {
        return new Engine(Recording.open(recordingDir));
    }

    // ---- index construction (§2.1) ----

    private ThreadIndex buildIndex(final int tid) throws IOException {
        int count = (int) recording.recordCount(tid);
        int[] depth = new int[count];
        int[] frameEnter = new int[count];
        int[] matchingExit = new int[count];
        int[] parentEnter = new int[count];
        int[] estack = new int[16];
        int top = -1;
        LongList execList = new LongList();
        LongList lineList = new LongList();
        LongList tsIdx = new LongList();
        LongList tsMs = new LongList();
        LongList capIdx = new LongList();
        String threadName = null;

        for (int i = 0; i < count; i++) {
            Rec r = recording.recordAt(tid, i);
            int type = r.type();
            switch (type) {
                case Format.METHOD_ENTER -> {
                    int parent = top >= 0 ? estack[top] : -1;
                    if (top + 1 >= estack.length) {
                        estack = Arrays.copyOf(estack, estack.length * 2);
                    }
                    estack[++top] = i;
                    depth[i] = top + 1;
                    frameEnter[i] = i;
                    matchingExit[i] = count - 1;
                    parentEnter[i] = parent;
                    execList.add((long) i);
                }
                case Format.METHOD_EXIT -> {
                    if (top >= 0) {
                        int e = estack[top];
                        matchingExit[e] = i;
                        depth[i] = top + 1;
                        frameEnter[i] = e;
                        top--;
                    } else {
                        depth[i] = 0;
                        frameEnter[i] = -1;
                    }
                    execList.add((long) i);
                }
                default -> {
                    depth[i] = top + 1;
                    frameEnter[i] = top >= 0 ? estack[top] : -1;
                    if (type == Format.LINE) {
                        execList.add((long) i);
                        lineList.add((long) i);
                    } else if (type == Format.THREAD_START && threadName == null) {
                        threadName = recording.symbolText(r.location());
                    } else if (type == Format.TIME_SYNC) {
                        tsIdx.add((long) i);
                        tsMs.add(r.after());
                    } else if (type == Format.STACK_SNAPSHOT) {
                        capIdx.add((long) i); // §14.5 capture positions, ascending
                    }
                }
            }
            indexObjectEvent(r, tid, i);
        }

        return new ThreadIndex(tid, count, depth, frameEnter, matchingExit, parentEnter,
                execList.toArray(), lineList.toArray(), tsIdx.toArray(), tsMs.toArray(),
                capIdx.toArray(), threadName);
    }

    private void indexObjectEvent(final Rec r, final int tid, final int index) {
        if (r.type() == Format.SNAPSHOT) {
            addObjEvent(r.target(), r.seq(), tid, index, true);
        } else if (r.type() == Format.FIELD_WRITE) {
            Site s = recording.site(r.location());
            if (s != null && s.isStatic()) {
                String owner = recording.symbolText(s.classSym());
                String field = recording.symbolText(s.memberSym());
                if (owner != null && field != null) {
                    staticWrites
                            .computeIfAbsent(owner + "#" + field, k -> new ArrayList<>())
                            .add(new WriteRef(r.seq(), tid, index));
                }
            } else if (r.target() != 0) {
                addObjEvent(r.target(), r.seq(), tid, index, false);
            }
        }
    }

    private void addObjEvent(final long id, final long seq, final int tid, final int index, final boolean snap) {
        objEvents.computeIfAbsent(id, k -> new ArrayList<>()).add(new ObjEvent(seq, tid, index, snap));
    }

    private void sortObjectIndices() {
        Comparator<ObjEvent> byOrder = Comparator
                .comparingLong(ObjEvent::seq)
                .thenComparingInt(ObjEvent::tid)
                .thenComparingLong(ObjEvent::index);
        for (List<ObjEvent> list : objEvents.values()) {
            list.sort(byOrder);
        }
        for (List<WriteRef> list : staticWrites.values()) {
            list.sort(Comparator.comparingLong(WriteRef::seq));
        }
    }

    private void buildStaticOwnerLookup() {
        for (String key : staticWrites.keySet()) {
            int hash = key.lastIndexOf('#');
            if (hash <= 0) {
                continue;
            }
            String owner = key.substring(0, hash);
            if (staticOwners.add(owner)) {
                ownersBySimpleName
                        .computeIfAbsent(Format.simpleName(owner), k -> new ArrayList<>())
                        .add(owner);
            }
        }
    }

    private void chooseInitialCursor() throws IOException {
        int bestTid = -1;
        long bestSeq = Long.MAX_VALUE;
        long bestIndex = 0;
        for (Map.Entry<Integer, ThreadIndex> e : indices.entrySet()) {
            ThreadIndex ti = e.getValue();
            if (ti.execIndices.length > 0) {
                long firstExec = ti.execIndices[0];
                long seq = recording.recordAt(ti.tid, firstExec).seq();
                if (seq < bestSeq) {
                    bestSeq = seq;
                    bestTid = ti.tid;
                    bestIndex = firstExec;
                }
            }
        }
        if (bestTid < 0) {
            // No exec records anywhere: fall back to the lowest tid with any records (§2.2).
            for (int tid : recording.threadIds()) {
                if (recording.recordCount(tid) > 0) {
                    bestTid = tid;
                    bestIndex = 0;
                    break;
                }
            }
        }
        if (bestTid < 0) {
            throw new IOException("recording has no records to position on");
        }
        cursorTid = bestTid;
        cursorIndex = bestIndex;
        recompute();
    }

    // ---- cursor accessors ----

    /** Dense thread id the cursor is currently in. */
    public int currentTid() {
        return cursorTid;
    }

    /** Record index of the cursor within its thread. */
    public long currentIndex() {
        return cursorIndex;
    }

    /** Global sequence number of the cursor record. */
    public long currentSeq() {
        return rec(cursorTid, cursorIndex).seq();
    }

    /** Immutable snapshot of the current view state (§2.2). */
    public Position position() {
        return new Position(cursorTid, cursorIndex, currentSeq(), selectedFrame, depthAtCursor());
    }

    private int depthAtCursor() {
        ThreadIndex ti = index(cursorTid);
        int ci = (int) cursorIndex;
        return ci >= 0 && ci < ti.depth.length ? ti.depth[ci] : 0;
    }

    // ---- navigation (§2.6, exec-record stepping §17.1) ----

    /**
     * Step to the next execution record — {@code LINE}, {@code METHOD_ENTER} or
     * {@code METHOD_EXIT} — in this thread, any frame (F7, §17.1). Descends into callees:
     * from a call-site line the next exec record is the callee's ENTER.
     */
    public StepResult stepInto() {
        ThreadIndex ti = index(cursorTid);
        int start = execForwardStart(ti);
        if (start < ti.execIndices.length) {
            return moveTo(cursorTid, ti.execIndices[start]);
        }
        return new StepResult(false, NO_NEXT_STEP);
    }

    /**
     * Step to the next execution record belonging to the current frame or an ancestor of it
     * (F8, §17.1): the next exec record at the current frame's depth or shallower — the
     * current/outer frames' LINEs, the current/ancestor frame's EXIT, and (past the current
     * frame's end) a SIBLING frame's ENTER, so the walk flows into the thread's next
     * top-level invocation instead of dead-ending at every frame boundary. Everything inside
     * callees (strictly deeper) is skipped; the current frame's own ENTER is never a landing.
     */
    public StepResult stepOver() {
        ThreadIndex ti = index(cursorTid);
        int own = ownFrameEnter(ti);
        int d = own >= 0 ? ti.depth[own] : 0;
        for (int t = execForwardStart(ti); t < ti.execIndices.length; t++) {
            long j = ti.execIndices[t];
            if (ti.depth[(int) j] <= d && j != own) {
                return moveTo(cursorTid, j);
            }
        }
        return new StepResult(false, NO_NEXT_STEP);
    }

    /** Step to the previous execution record in this thread, any frame (Shift-F7, §17.1). */
    public StepResult reverseStep() {
        ThreadIndex ti = index(cursorTid);
        int start = execBackwardStart(ti);
        if (start >= 0) {
            return moveTo(cursorTid, ti.execIndices[start]);
        }
        return new StepResult(false, NO_EARLIER_STEP);
    }

    /**
     * Step to the previous exec record at the current frame's depth or shallower (Shift-F8,
     * §17.1) — the mirror of {@link #stepOver}: earlier completed callees are skipped whole,
     * the current frame's own ENTER is skipped (from a frame's first line the landing is the
     * caller's call-site line), and before the frame began the landing is an earlier sibling
     * frame's EXIT.
     */
    public StepResult reverseStepOver() {
        ThreadIndex ti = index(cursorTid);
        int own = ownFrameEnter(ti);
        int d = own >= 0 ? ti.depth[own] : 0;
        for (int t = execBackwardStart(ti); t >= 0; t--) {
            long j = ti.execIndices[t];
            if (ti.depth[(int) j] <= d && j != own) {
                return moveTo(cursorTid, j);
            }
        }
        return new StepResult(false, NO_EARLIER_STEP);
    }

    /** The cursor's frame ENTER index, or {@code -1} outside any frame. */
    private int ownFrameEnter(final ThreadIndex ti) {
        int ci = (int) cursorIndex;
        return ci >= 0 && ci < ti.frameEnter.length ? ti.frameEnter[ci] : -1;
    }

    /**
     * Move to the next (forward) / previous (backward) LINE record whose site is the same
     * source {@code (class, line)} as the cursor — hopping between re-hits of the current
     * line, e.g. loop iterations (Ctrl-R / Alt-R).
     */
    public StepResult runToCurrentLine(final boolean forward) {
        ThreadIndex ti = index(cursorTid);
        if (!ti.hasLines()) {
            return new StepResult(false, "no line records in this thread");
        }
        int location = rec(cursorTid, cursorIndex).location();
        int idx = Arrays.binarySearch(ti.lineIndices, cursorIndex);
        if (forward) {
            int start = idx >= 0 ? idx + 1 : -idx - 1;
            for (int t = start; t < ti.lineIndices.length; t++) {
                if (rec(cursorTid, ti.lineIndices[t]).location() == location) {
                    return moveTo(cursorTid, ti.lineIndices[t]);
                }
            }
            return new StepResult(false, NO_NEXT_APPEARANCE);
        }
        int start = idx >= 0 ? idx - 1 : -idx - 2;
        for (int t = start; t >= 0; t--) {
            if (rec(cursorTid, ti.lineIndices[t]).location() == location) {
                return moveTo(cursorTid, ti.lineIndices[t]);
            }
        }
        return new StepResult(false, NO_PREV_APPEARANCE);
    }

    /**
     * Jump the cursor to the next ({@code forward}) / previous {@code LINE} record in the CURRENT
     * thread, strictly after / before the cursor index (§19.2, keys {@code j}/{@code J} in a
     * "green" callsOnly context). The thread's sorted {@link ThreadIndex#lineIndices} is
     * binary-searched for the insertion point and the following (forward) / preceding entry is the
     * landing — so the jump hops straight over any callsOnly gap (the {@code METHOD_ENTER}/
     * {@code METHOD_EXIT} records between two line-traced records). No-op ({@link
     * StepResult#moved()} {@code false}, {@link #NO_FURTHER_LINE_TRACE}) when there is no such
     * record — the cursor already sits on the thread's last/first {@code LINE}, or the thread
     * carries no {@code LINE} records at all. On a move the selected frame resets to innermost
     * through the normal {@link #moveTo} path, so the TUI's {@code doStep} handles {@code afterMove}
     * and the single history entry exactly like every other navigation.
     *
     * @param forward {@code true} to advance to the next LINE, {@code false} to the previous one
     */
    public StepResult toLineTrace(final boolean forward) {
        ThreadIndex ti = index(cursorTid);
        int idx = Arrays.binarySearch(ti.lineIndices, cursorIndex);
        if (forward) {
            int start = idx >= 0 ? idx + 1 : -idx - 1;
            if (start < ti.lineIndices.length) {
                return moveTo(cursorTid, ti.lineIndices[start]);
            }
        } else {
            int start = idx >= 0 ? idx - 1 : -idx - 2;
            if (start >= 0) {
                return moveTo(cursorTid, ti.lineIndices[start]);
            }
        }
        return new StepResult(false, NO_FURTHER_LINE_TRACE);
    }

    /**
     * Move the cursor into another thread — onto the execution record (§17.1 set) with the
     * greatest {@code seq ≤} the current seq, else that thread's first exec record, keeping
     * the timelines roughly aligned when switching (§2.6). A thread with no exec records at
     * all (metadata only) positions on its first record.
     */
    public StepResult selectThread(final int tid) {
        ThreadIndex ti = indices.get(tid);
        if (ti == null) {
            return new StepResult(false, "no such thread t" + tid);
        }
        if (ti.count == 0) {
            return new StepResult(false, "thread t" + tid + " has no records");
        }
        if (ti.execIndices.length == 0) {
            return moveTo(tid, 0);
        }
        long targetSeq = currentSeq();
        int lo = 0;
        int hi = ti.execIndices.length - 1;
        int ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long seqMid = rec(tid, ti.execIndices[mid]).seq();
            if (seqMid <= targetSeq) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        long chosen = ans >= 0 ? ti.execIndices[ans] : ti.execIndices[0];
        return moveTo(tid, chosen);
    }

    /**
     * Jump the cursor straight to the record at {@code (tid, index)} — the absolute move behind
     * history-trail entries and search-result selections (§11.5/§11.7). Callers pass indices
     * that are already execution records (the §17.1 set — the TUI lands on one by seek+step);
     * on a move the selected frame resets to innermost, exactly like the stepping methods.
     *
     * @param tid the thread to move into
     * @param index the record index (an execution record) to land on
     * @return {@link StepResult#moved()} {@code false} with {@link #ALREADY_HERE} when the cursor
     *     is already at {@code (tid, index)}; otherwise a plain moved result
     */
    public StepResult seek(final int tid, final long index) {
        if (tid == cursorTid && index == cursorIndex) {
            return new StepResult(false, ALREADY_HERE);
        }
        return moveTo(tid, index);
    }

    /**
     * Locate the record with global sequence number {@code seq} (§16.7). Each thread's record
     * file is seq-ascending — {@code seq} is a global {@code AtomicLong} incremented once per
     * record, so within a thread the record indices are strictly increasing in seq — so each
     * thread is binary-searched over {@code [0, count)}. {@code seq} is globally unique, so at
     * most one record matches across ALL threads; the first thread found to carry it is returned
     * (any is fine, since there is only one). Returns {@link SeqLocation#found()} {@code false}
     * (with {@code tid == 0}, {@code index == 0}) when no record has that seq. Does <em>not</em>
     * move the cursor and does not require the record to be a {@code LINE}.
     *
     * @param seq the global sequence number to locate
     */
    public SeqLocation findSeq(final long seq) {
        for (int tid : recording.threadIds()) {
            ThreadIndex ti = index(tid);
            long lo = 0;
            long hi = ti.count - 1L;
            while (lo <= hi) {
                long mid = (lo + hi) >>> 1;
                long midSeq = rec(tid, mid).seq();
                if (midSeq == seq) {
                    return new SeqLocation(true, tid, mid);
                } else if (midSeq < seq) {
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
        }
        return new SeqLocation(false, 0, 0L);
    }

    /**
     * Move to the innermost (execution) frame's <em>return</em>: the greatest {@code LINE} index
     * {@code j} in this thread with {@code frameEnter[j] == frameEnter[cursor]} and
     * {@code j ≤ matchingExit[enter]} (§11.6, key {@code e}). No-op with {@link
     * #ALREADY_AT_FRAME_RETURN} when the cursor is already on that line or the frame has no
     * {@code LINE} record. On a move the selected frame resets to innermost.
     */
    public StepResult exitFrameForward() {
        ThreadIndex ti = index(cursorTid);
        if (!ti.hasLines()) {
            return new StepResult(false, ALREADY_AT_FRAME_RETURN);
        }
        int ci = (int) cursorIndex;
        int enter = ci >= 0 && ci < ti.frameEnter.length ? ti.frameEnter[ci] : -1;
        long bound = enter >= 0 ? ti.matchingExit[enter] : ti.count - 1;
        long target = -1;
        for (int t = ti.lineIndices.length - 1; t >= 0; t--) {
            long j = ti.lineIndices[t];
            if (j > bound) {
                continue;
            }
            if (ti.frameEnter[(int) j] == enter) {
                target = j;
                break;
            }
        }
        if (target < 0 || target == cursorIndex) {
            return new StepResult(false, ALREADY_AT_FRAME_RETURN);
        }
        return moveTo(cursorTid, target);
    }

    /**
     * Move to the innermost (execution) frame's <em>entry</em>: the least {@code LINE} index
     * {@code j} in this thread with {@code frameEnter[j] == frameEnter[cursor]} (§11.6, key
     * {@code E}). No-op with {@link #ALREADY_AT_FRAME_ENTRY} when the cursor is already on that
     * line or the frame has no {@code LINE} record. On a move the selected frame resets to
     * innermost.
     */
    public StepResult exitFrameBackward() {
        ThreadIndex ti = index(cursorTid);
        if (!ti.hasLines()) {
            return new StepResult(false, ALREADY_AT_FRAME_ENTRY);
        }
        int ci = (int) cursorIndex;
        int enter = ci >= 0 && ci < ti.frameEnter.length ? ti.frameEnter[ci] : -1;
        long lowerBound = enter >= 0 ? enter : 0;
        long target = -1;
        for (int t = 0; t < ti.lineIndices.length; t++) {
            long j = ti.lineIndices[t];
            if (j < lowerBound) {
                continue;
            }
            if (ti.frameEnter[(int) j] == enter) {
                target = j;
                break;
            }
        }
        if (target < 0 || target == cursorIndex) {
            return new StepResult(false, ALREADY_AT_FRAME_ENTRY);
        }
        return moveTo(cursorTid, target);
    }

    /**
     * Select which frame the Source/Locals windows display; does not move the cursor (§2.3).
     * Unrecorded (capture-only) frames are selectable too (§14.5) — {@link #locals} simply
     * yields nothing for them.
     */
    public boolean selectFrame(final int frameIndex) {
        if (frameIndex < 0 || frameIndex >= publicFrames.size()) {
            return false;
        }
        selectedFrame = frameIndex;
        return true;
    }

    /** Index into {@code execIndices} of the first exec record strictly after the cursor. */
    private int execForwardStart(final ThreadIndex ti) {
        int idx = Arrays.binarySearch(ti.execIndices, cursorIndex);
        return idx >= 0 ? idx + 1 : -idx - 1;
    }

    /** Index into {@code execIndices} of the last exec record strictly before the cursor, or -1. */
    private int execBackwardStart(final ThreadIndex ti) {
        int idx = Arrays.binarySearch(ti.execIndices, cursorIndex);
        return idx >= 0 ? idx - 1 : -idx - 2;
    }

    private StepResult moveTo(final int tid, final long idx) {
        cursorTid = tid;
        cursorIndex = idx;
        recompute();
        return new StepResult(true, "");
    }

    // ---- frames (§2.3, §14.5) ----

    /**
     * The call stack at the cursor, outermost first. For schema-4 recordings the recorded
     * frames are preceded by the unrecorded (grey) caller frames derived from the thread's
     * nearest {@code STACK_SNAPSHOT} at/before the cursor (§14.5); the innermost recorded
     * frame is always last.
     */
    public List<Frame> stack() {
        return publicFrames;
    }

    /** Currently selected frame index; reset to innermost on every cursor move. */
    public int selectedFrame() {
        return selectedFrame;
    }

    /**
     * The record the frame's shown state is reconstructed as-of (§17.5): the frame's last
     * execution record at/before the cursor — the cursor itself for the innermost frame, the
     * call-site LINE for an outer frame, or the frame's own ENTER when it has executed no
     * line yet. {@code found=false} for an out-of-range index or an unrecorded (capture-only)
     * frame, whose shown state is not reconstructed from records at all.
     *
     * @param frameIndex a stack index, {@code 0} = outermost .. {@code stack().size()-1} = innermost
     */
    public SeqLocation frameAsOf(final int frameIndex) {
        int unrecorded = publicFrames.size() - ctx.size();
        if (frameIndex < unrecorded || frameIndex >= publicFrames.size()) {
            return new SeqLocation(false, 0, 0L);
        }
        return new SeqLocation(true, cursorTid, ctx.get(frameIndex - unrecorded).asOfIndex());
    }

    /**
     * Whether stack frame {@code frameIndex} executed any {@code LINE} record within its span
     * (§18.3): {@code true} iff the frame is a recorded {@link #stack()} frame and at least one
     * {@code LINE} record with {@code frameEnter} equal to its ENTER index exists in
     * {@code [enterIndex, matchingExit]} — nested callees' lines belong to their own enters and
     * never count. {@code false} for an unrecorded (capture-only) frame or an out-of-range index.
     * Answered from the sorted {@link ThreadIndex} line positions (binary search + a walk bounded
     * by the frame's span), never by scanning the thread's records.
     *
     * @param frameIndex a stack index, {@code 0} = outermost .. {@code stack().size()-1} = innermost
     */
    public boolean frameHasLines(final int frameIndex) {
        int unrecorded = publicFrames.size() - ctx.size();
        if (frameIndex < unrecorded || frameIndex >= publicFrames.size()) {
            return false;
        }
        ThreadIndex ti = index(cursorTid);
        int enter = ctx.get(frameIndex - unrecorded).frame().enterIndex();
        long exit = ti.matchingExit[enter];
        int t = Arrays.binarySearch(ti.lineIndices, enter);
        if (t < 0) {
            t = -t - 1;
        }
        for (; t < ti.lineIndices.length; t++) {
            long j = ti.lineIndices[t];
            if (j > exit) {
                return false;
            }
            if (ti.frameEnter[(int) j] == enter) {
                return true;
            }
        }
        return false;
    }

    private void recompute() {
        ThreadIndex ti = index(cursorTid);
        int ci = (int) cursorIndex;
        int enter = ci >= 0 && ci < ti.frameEnter.length ? ti.frameEnter[ci] : -1;
        List<Integer> enters = new ArrayList<>();
        int cur = enter;
        while (cur != -1) {
            enters.add(cur);
            cur = ti.parentEnter[cur];
        }
        Collections.reverse(enters); // outermost first
        int m = enters.size();
        int cursorLine = ci >= 0 && rec(cursorTid, ci).type() == Format.LINE ? lineNumberAt(cursorTid, ci) : -1;

        List<Frame> unrecorded = unrecordedFrames(ti, ci, enters);
        int u = unrecorded.size();

        List<FrameCtx> list = new ArrayList<>(m);
        for (int k = 0; k < m; k++) {
            int enterK = enters.get(k);
            Rec er = rec(cursorTid, enterK);
            Site s = recording.site(er.location());
            String cls = s != null ? recording.symbolText(s.classSym()) : null;
            String meth = s != null && s.memberSym() != 0 ? recording.symbolText(s.memberSym()) : null;
            String desc = s != null ? s.desc() : "";
            String file = s != null ? recording.symbolText(s.fileSym()) : null;
            boolean stat = s != null && s.isStatic();

            int currentLine;
            long asOf;
            if (k == m - 1) {
                // Innermost frame: the cursor line itself; on an ENTER/EXIT cursor (§17.1)
                // the last LINE the frame executed at/before the cursor, else the site line
                // (an ENTER before any line, or a line-less frame).
                if (cursorLine >= 0) {
                    currentLine = cursorLine;
                } else {
                    long lastLine = lastFrameLineAtOrBefore(ti, enterK, ci);
                    currentLine = lastLine >= 0 ? lineNumberAt(cursorTid, lastLine)
                            : (s != null ? s.line() : 0);
                }
                asOf = ci;
            } else {
                int childEnter = enters.get(k + 1);
                long lastLine = lastFrameLineAtOrBefore(ti, enterK, childEnter - 1L);
                if (lastLine >= 0) {
                    currentLine = lineNumberAt(cursorTid, lastLine);
                    asOf = lastLine;
                } else {
                    currentLine = s != null ? s.line() : 0;
                    asOf = enterK;
                }
            }
            Frame f = new Frame(u + k, enterK, er.location(), cls, meth, desc, file, currentLine, stat, true);
            list.add(new FrameCtx(f, asOf));
        }
        this.ctx = list;
        List<Frame> merged = new ArrayList<>(u + m);
        merged.addAll(unrecorded);
        for (FrameCtx fc : list) {
            merged.add(fc.frame());
        }
        this.publicFrames = List.copyOf(merged);
        this.selectedFrame = Math.max(0, merged.size() - 1);
    }

    /**
     * The greatest {@code LINE} record index {@code ≤ bound} belonging to the frame entered at
     * {@code enterK} ({@code frameEnter == enterK}), or {@code -1} when that frame has executed
     * no line by {@code bound}. Walks the sorted {@code lineIndices} backward from the bound,
     * stopping once indices fall before the frame's ENTER — every record of a frame follows its
     * ENTER, so nothing earlier can belong to it (lines of nested callees in between belong to
     * their own enters and are skipped by the {@code frameEnter} test).
     */
    private long lastFrameLineAtOrBefore(final ThreadIndex ti, final int enterK, final long bound) {
        int t = Arrays.binarySearch(ti.lineIndices, bound);
        if (t < 0) {
            t = -t - 2;
        }
        for (; t >= 0; t--) {
            long j = ti.lineIndices[t];
            if (j < enterK) {
                return -1;
            }
            if (ti.frameEnter[(int) j] == enterK) {
                return j;
            }
        }
        return -1;
    }

    /**
     * The §14.5 unrecorded (grey) caller frames for the cursor position, outermost-first:
     * the thread's nearest {@code STACK_SNAPSHOT} at/before the cursor is scanned
     * innermost-first for the first frame equal to the outermost recorded frame by
     * {@code (classBinary, methodName)}; the capture frames above the match (its callers)
     * become unrecorded context. With an empty recorded stack, or when no capture frame
     * matches (stale-but-useful approximation), the whole capture is unrecorded context.
     * Empty for schema ≤ 3 recordings or when the thread has no capture yet.
     */
    private List<Frame> unrecordedFrames(final ThreadIndex ti, final int ci, final List<Integer> enters) {
        if (schemaVersion < 4 || ti.stackSnapshotIndices.length == 0 || ci < 0) {
            return List.of();
        }
        List<Recording.CapturedFrame> capture = captureAtOrBefore(ti, ci);
        if (capture.isEmpty()) {
            return List.of();
        }
        int from = 0;
        if (!enters.isEmpty()) {
            Site s = recording.site(rec(ti.tid, enters.get(0)).location());
            String outClass = s != null ? recording.symbolText(s.classSym()) : null;
            String outMethod = s != null && s.memberSym() != 0 ? recording.symbolText(s.memberSym()) : null;
            for (int k = 0; k < capture.size(); k++) {
                Recording.CapturedFrame cf = capture.get(k);
                if (Objects.equals(cf.classBinary(), outClass) && Objects.equals(cf.methodName(), outMethod)) {
                    from = k + 1; // C[k] is R_out itself; its callers are C[k+1..]
                    break;
                }
            }
        }
        List<Frame> out = new ArrayList<>(capture.size() - from);
        for (int i = capture.size() - 1; i >= from; i--) { // innermost-first → outermost-first
            Recording.CapturedFrame cf = capture.get(i);
            out.add(new Frame(out.size(), -1, 0, cf.classBinary(), cf.methodName(), "",
                    cf.fileName(), cf.line(), false, false));
        }
        return out;
    }

    /** Resolved frames (innermost-first) of the nearest {@code STACK_SNAPSHOT} at/before {@code ci}, else empty. */
    private List<Recording.CapturedFrame> captureAtOrBefore(final ThreadIndex ti, final int ci) {
        long[] caps = ti.stackSnapshotIndices;
        int lo = 0;
        int hi = caps.length - 1;
        int ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (caps[mid] <= ci) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (ans < 0) {
            return List.of();
        }
        try {
            return recording.stackAt(ti.tid, rec(ti.tid, caps[ans]).after());
        } catch (IOException e) {
            return List.of(); // torn capture blob (truncated tail) → no unrecorded context
        }
    }

    private int lineNumberAt(final int tid, final long index) {
        Site s = recording.site(rec(tid, index).location());
        return s != null ? s.line() : 0;
    }

    // ---- locals (§2.4) ----

    /**
     * Reconstruct frame {@code frameIndex}'s locals as-of the cursor: seed parameter slots
     * from the frame's {@code ARG} records (slot 0 = {@code this} for instance methods), then
     * apply {@code LOCAL_WRITE} records up to the frame's as-of index, latest value per slot.
     * An unrecorded (capture-only) frame has no locals — the list is empty (§14.5).
     */
    public List<Local> locals(final int frameIndex) {
        int unrecorded = publicFrames.size() - ctx.size();
        if (frameIndex < 0 || frameIndex >= publicFrames.size() || frameIndex < unrecorded) {
            return List.of();
        }
        FrameCtx fc = ctx.get(frameIndex - unrecorded);
        Frame frame = fc.frame();
        ThreadIndex ti = index(cursorTid);
        int enter = frame.enterIndex();
        int exit = ti.matchingExit[enter];
        long p = Math.min(fc.asOfIndex(), exit);

        TreeMap<Integer, Local> bySlot = new TreeMap<>();

        // 1. Synthetic `this` for instance frames — the derivation is shared with
        // frameThisId() (§16.7) so the two never diverge.
        if (!frame.isStatic()) {
            long thisId = thisIdOf(frame, ti);
            Value v = thisId != 0 ? new Value.Ref(thisId, renderObjectId(thisId)) : new Value.Null();
            String desc = frame.classBinary() != null
                    ? "L" + frame.classBinary().replace('.', '/') + ";"
                    : "";
            bySlot.put(0, new Local(0, "this", desc, v));
        }

        // 1b. Parameter slots from ARG records that immediately follow the ENTER. Names come
        // from the site's §5 `#p` slot→name map when the recording carries one (§17.4,
        // recorder ≥ v1.7); the argN fallback keeps older recordings readable.
        List<String> params = paramDescriptors(frame.descriptor());
        Site methodSite = recording.site(frame.methodSiteId());
        Map<Integer, String> paramNames = methodSite != null ? methodSite.paramNames() : Map.of();
        int base = frame.isStatic() ? 0 : 1;
        int[] argSlot = new int[params.size()];
        int acc = base;
        for (int a = 0; a < params.size(); a++) {
            argSlot[a] = acc;
            acc += slotSize(params.get(a));
        }
        for (long j = enter + 1; j < ti.count; j++) {
            Rec r = rec(cursorTid, j);
            if (r.type() != Format.ARG) {
                break;
            }
            int argIdx = r.idx();
            int slot = argIdx < argSlot.length ? argSlot[argIdx] : base + argIdx;
            String d = argIdx < params.size() ? params.get(argIdx) : "";
            Value v = toValue(r.afterKind(), r.tag(), r.after(), r.tid(), r.isTruncated());
            String name = paramNames.getOrDefault(slot, "arg" + argIdx);
            bySlot.put(slot, new Local(slot, name, d, v));
        }

        // 2. LOCAL_WRITE records in (enter, p] belonging to this frame, latest wins.
        for (long k = enter + 1; k <= p && k < ti.count; k++) {
            Rec r = rec(cursorTid, k);
            if (r.type() != Format.LOCAL_WRITE || ti.frameEnter[(int) k] != enter) {
                continue;
            }
            int slot = r.idx();
            Site s = recording.site(r.location());
            String name = s != null && s.memberSym() != 0 ? recording.symbolText(s.memberSym()) : null;
            String desc = s != null ? s.desc() : "";
            Value v = toValue(r.afterKind(), r.tag(), r.after(), r.tid(), r.isTruncated());
            bySlot.put(slot, new Local(slot, name != null ? name : "slot" + slot, desc, v));
        }

        return List.copyOf(bySlot.values());
    }

    /**
     * The {@code this} object id (slot 0) of stack frame {@code frameIndex}, or {@code 0} for a
     * static frame, an unrecorded (capture-only) frame, an out-of-range index, or when no
     * {@code this} is resolvable (§16.7). Reuses the exact §2.4 {@code this}-derivation that
     * {@link #locals} performs (both delegate to {@link #thisIdOf}), so the two never diverge.
     *
     * @param frameIndex a stack index, {@code 0} = outermost .. {@code depth-1} = innermost
     */
    public long frameThisId(final int frameIndex) {
        if (frameIndex < 0 || frameIndex >= publicFrames.size()) {
            return 0;
        }
        return thisIdOf(publicFrames.get(frameIndex), index(cursorTid));
    }

    /**
     * The §2.4 {@code this}-derivation shared by {@link #locals} and {@link #frameThisId}: for a
     * recorded instance frame, the slot-0 {@code this} object id read from the frame's ENTER
     * record {@code target}, with the {@code <init>} fallback — a constructor's ENTER carries
     * {@code target 0} (this is uninitialised at entry) so the live object's id is recovered from
     * the matching {@code METHOD_EXIT}. Returns {@code 0} for a static frame, an unrecorded frame
     * (no ENTER), or when the id is genuinely absent.
     */
    private long thisIdOf(final Frame frame, final ThreadIndex ti) {
        if (frame.isStatic() || !frame.recorded() || frame.enterIndex() < 0) {
            return 0;
        }
        int enter = frame.enterIndex();
        int exit = ti.matchingExit[enter];
        long thisId = rec(cursorTid, enter).target();
        if (thisId == 0 && "<init>".equals(frame.methodName()) && exit >= 0 && exit < ti.count) {
            Rec exitRec = rec(cursorTid, exit);
            if (exitRec.type() == Format.METHOD_EXIT) {
                thisId = exitRec.target();
            }
        }
        return thisId;
    }

    // ---- object trees (§2.5) ----

    /** Root node for an object id (label = {@code renderObjectId(id)}), expandable when non-null. */
    public ObjectNode objectTree(final long objectId) {
        Value v = objectId == 0 ? new Value.Null() : new Value.Ref(objectId, renderObjectId(objectId));
        return new ObjectNode(renderObjectId(objectId), v, objectId != 0);
    }

    /** Materialise a node's children with no ancestor path (convenience). */
    public List<ObjectNode> children(final ObjectNode node) {
        return children(node, Set.of());
    }

    /**
     * Materialise a node's children as-of the current seq (§2.5): base is the object's latest
     * snapshot with {@code seq ≤ currentSeq}, over which field writes in {@code (snapshot.seq,
     * currentSeq]} are applied; containers unfold to elements/pairs with a {@code …+N more}
     * sentinel when truncated at capture. A MAP unfolds to entry nodes labelled
     * {@code <key> -> <value>}, each expandable into exactly the two children
     * {@code key = <key>} and {@code value = <value>} carrying their own values (§17.3), so a
     * Ref key or value is independently explorable from there. {@code ancestorIds} carries the
     * object ids already on the path so a back-reference renders as a {@code ↺ (cycle)} leaf
     * instead of recursing.
     */
    public List<ObjectNode> children(final ObjectNode node, final Set<Long> ancestorIds) {
        if (node.isMapEntry()) {
            // §17.3: an entry expands to its key/value pair; the entry itself carries no
            // object id, so the ancestor path passes through unchanged (the cycle guard
            // applies to the Ref key/value children exactly as to any other child).
            return List.of(
                    childNode("key = ", node.entryKey(), ancestorIds),
                    childNode("value = ", node.entryValue(), ancestorIds));
        }
        if (!(node.value() instanceof Value.Ref ref) || ref.objectId() == 0) {
            return List.of();
        }
        long id = ref.objectId();
        Set<Long> path = new HashSet<>(ancestorIds);
        path.add(id);
        Reconstructed rc = reconstruct(id, currentSeq());
        if (!rc.present) {
            return List.of(new ObjectNode("<No info available>", new Value.Absent(), false));
        }
        List<ObjectNode> out = new ArrayList<>();
        if (rc.form == Format.FORM_FIELDS) {
            for (Map.Entry<String, Value> e : rc.fields.entrySet()) {
                out.add(childNode(e.getKey() + " = ", e.getValue(), path));
            }
        } else if (rc.form == Format.FORM_MAP) {
            int pairs = rc.keys.size();
            for (int i = 0; i < pairs; i++) {
                Value k = rc.keys.get(i);
                Value v = rc.values.get(i);
                out.add(ObjectNode.mapEntry(k.display() + " -> " + v.display(), k, v));
            }
            if (rc.totalSize > pairs) {
                out.add(moreLeaf(rc.totalSize - pairs));
            }
        } else {
            for (int i = 0; i < rc.elements.size(); i++) {
                out.add(childNode("[" + i + "] = ", rc.elements.get(i), path));
            }
            if (rc.totalSize > rc.elements.size()) {
                out.add(moreLeaf(rc.totalSize - rc.elements.size()));
            }
        }
        return out;
    }

    private ObjectNode childNode(final String prefix, final Value v, final Set<Long> path) {
        if (v instanceof Value.Ref r && r.objectId() != 0 && path.contains(r.objectId())) {
            return new ObjectNode(prefix + "↺ #" + r.objectId() + " (cycle)", v, false);
        }
        boolean expandable = v instanceof Value.Ref r2 && r2.objectId() != 0;
        return new ObjectNode(prefix + v.display(), v, expandable);
    }

    private ObjectNode moreLeaf(final long n) {
        return new ObjectNode("…+" + n + " more", new Value.Absent(), false);
    }

    private Reconstructed reconstruct(final long id, final long asOfSeq) {
        List<ObjEvent> events = objEvents.getOrDefault(id, List.of());
        ObjEvent snap = null;
        for (ObjEvent ev : events) {
            if (ev.snapshot() && ev.seq() <= asOfSeq) {
                snap = ev; // list is seq-ordered, so the last match is the latest snapshot
            }
        }
        long snapSeq = snap != null ? snap.seq() : Long.MIN_VALUE;
        List<ObjEvent> writes = new ArrayList<>();
        for (ObjEvent ev : events) {
            if (!ev.snapshot() && ev.seq() > snapSeq && ev.seq() <= asOfSeq) {
                writes.add(ev);
            }
        }
        Recording.Snapshot parsed = null;
        int snapTid = cursorTid;
        if (snap != null) {
            snapTid = snap.tid();
            Rec sr = rec(snap.tid(), snap.index());
            parsed = snapshotAt(snap.tid(), sr.after());
        }
        if (parsed == null && writes.isEmpty()) {
            return Reconstructed.absent();
        }
        int form = parsed != null ? parsed.form() : Format.FORM_FIELDS;

        if (form == Format.FORM_FIELDS) {
            LinkedHashMap<String, Value> fields = new LinkedHashMap<>();
            if (parsed != null) {
                for (Recording.SnapField f : parsed.fields()) {
                    String name = recording.symbolText(f.nameSym());
                    fields.put(name != null ? name : "?sym" + f.nameSym(),
                            toValue(f.kind(), f.tag(), f.value(), snapTid, false));
                }
            }
            for (ObjEvent w : writes) {
                Rec wr = rec(w.tid(), w.index());
                Site s = recording.site(wr.location());
                String name = s != null && s.memberSym() != 0 ? recording.symbolText(s.memberSym()) : null;
                fields.put(name != null ? name : "?field",
                        toValue(wr.afterKind(), wr.tag(), wr.after(), w.tid(), wr.isTruncated()));
            }
            return Reconstructed.fields(fields);
        }

        List<Value> keys = new ArrayList<>();
        List<Value> values = new ArrayList<>();
        List<Value> elements = new ArrayList<>();
        List<Recording.SnapField> es = parsed.fields();
        if (form == Format.FORM_MAP) {
            for (int i = 0; i + 1 < es.size(); i += 2) {
                keys.add(toValue(es.get(i).kind(), es.get(i).tag(), es.get(i).value(), snapTid, false));
                values.add(toValue(es.get(i + 1).kind(), es.get(i + 1).tag(), es.get(i + 1).value(), snapTid, false));
            }
        } else {
            for (Recording.SnapField f : es) {
                elements.add(toValue(f.kind(), f.tag(), f.value(), snapTid, false));
            }
        }
        return Reconstructed.container(form, parsed.totalSize(), keys, values, elements);
    }

    private LinkedHashMap<String, Value> fieldsOf(final long id) {
        Reconstructed rc = reconstruct(id, currentSeq());
        return rc.present && rc.form == Format.FORM_FIELDS ? rc.fields : new LinkedHashMap<>();
    }

    // ---- watches (§2.7) ----

    /** Resolve a watch expression as-of the current cursor: {@code #id}/{@code id} or {@code Class.field.path}. */
    public WatchResult resolveWatch(final String expression) {
        String expr = expression == null ? "" : expression.trim();
        if (expr.isEmpty()) {
            return new WatchResult(expression, null, UNSUPPORTED_WATCH);
        }
        String idText = expr.startsWith("#") ? expr.substring(1) : expr;
        if (idText.matches("\\d+")) {
            try {
                return new WatchResult(expression, objectTree(Long.parseLong(idText)), null);
            } catch (NumberFormatException e) {
                return new WatchResult(expression, null, UNSUPPORTED_WATCH);
            }
        }
        if (expr.startsWith("#")) {
            return new WatchResult(expression, null, UNSUPPORTED_WATCH);
        }

        String[] tokens = expr.split("\\.");
        if (tokens.length < 2) {
            return new WatchResult(expression, null, UNSUPPORTED_WATCH);
        }
        for (int splitLen = 1; splitLen < tokens.length; splitLen++) {
            String classCand = String.join(".", Arrays.copyOfRange(tokens, 0, splitLen));
            String binary = resolveClassBinary(classCand, splitLen == 1);
            if (binary == null) {
                continue;
            }
            String field = tokens[splitLen];
            WriteRef w = latestStaticWrite(binary + "#" + field);
            if (w == null) {
                continue;
            }
            Rec wr = rec(w.tid(), w.index());
            Value val = toValue(wr.afterKind(), wr.tag(), wr.after(), w.tid(), wr.isTruncated());
            return walkPath(expression, val, Arrays.copyOfRange(tokens, splitLen + 1, tokens.length));
        }
        return new WatchResult(expression, null, UNSUPPORTED_WATCH);
    }

    private WatchResult walkPath(final String expression, final Value start, final String[] segments) {
        Value val = start;
        for (String seg : segments) {
            if (!(val instanceof Value.Ref r) || r.objectId() == 0) {
                return new WatchResult(expression, null,
                        "no field `" + seg + "` on " + val.display() + " (as of seq " + currentSeq() + ")");
            }
            LinkedHashMap<String, Value> fields = fieldsOf(r.objectId());
            if (!fields.containsKey(seg)) {
                return new WatchResult(expression, null,
                        "no field `" + seg + "` on #" + r.objectId() + " (as of seq " + currentSeq() + ")");
            }
            val = fields.get(seg);
        }
        if (val instanceof Value.Ref r && r.objectId() != 0) {
            return new WatchResult(expression, objectTree(r.objectId()), null);
        }
        return new WatchResult(expression, new ObjectNode(val.display(), val, false), null);
    }

    private String resolveClassBinary(final String candidate, final boolean allowSimple) {
        if (staticOwners.contains(candidate)) {
            return candidate;
        }
        if (allowSimple) {
            List<String> owners = ownersBySimpleName.get(candidate);
            if (owners != null && owners.size() == 1) {
                return owners.get(0);
            }
        }
        return null;
    }

    private WriteRef latestStaticWrite(final String key) {
        List<WriteRef> list = staticWrites.get(key);
        if (list == null) {
            return null;
        }
        long seq = currentSeq();
        WriteRef ans = null;
        for (WriteRef w : list) {
            if (w.seq() <= seq) {
                ans = w;
            } else {
                break;
            }
        }
        return ans;
    }

    // ---- record info (§2.8) ----

    /** Everything the Record Info window shows for the cursor record (§2.8). */
    public RecordInfo recordInfo() {
        Rec r = rec(cursorTid, cursorIndex);
        ThreadIndex ti = index(cursorTid);
        long threadCount = ti.count;
        return new RecordInfo(
                r.seq(),
                cursorTid,
                threadName(cursorTid),
                Format.typeName(r.type()),
                formatSite(r),
                cursorIndex,
                threadCount,
                globalRecordCount,
                ti.timeAt(cursorIndex),
                depthAtCursor(),
                recording.isTruncated());
    }

    private String formatSite(final Rec r) {
        Site s = recording.site(r.location());
        String cls = s != null ? recording.symbolText(s.classSym()) : null;
        String file = s != null ? recording.symbolText(s.fileSym()) : null;
        int line = s != null ? s.line() : 0;
        String method = s != null && s.memberSym() != 0 ? recording.symbolText(s.memberSym()) : innermostMethod();
        String clsSimple = cls != null ? Format.simpleName(cls) : "?";
        String methodPart = method != null && !method.isEmpty() ? "." + method : "";
        String filePart = (file != null ? file : "?") + ":" + line;
        return clsSimple + methodPart + " (" + filePart + ")";
    }

    private String innermostMethod() {
        return ctx.isEmpty() ? null : ctx.get(ctx.size() - 1).frame().methodName();
    }

    // ---- threads ----

    /** Thread ids with recorded activity, ascending. */
    public List<Integer> threads() {
        return recording.threadIds();
    }

    /** Recorded name of a thread, or a {@code t<tid>} fallback when unknown. */
    public String threadName(final int tid) {
        ThreadIndex ti = indices.get(tid);
        String name = ti != null ? ti.threadName : null;
        return name != null ? name : "t" + tid;
    }

    /**
     * Whether thread {@code tid} carries any {@code LINE} record at all (§18.3) — {@code false}
     * for an enter/exit-only (callsOnly) or metadata-only thread, and for an unknown tid. This is
     * the classification behind the Threads window's green marking and the §18.4
     * {@code viewer-thread-kinds.tsv} rows ({@code LINES} iff {@code true}).
     *
     * @param tid a dense thread id (see {@link #threads()})
     */
    public boolean threadHasLines(final int tid) {
        ThreadIndex ti = indices.get(tid);
        return ti != null && ti.hasLines();
    }

    // ---- method-call browse (§11.7) ----

    /**
     * The distinct {@code METHOD} sites that were entered anywhere in the recording, each with its
     * total invocation count, sorted by {@code classBinary} then {@code methodName} (§11.7). Built
     * once on first use and cached; the returned list is immutable. Names are resolved exactly as
     * {@link #stack()} and {@link #recordInfo()} resolve them.
     */
    public List<MethodSite> methodSites() {
        ensureMethodSites();
        return methodSitesCache;
    }

    /**
     * The first {@code cap} invocations of the given {@code METHOD} site across all threads,
     * ascending by global {@code seq} (§11.7). Returns an empty list for an unknown site id or a
     * non-positive {@code cap}. Shares the per-site occurrence index with {@link #methodSites()},
     * built once on first use.
     *
     * @param siteId a {@code METHOD} site id (typically from {@link MethodSite#siteId()})
     * @param cap the maximum number of invocations to return
     */
    public List<Call> callsOf(final int siteId, final int cap) {
        ensureMethodSites();
        List<Call> all = callsBySite.get(siteId);
        if (all == null || cap <= 0) {
            return List.of();
        }
        return List.copyOf(all.subList(0, Math.min(cap, all.size())));
    }

    private void ensureMethodSites() {
        if (methodSitesCache != null) {
            return;
        }
        Map<Integer, List<Call>> bySite = new HashMap<>();
        for (int tid : recording.threadIds()) {
            long count = index(tid).count;
            for (long i = 0; i < count; i++) {
                Rec r = rec(tid, i);
                if (r.type() == Format.METHOD_ENTER) {
                    bySite.computeIfAbsent(r.location(), k -> new ArrayList<>())
                            .add(new Call(r.seq(), tid, i));
                }
            }
        }
        Comparator<Call> byOrder = Comparator.comparingLong(Call::seq)
                .thenComparingInt(Call::tid)
                .thenComparingLong(Call::index);
        List<MethodSite> sites = new ArrayList<>(bySite.size());
        for (Map.Entry<Integer, List<Call>> e : bySite.entrySet()) {
            e.getValue().sort(byOrder);
            Site s = recording.site(e.getKey());
            String cls = s != null ? recording.symbolText(s.classSym()) : null;
            String meth = s != null && s.memberSym() != 0 ? recording.symbolText(s.memberSym()) : null;
            String desc = s != null ? s.desc() : "";
            sites.add(new MethodSite(e.getKey(), cls, meth, desc, e.getValue().size()));
        }
        sites.sort(Comparator
                .comparing((MethodSite m) -> m.classBinary() == null ? "" : m.classBinary())
                .thenComparing(m -> m.methodName() == null ? "" : m.methodName()));
        this.callsBySite = bySite;
        this.methodSitesCache = List.copyOf(sites);
    }

    // ---- instance & field-write browse (§13.6) ----

    /**
     * The distinct runtime classes the recording registered instances of (one entry per
     * {@code OBJECT_FIRST_SEEN} runtime class), each with its total instance count, sorted by
     * {@code classBinary} (§13.6). Built once on first use and cached; the returned list is
     * immutable.
     */
    public List<ClassSite> classSites() {
        ensureInstanceIndex();
        return classSitesCache;
    }

    /**
     * The first {@code cap} instances of the given runtime class across all threads, ascending
     * by global {@code seq} (§13.6). Returns an empty list for an unknown class or a non-positive
     * {@code cap}. Shares the per-class instance index with {@link #classSites()}, built once on
     * first use.
     *
     * @param classBinary a runtime class binary name (typically from {@link ClassSite#classBinary()})
     * @param cap the maximum number of instances to return
     */
    public List<Instance> instancesOf(final String classBinary, final int cap) {
        ensureInstanceIndex();
        List<Instance> all = instancesByClass.get(classBinary);
        if (all == null || cap <= 0) {
            return List.of();
        }
        return List.copyOf(all.subList(0, Math.min(cap, all.size())));
    }

    /**
     * The position of an object id's {@code OBJECT_FIRST_SEEN} record — the "go to first
     * mention" target of the instance-browse dialog (§13.6/§14.2, renamed from
     * {@code creationOf} per §14.3) — or {@code null} when the recording never registered
     * that id.
     */
    public Instance firstMentionOf(final long objectId) {
        ensureInstanceIndex();
        return firstMentionById.get(objectId);
    }

    /**
     * Every {@code FIELD_WRITE} to a field named {@code fieldName} whose owner class belongs to
     * source file {@code fileName}, ascending by global {@code seq}, stopping at {@code cap} hits
     * (§13.5). "Belongs to" is the outermost-simple-name rule: the owner class binary's text
     * after the last {@code .}, cut at the first {@code $}, plus {@code ".java"} must equal
     * {@code fileName} — so inner/nested classes match their enclosing file. Old/new values are
     * rendered at query time exactly as the reader dump renders them (per-slot runtime tags of
     * reference-typed fields, blob strings resolved).
     *
     * @param fileName the source file to match owners against, e.g. {@code Foo.java}
     * @param fieldName the bare field name to look up
     * @param cap the maximum number of hits to return
     */
    public List<FieldWriteHit> fieldWrites(final String fileName, final String fieldName, final int cap) {
        return fieldWrites(fileName, fieldName, 0L, cap);
    }

    /**
     * The owner-filtered variant of {@link #fieldWrites(String, String, int)} (§16.7): identical
     * to the 3-arg version but, when {@code ownerId != 0}, additionally requires the
     * {@code FIELD_WRITE} record's {@code target()} to equal {@code ownerId} — so a
     * {@code this}-instance query lists only that object's writes, and statics (target 0) are
     * excluded. {@code ownerId == 0} reproduces the 3-arg behaviour exactly, which is why the
     * 3-arg overload delegates here rather than duplicating the walk.
     *
     * @param fileName the source file to match owners against, e.g. {@code Foo.java}
     * @param fieldName the bare field name to look up
     * @param ownerId the owner object id to restrict to, or {@code 0} for all instances (+ statics)
     * @param cap the maximum number of hits to return
     */
    public List<FieldWriteHit> fieldWrites(
            final String fileName, final String fieldName, final long ownerId, final int cap) {
        ensureInstanceIndex();
        List<WriteRef> refs = fieldWriteRefsByName.get(fieldName);
        if (refs == null || fileName == null || cap <= 0) {
            return List.of();
        }
        List<FieldWriteHit> out = new ArrayList<>();
        for (WriteRef w : refs) {
            if (out.size() >= cap) {
                break;
            }
            Rec r = rec(w.tid(), w.index());
            if (ownerId != 0 && r.target() != ownerId) {
                continue;
            }
            Site s = recording.site(r.location());
            if (s == null) {
                continue;
            }
            String ownerBinary = recording.symbolText(s.classSym());
            if (!fileName.equals(outermostSimpleName(ownerBinary) + ".java")) {
                continue;
            }
            out.add(new FieldWriteHit(
                    r.seq(),
                    w.tid(),
                    w.index(),
                    r.target(),
                    ownerBinary,
                    fieldName,
                    s.isStatic() || r.isStatic(),
                    renderer.render(r, true),
                    renderer.render(r, false),
                    s.fileSym() != 0 ? recording.symbolText(s.fileSym()) : null,
                    s.line()));
        }
        return List.copyOf(out);
    }

    /**
     * The total number of {@code FIELD_WRITE}s {@link #fieldWrites} would match for
     * {@code (fileName, fieldName)} with an unbounded cap — the {@code of N} in a capped
     * variable-history note (§13.5). Walks the same index applying the same owner-file rule
     * but renders nothing, so it stays cheap even against millions of writes.
     *
     * @param fileName the source file to match owners against, e.g. {@code Foo.java}
     * @param fieldName the bare field name to look up
     */
    public long countFieldWrites(final String fileName, final String fieldName) {
        return countFieldWrites(fileName, fieldName, 0L);
    }

    /**
     * The owner-filtered variant of {@link #countFieldWrites(String, String)} (§16.7): identical
     * to the 2-arg version but, when {@code ownerId != 0}, only counts writes whose
     * {@code FIELD_WRITE} record {@code target()} equals {@code ownerId} (statics, target 0, are
     * excluded). {@code ownerId == 0} reproduces the 2-arg behaviour exactly, which is why the
     * 2-arg overload delegates here. Renders nothing, so it stays cheap against millions of writes.
     *
     * @param fileName the source file to match owners against, e.g. {@code Foo.java}
     * @param fieldName the bare field name to look up
     * @param ownerId the owner object id to restrict to, or {@code 0} for all instances (+ statics)
     */
    public long countFieldWrites(final String fileName, final String fieldName, final long ownerId) {
        ensureInstanceIndex();
        List<WriteRef> refs = fieldWriteRefsByName.get(fieldName);
        if (refs == null || fileName == null) {
            return 0;
        }
        long n = 0;
        for (WriteRef w : refs) {
            Rec r = rec(w.tid(), w.index());
            if (ownerId != 0 && r.target() != ownerId) {
                continue;
            }
            Site s = recording.site(r.location());
            if (s == null) {
                continue;
            }
            if (fileName.equals(outermostSimpleName(recording.symbolText(s.classSym())) + ".java")) {
                n++;
            }
        }
        return n;
    }

    /**
     * The one additional lazy full pass over all threads' records behind the §13.6 API (mirrors
     * {@link #ensureMethodSites()}): indexes every {@code OBJECT_FIRST_SEEN} by runtime class and
     * by object id, and every {@code FIELD_WRITE} by field name (positions only).
     */
    private void ensureInstanceIndex() {
        if (classSitesCache != null) {
            return;
        }
        Map<String, List<Instance>> byClass = new HashMap<>();
        Map<Long, Instance> byId = new HashMap<>();
        Map<String, List<WriteRef>> writeRefs = new HashMap<>();
        for (int tid : recording.threadIds()) {
            long count = index(tid).count;
            for (long i = 0; i < count; i++) {
                Rec r = rec(tid, i);
                if (r.type() == Format.OBJECT_FIRST_SEEN && r.target() != 0) {
                    String cls = recording.symbolText(r.location());
                    Instance inst = new Instance(r.target(), r.seq(), tid, i);
                    byClass.computeIfAbsent(cls != null ? cls : "?", k -> new ArrayList<>()).add(inst);
                    byId.merge(r.target(), inst, (a, b) -> a.seq() <= b.seq() ? a : b);
                } else if (r.type() == Format.FIELD_WRITE) {
                    Site s = recording.site(r.location());
                    String field = s != null && s.memberSym() != 0 ? recording.symbolText(s.memberSym()) : null;
                    if (field != null) {
                        writeRefs.computeIfAbsent(field, k -> new ArrayList<>())
                                .add(new WriteRef(r.seq(), tid, i));
                    }
                }
            }
        }
        Comparator<Instance> instOrder = Comparator
                .comparingLong(Instance::seq)
                .thenComparingInt(Instance::tid)
                .thenComparingLong(Instance::index);
        List<ClassSite> sites = new ArrayList<>(byClass.size());
        for (Map.Entry<String, List<Instance>> e : byClass.entrySet()) {
            e.getValue().sort(instOrder);
            sites.add(new ClassSite(e.getKey(), e.getValue().size()));
        }
        sites.sort(Comparator.comparing(ClassSite::classBinary));
        Comparator<WriteRef> refOrder = Comparator
                .comparingLong(WriteRef::seq)
                .thenComparingInt(WriteRef::tid)
                .thenComparingLong(WriteRef::index);
        for (List<WriteRef> list : writeRefs.values()) {
            list.sort(refOrder);
        }
        this.instancesByClass = byClass;
        this.firstMentionById = byId;
        this.fieldWriteRefsByName = writeRefs;
        this.classSitesCache = List.copyOf(sites);
    }

    /**
     * The §13.5 owner-file name part: the text after the last {@code .} of a binary name, cut at
     * the first {@code $} — {@code com.ex.Foo$Inner} → {@code Foo} — so a nested class maps to its
     * enclosing source file. (Contrast {@link Format#simpleName}, which keeps the innermost part.)
     */
    static String outermostSimpleName(final String binaryName) {
        if (binaryName == null || binaryName.isEmpty()) {
            return "?";
        }
        int dot = binaryName.lastIndexOf('.');
        String tail = dot >= 0 ? binaryName.substring(dot + 1) : binaryName;
        int dollar = tail.indexOf('$');
        return dollar >= 0 ? tail.substring(0, dollar) : tail;
    }

    // ---- shared value/descriptor helpers ----

    private Value toValue(final int kind, final int tag, final long slot, final int tid, final boolean truncated) {
        return switch (kind) {
            case Format.KIND_NONE -> switch (tag) {
                case Format.TAG_NULL -> new Value.Null();
                case Format.TAG_VOID -> new Value.Primitive("void");
                default -> new Value.Absent();
            };
            case Format.KIND_INLINE -> new Value.Primitive(renderer.render(Format.KIND_INLINE, tag, slot, tid, false));
            case Format.KIND_OBJECT_ID -> slot == 0
                    ? new Value.Null()
                    : new Value.Ref(slot, renderObjectId(slot));
            case Format.KIND_BLOB -> blobValue(slot, tid, truncated);
            default -> new Value.Primitive("<bad kind " + kind + ">");
        };
    }

    private Value blobValue(final long offset, final int tid, final boolean truncated) {
        try {
            return new Value.Str(recording.utf8At(tid, offset), truncated);
        } catch (IOException e) {
            return new Value.Primitive("<bad blob @" + offset + ">");
        }
    }

    private String renderObjectId(final long id) {
        return renderer.renderObjectId(id);
    }

    /** Split a JVM method descriptor into its parameter type descriptors, in order. */
    static List<String> paramDescriptors(final String desc) {
        List<String> out = new ArrayList<>();
        if (desc == null) {
            return out;
        }
        int open = desc.indexOf('(');
        int close = desc.indexOf(')');
        if (open < 0 || close < 0 || close < open) {
            return out;
        }
        int p = open + 1;
        while (p < close) {
            int start = p;
            char c = desc.charAt(p);
            while (c == '[') {
                p++;
                c = desc.charAt(p);
            }
            if (c == 'L') {
                int semi = desc.indexOf(';', p);
                if (semi < 0) {
                    break;
                }
                p = semi + 1;
            } else {
                p++;
            }
            out.add(desc.substring(start, p));
        }
        return out;
    }

    /** JVM local-variable slots a parameter of this descriptor occupies (2 for long/double). */
    static int slotSize(final String d) {
        if (d.startsWith("[")) {
            return 1;
        }
        return d.equals("J") || d.equals("D") ? 2 : 1;
    }

    // ---- internal reader access (IOException wrapped: navigation API is checked-exception-free) ----

    private ThreadIndex index(final int tid) {
        ThreadIndex ti = indices.get(tid);
        if (ti == null) {
            throw new IllegalStateException("no index for thread t" + tid);
        }
        return ti;
    }

    /** Package-private view of a thread's index, for white-box tests of §2.1 invariants. */
    ThreadIndex threadIndex(final int tid) {
        return index(tid);
    }

    private Rec rec(final int tid, final long idx) {
        try {
            return recording.recordAt(tid, idx);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Recording.Snapshot snapshotAt(final int tid, final long offset) {
        try {
            return recording.snapshotAt(tid, offset);
        } catch (IOException e) {
            return null; // dangling snapshot blob (truncated tail) → treat as no snapshot
        }
    }

    /** The declared schema version of the underlying recording (drives snapshot parsing). */
    public int schemaVersion() {
        return schemaVersion;
    }

    @Override
    public void close() {
        recording.close();
    }
}
