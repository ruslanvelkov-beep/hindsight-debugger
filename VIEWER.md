# Hindsight Viewer — Spec & Internal API — v1

The `viewer` module: an interactive, read-only, terminal "inspect-time" debugger over a
Hindsight recording. It replays a recording (FORMAT.md) into a live view — source with the
current line highlighted, call stack, locals, watches, record info — and lets the user step
forward and backward through execution. This document is LAW for the `engine`, `source`,
`highlight`, and `tui` packages; implement against it exactly so the pieces compose.

Module: `io.hindsight.viewer`, depends on `:reader` (parsing + `Recording`/`Rec`/`Site`/
`Format`/`ValueRenderer`) and `com.googlecode.lanterna:lanterna:3.1.3`. JDK 25, `-Xlint:all
-Werror` clean. Package layout:

- `io.hindsight.viewer.engine` — reconstruction (no terminal deps; unit-tested).
- `io.hindsight.viewer.source` — source-file resolution + line cache (no terminal deps).
- `io.hindsight.viewer.highlight` — Java syntax highlighter (no terminal/engine deps).
- `io.hindsight.viewer.tui` — lanterna UI (depends on engine/source/highlight).
- `io.hindsight.viewer.Main` — CLI entry, wiring.

## 1. CLI

`viewer --dir <recordingDir> [--source <root1:root2:...>]... [--repo <dir>]... [--tid N] [--seq N | --last]`

- `--dir` (required): a recording directory (`Recording.open`).
- `--source`: colon-separated source roots (each a directory that is the package root, i.e.
  `<root>/com/foo/Bar.java`). Repeatable.
- `--repo`: a directory tree under which every `*/src/main/java` and `*/src/test/java`
  directory is auto-discovered and added as a source root. Repeatable.
- `--tid`: initial thread to focus (default: the engine opens on the earliest exec record;
  §26.7 then lands on the first line-traced record of the chosen thread — an accepted
  `--tid`, else thread 1 — when it has line records).
- `--seq` / `--last`: the opening record (§26.7). Mutually exclusive.
- Missing source roots are non-fatal — the Source window shows a placeholder (§7.1).
- On a terminal smaller than 80×24, the UI shows a "terminal too small (need ≥80×24)"
  message and waits for resize or `q`.

## 2. Reconstruction engine (`engine`)

### 2.1 Indices (built once at open, from `Recording`)

Per thread `tid`, one pass over `recordAt(tid, 0..count)` builds a `ThreadIndex`:

- `depth[i]` — stack depth at record `i` (number of live frames), see below.
- `frameEnter[i]` — index of the METHOD_ENTER of the innermost live frame enclosing record
  `i`, or `-1` when `i` is outside any frame (records before the first ENTER / after the
  last EXIT at depth 0). A METHOD_ENTER's own `frameEnter` is itself; a METHOD_EXIT's is the
  frame it closes.
- `matchingExit[e]` — for a METHOD_ENTER at index `e`, the index of its matching METHOD_EXIT
  (or `count-1` sentinel when the frame never closed — truncated tail / live at shutdown).
- `parentEnter[e]` — for a METHOD_ENTER at index `e`, the enclosing frame's ENTER index at
  the moment `e` was pushed, or `-1` for a depth-0 frame.
- `lineIndices` — sorted `long[]` of the indices of LINE records in this thread (for binary
  search during stepping). Empty if the recording had `lines=false`.

Bracket-matching pass: maintain a stack of ENTER indices. On METHOD_ENTER push; on
METHOD_EXIT pop and record `matchingExit`. `frameEnter[i]` = current stack top before the
event is applied for non-ENTER/EXIT records; for an ENTER it's the ENTER itself (after push);
for an EXIT it's the frame being popped (before pop). `depth[i]` = stack size with the same
convention (ENTER counts itself; EXIT counts the closing frame). Unmatched EXIT (stack
empty) is tolerated: treat as depth 0, `frameEnter=-1`. Unmatched ENTER at EOF:
`matchingExit = count-1`.

Global object index (one pass over all threads, reusing the reader's scan where possible):

- `objectEvents` — `Map<Long objectId, long[] packed>` OR a parallel structure giving, per
  object id, the ordered list of `(seq, tid, index)` of its SNAPSHOT and FIELD_WRITE records.
  Ordered by `seq`. Used for object-tree reconstruction as-of a seq.
- `staticWrites` — for watch resolution: `Map<String "ownerBinary#field", List<(seq,tid,index)>>`
  built from FIELD_WRITE records whose site `isStatic()`.

Indices are built eagerly at open (a few passes over the mapped files; acceptable for an
offline tool). Expose progress-free; opening a multi-GB recording maps lazily via the reader.

### 2.2 Cursor

The engine holds a mutable **cursor**: `(int tid, long index)` that always points at a
**LINE record** (the "current line"). If a recording has no LINE records, the cursor points
at the first record of the chosen thread and stepping degrades to record-by-record (documented
in the info line). API:

```java
public final class Engine implements AutoCloseable {
    public static Engine open(Path recordingDir) throws IOException;  // no source deps

    // cursor
    public int currentTid();
    public long currentIndex();
    public long currentSeq();
    public Position position();            // immutable snapshot of the whole view state

    // navigation — each returns a StepResult (moved? + optional info message)
    public StepResult stepInto();          // F7
    public StepResult stepOver();          // F8
    public StepResult reverseStep();       // Shift-F7
    public StepResult reverseStepOver();   // Shift-F8
    public StepResult runToCurrentLine(boolean forward);   // Ctrl-R / Shift-Ctrl-R
    public StepResult selectThread(int tid);               // move cursor into another thread
    public boolean selectFrame(int frameIndex);            // 0 = outermost .. depth-1 innermost

    // reconstruction of the current position
    public List<Frame> stack();            // outermost first
    public int selectedFrame();
    public List<Local> locals(int frameIndex);   // as-of the cursor, for that frame
    public RecordInfo recordInfo();
    public List<Integer> threads();        // tids with recorded activity, ascending
    public String threadName(int tid);

    // object trees (lazy, as-of currentSeq)
    public ObjectNode objectTree(long objectId);            // root node for an id
    public List<ObjectNode> children(ObjectNode node);      // lazily materialised fields/elements

    // watches
    public WatchResult resolveWatch(String expression);     // as-of currentSeq
}
```

`StepResult` = `record StepResult(boolean moved, String message)`. When a step cannot move
(e.g. reverse step at the very first LINE), `moved=false` and `message` explains ("no earlier
line in this thread"). Run-to-line with no further occurrence: `moved=false`, message "no
next/previous appearance of this line."

### 2.3 Frames

`record Frame(int index, int enterIndex, int methodSiteId, String classBinary, String
methodName, String descriptor, String fileName, int currentLine, boolean isStatic)`.

`stack()` returns frames outermost→innermost for the cursor. The innermost frame's
`currentLine` = the cursor LINE record's line. An outer frame `Fk`'s `currentLine` = the line
of the last LINE record belonging to `Fk` (i.e. `frameEnter==Fk.enterIndex`) with index `<`
the ENTER index of `F(k+1)` and `≤` cursor index — the call-site line. If a frame has no LINE
record before its inner call (e.g. `lines=false`), fall back to the method site's first line.

`selectedFrame` defaults to the innermost (`depth-1`) on every cursor move. Selecting a frame
does NOT move the cursor; it only changes which frame the Source/Locals windows display.

### 2.4 Locals

`record Local(int slot, String name, String descriptor, Value value)`.

For frame `Fk` (ENTER at `e`, matching EXIT at `x`) as-of cursor index `p` (clamped to
`min(p, x)`; when the selected frame is an outer frame, `p` = that frame's current-line index
from §2.3, so locals reflect the call site, not the deepest position):

1. Seed from the ARG records immediately following `e` (arg slots → parameter names via the
   method site's descriptor + the LOCAL/VAR symbols where available; if names are unknown,
   name = `arg0`, `arg1`, …). Args map to the method's parameter slots (slot 0 = `this` for
   instance methods — surface `this` as a synthetic local when the frame is non-static).
2. Apply LOCAL_WRITE records `k` in `(e, p]` with `frameEnter[k]==e`, keeping the latest value
   per slot. The site gives the variable name (`memberSym`) and slot.
3. Result: one `Local` per slot that has a known value at `p`, ordered by slot. A slot with
   multiple names across disjoint scopes shows the name from the most recent write.

`Value` (shared with object trees / watches):

```java
sealed interface Value {
    record Primitive(String rendered) implements Value;   // ints, bool, char, float, ...
    record Str(String text, boolean truncated) implements Value;
    record Null() implements Value;
    record Ref(long objectId, String display) implements Value;  // display = renderObjectId(id)
    record Absent() implements Value;    // slot known to exist but no value recorded
}
```

Render primitives/strings/refs via the reader's `ValueRenderer` (reuse it — do not
reimplement tag decoding). `Ref` nodes are the expandable ones.

### 2.5 Object trees

`ObjectNode` is a lazily-expanded tree node:

```java
final class ObjectNode {
    String label;          // e.g. "balance = 1000", "[3]", "key -> value", "netHbar = HashMap#42"
    Value value;           // the node's own value (Ref/Primitive/Str/Null/Absent)
    boolean expandable;    // true iff value is a Ref that resolves to fields/elements
    // children materialised on demand via Engine.children(node)
}
```

`objectTree(id)` returns a root `Ref` node (label = `renderObjectId(id)`). `children(node)` for
a `Ref` value:

- Find the object's latest SNAPSHOT with `seq ≤ currentSeq`. Parse it (`Recording.parseSnapshot`
  via the reader) → a base map of field name → value (FIELDS form) OR indexed elements
  (MAP/SET/LIST/ARRAY forms, per FORMAT.md §3 schema 2/3).
- Apply FIELD_WRITE records for this id with `snapshot.seq < seq ≤ currentSeq` in seq order,
  overriding by field name (site `memberSym`). Field writes with no snapshot still populate
  fields (base = empty).
- Children order: FIELDS → declared field order from the snapshot then any write-only fields;
  MAP → `key -> value` pairs; SET/LIST/ARRAY → `[i] = value`. If the container was truncated
  at capture (snapshot `totalSize > captured`), append a synthetic leaf `…+N more`.
- If there is neither a snapshot nor any field write for the id (as-of currentSeq): a single
  leaf child with label `<No info available>` and `expandable=false`.
- Cycle guard: `children()` receives the set of ancestor object ids on the path (tracked by
  the TUI as it descends); an id already on the path renders as a leaf `↺ #id (cycle)`.
  (Engine exposes `children(ObjectNode node, Set<Long> ancestorIds)`.)

### 2.6 Stepping (LINE-record based; `depth[]` from §2.1)

Let `i` = cursor index, `d = depth[i]`, in thread `tid`. All searches are within `tid`.

- **stepInto (F7)**: next LINE index `j > i`. (Any depth — descends into callees.)
- **stepOver (F8)**: next LINE index `j > i` with `depth[j] ≤ d`. (Skips deeper callee lines;
  if the frame returns first, lands on the shallower caller line — i.e. step-out. So at the
  end of a frame F7 and F8 coincide.)
- **reverseStep (Shift-F7)**: previous LINE index `j < i`. (Any depth.)
- **reverseStepOver (Shift-F8)**: previous LINE index `j < i` with `depth[j] ≤ d`. (At the
  start of a frame the previous same-or-shallower line is in the caller, so it behaves like
  reverse step.)
- **runToCurrentLine(forward)**: with the current LINE's `(classBinary, line)` = `L`, the next
  (forward) / previous (backward) LINE index in `tid` whose site resolves to the same `L`.
  Used to hop between loop iterations / re-hits of the current line.
- **selectThread(tid)**: move the cursor to a LINE record in `tid` — the one with the greatest
  `seq ≤ currentSeq` if any, else the first LINE record in `tid`. Keeps the timelines roughly
  aligned when switching threads.

Every successful move resets `selectedFrame` to innermost and recomputes stack/locals.

### 2.7 Watches (`resolveWatch`)

`record WatchResult(String expression, ObjectNode root, String error)`. Supported v1 grammar
(no arithmetic/string ops):

- `#<id>` or `<id>` (decimal): object id → its tree root.
- `<ClassBinaryOrSimple>.<field>[.<field>...]`: resolve the leading `Class.field` as a **static
  field** (latest STATIC FIELD_WRITE with `seq ≤ currentSeq`, matching owner + field name;
  `Class` matches a CLASS symbol by binary name or unique simple name). If its value is a
  `Ref`, walk the remaining `.field` segments by field name through the object tree (as-of
  currentSeq). A missing segment → `error` "no field `x` on #id (as of seq S)".
- Anything else → `error` "unsupported expression (v1 accepts #id or Class.field.path)".

Watches are held by the TUI; the engine only resolves on demand (re-resolved on every cursor
move so values track the timeline).

### 2.8 RecordInfo

`record RecordInfo(long seq, int tid, String threadName, String typeName, String siteText,
long index, long threadRecordCount, long globalRecordCount, long approxEpochMillis, int depth,
boolean truncatedRecording)`. `siteText` = `Class.method (File:line)` for the cursor record;
`approxEpochMillis` from the nearest preceding TIME_SYNC in `tid`.

## 3. Source resolution (`source`)

`SourcePath`:

```java
final class SourcePath {
    static SourcePath of(List<Path> roots, List<Path> repos);   // repos auto-discover src roots
    Optional<List<String>> lines(String classBinary, String fileName);  // cached, 1 read per file
}
```

Resolve: `packagePath = classBinary.substring(0, lastDot).replace('.', '/')` (empty for
default package); candidate `<root>/<packagePath>/<fileName>` for each root, first existing
wins. Cache both hits (the file's lines) and misses. Repo auto-discovery: walk each `--repo`
dir, add every directory ending in `src/main/java` or `src/test/java` as a root (skip
`build/`, `.git/`, `node_modules/`). Reading is UTF-8; tabs expanded to 4 spaces at render
time by the TUI, not here (keep raw lines so column math is the TUI's).

## 4. Syntax highlighting (`highlight`)

`JavaHighlighter.highlight(String line) -> List<Span>` where `record Span(int start, int end,
Style style)` and `enum Style { PLAIN, KEYWORD, STRING, CHAR, NUMBER, COMMENT, ANNOTATION,
PUNCTUATION }`. Line-oriented but block-comment aware via a carried state:

```java
List<List<Span>> highlight(List<String> lines);   // handles multi-line /* */ comments
```

Spans are non-overlapping, cover the whole line (gaps = PLAIN), `start`/`end` are char offsets
(half-open). Keyword set = the Java language keywords + `true`/`false`/`null`. No semantic
analysis (no type highlighting) — purely lexical. Text blocks (`"""`) handled as multi-line
strings via carried state. The TUI maps `Style` → lanterna colors (§6).

## 5. TUI layout (`tui`)

Screen = `cols × rows`. Bottom **2 rows** reserved for info/help (§8). The rest (`rows-2`
high) splits into a left **Source** panel and a right **column**:

- Source width = `max(40, cols*13/20)` (≈65%), clamped so the right column is ≥ 34 cols.
- Right column stacks five bordered windows top→bottom with these height weights of the
  available `rows-2`: Threads 3, Stack 5, Locals 6, Watches 4, Record Info 3 (normalised;
  each ≥ 3 rows incl. border, giving the remainder to Locals when space is tight).
- Every window has a single-line border with a title; the **focused** window's border/title
  is bold + a distinct color. Titles include the focus letter, e.g. `Source [s]`,
  `Threads [t]`, `Stack [c]`, `Locals [l]`, `Watches [w]`, `Record Info [r]`.

Focus: exactly one window. Global keys always work; navigation keys go to the focused window.

## 6. Windows

### 6.1 Source `[s]`
Read-only. Shows the **selected frame's** file (§2.3) with line numbers in a left gutter
(width = digits of max line + 1). The frame's `currentLine` is highlighted full-width (a
background color; if the exec cursor's innermost line, a brighter highlight). Java syntax
highlighting (§4) applied to code; the gutter is dim. Horizontal scroll offset `hcol`
(columns hidden at left). Tabs expand to 4 columns. If the file is unresolved, show
`<source not found on source path>` plus `Class.method` and the line number, and disable
horizontal scroll.

Scroll model — viewport top line `top` (1-based), height `H`:
- Vertical auto-follow on cursor move: let `L` = highlighted line. If `L < top+5`, set
  `top = L-5`; if `L > top+H-1-5`, set `top = L-(H-1)+5`; clamp `top` to `[1, maxTop]` where
  `maxTop = max(1, fileLines-H+1)`. Line 1 only ever appears on the top window row; the last
  file line pins to the bottom row. When the file changes (frame/thread switch), center `L`:
  `top = clamp(L - H/2, 1, maxTop)`.
- Manual navigation (Source focused): Up/Down scroll `top` by ∓1; Left/Right change `hcol` by
  ∓4 (min 0); PageUp/PageDown and Ctrl-B/Ctrl-F move `top` by ∓(H-1); Home → `top=1,hcol=0`;
  End → `top=maxTop`; `g` → `top=1`; `G` → `top=maxTop`. Manual scroll does not move execution.

### 6.2 Threads `[t]`
Selectable read-only list of `threads()` as `t<tid>  "name"  (records)`, current thread
marked. Up/Down move selection; Enter = `selectThread(tid)` (moves the cursor into it and
refreshes everything).

### 6.3 Stack `[c]`
Selectable read-only list of `stack()` innermost-at-top (IntelliJ order) —
`Class.method(desc-simplified) (File:line)`. Up/Down move selection; Enter/selection =
`selectFrame`, which updates Source + Locals (does not move the cursor). The selected frame is
marked.

### 6.4 Locals `[l]`
Read-only object tree of `locals(selectedFrame)`. Top-level nodes = `this` (if any) + locals
by slot; each `Ref` is expandable. Up/Down move through visible nodes; Right/Enter expands (or
descends), Left collapses (or moves to parent). Expansion calls `Engine.children`. Tree glyphs:
`▸`/`▾` for collapsible, two spaces of indent per depth. A `<No info available>` child is a
non-expandable leaf.

### 6.5 Watches `[w]`
Read-write. Same tree rendering as Locals for each watch's resolved root. Keys: `a` (or
Insert) → input mode (a one-line editor at the window's bottom; type expression, Enter adds &
resolves, Esc cancels); `d` (or Delete) removes the selected top-level watch; Up/Down/Right/
Left navigate/expand as in Locals. While in input mode ALL keys go to the editor (focus letters
and stepping are suspended until Esc/Enter). Unresolved watches show `<error text>`.

### 6.6 Record Info `[r]`
Read-only key/value list from `recordInfo()`: seq, thread (`t<tid> "name"`), type, site,
position (`index+1 / threadRecordCount`, and global), depth, time (`approxEpochMillis` as
`HH:mm:ss.SSS`), and a `TRUNCATED` note when set. Not a tree; Up/Down scroll if it overflows.

## 7. Key map (global unless noted)

Read `KeyStroke` via lanterna. Modifiers via `isCtrlDown/isAltDown/isShiftDown`.

| Key | Action |
|---|---|
| `F7` / `Shift+F7` | step into / reverse step |
| `F8` / `Shift+F8` | step over / reverse step over |
| `Ctrl+R` | run to current line, forward |
| `Ctrl+R` with Shift, else `Alt+R` (fallback) | run to current line, backward |
| `s`/`t`/`c`/`l`/`w`/`r` | focus Source/Threads/Stack/Locals/Watches/Record Info |
| `Tab` / `Shift+Tab` | cycle focus forward / backward |
| Arrows, PageUp/Down, Home/End, `Ctrl+F`/`Ctrl+B`, `g`/`G` | navigation → focused window (§6) |
| `/` | open "find method calls" dialog (§9) |
| `.` | open "find object usage" dialog (§9) |
| `?` or `F1` | toggle help overlay |
| `q` / `Ctrl+C` | quit (restore terminal) |

Terminal reality: `Shift+Ctrl+R` is not encodable on most terminals, so reverse-run-to-line is
**`Alt+R`** (and also accepted as `Shift+Ctrl+R` where the terminal reports it). `Shift+F7/F8`
work on xterm-family terminals (iTerm2, most Linux terminals); where a terminal cannot report
the shift modifier on a function key, the fallback bindings **`F9` = reverse step** and
**`F10` = reverse step over** are also accepted. The help line documents whichever bindings are
active. Letter-focus keys and `g/G` are suspended while the Watches editor is in input mode.

## 8. Info / help lines (bottom 2 rows)

- Row `rows-1` (second from bottom): **status** — transient messages (`StepResult.message`,
  "no next appearance of this line", search results, errors). Cleared on the next successful
  action. Also shows the current position compactly: `seq S · t<tid> · Class.method · File:line`.
- Row `rows` (bottom): **help** — context-sensitive one-liner of the most useful keys for the
  focused window, always including `F7 step  F8 over  ⇧ reverse  ^R run-to-line  s/t/c/l/w/r
  focus  q quit`. If a message is too long for one row, the status row may wrap into this row
  (help then hidden) for that message.

## 9. Search dialogs

Modal centered box; Esc closes; ↑/↓ select; Enter jumps.

- **Find method calls (`/`)**: text field; matches METHOD_ENTER sites whose `Class.method`
  contains the query (case-insensitive). Results list distinct matching call sites with a hit
  count; Enter moves the cursor to the first (or next) ENTER of that site by seq (then to the
  nearest following LINE so the view sits on a line).
- **Find object usage (`.`)**: text field accepting an object id (`#id`/`id`) or a simple class
  name; results = records referencing that id (or first-seen of that class) with `seq · tid ·
  Class.method:line`; Enter moves the cursor to the nearest LINE at/after that record's seq in
  its thread.

All dialog outcomes and "no matches" are reported on the status row.

## 10. Rendering & robustness

- Use a `TerminalScreen` (alternate buffer via `startScreen()`), full-redraw per frame from a
  model (no incremental diffing beyond lanterna's own). `refresh()` after each render. Handle
  `doResizeIfNecessary()` at the top of the loop; re-layout on size change.
- Never throw out of the event loop: catch per-key exceptions, show the message on the status
  row, keep running. Always `stopScreen()` (restore the user's terminal) on exit — even on
  exception — via try/finally.
- The engine and source/highlight packages must have no lanterna imports (unit-testable
  headless). The tui package must contain all lanterna usage.

## 11. v1.1 additions & changes (authoritative — supersedes conflicting text above)

Eight changes. Where this section conflicts with §§5–10, this section wins.

### 11.1 Scroll margin 5 → 15
`ScrollMath.MARGIN = 15` (was 5). Because the Source window can be shorter than `2*MARGIN+1`,
`autoFollow` MUST use an effective margin `m = min(15, max(0, (height-1)/2))` so the two
edge conditions never cross and the view cannot oscillate. `center()` is unchanged. Update the
ScrollMath test for the 15 margin and add a small-window (`height ≤ 30`) case.

### 11.2 Return-to-current-line — `o` (origin)
Global key `o`: return the Source Code window to *following execution* — clear any browse-mode
target (§11.8), set the displayed file to the selected frame's file, and re-center on its
current line via `ScrollMath.center` (reset `hcol=0`). Does not move the execution cursor. Show
`o origin` on the help line. Works from any focus (does not change focus).

### 11.3 Swap `s`/`c`; rename main window to "Source Code"
Focus letters change: **`c` = Source Code**, **`s` = Stack**. The main window title is now
`Source Code [c]`; the Stack window title is `Stack [s]`. Update all titles, the focus-switch
`switch`, the help line, and the help overlay accordingly. Everything else about those windows
is unchanged. (`t`/`l`/`w`/`r` unchanged. Tab-cycle order unchanged.)

### 11.4 Horizontal scroll for every window
Every window (not just Source Code) keeps its own horizontal offset `hcol` and clips each
rendered row to `[hcol, hcol+innerWidth)`; a row longer than the window no longer truncates
permanently — it scrolls. Keys:
- **`<` / `>`** (Shift+comma / Shift+period — plain characters, available on every terminal):
  scroll the focused window left / right by 4 columns (min 0). Works in ALL windows including
  the tree windows, so tree windows always have a working horizontal scroll.
- Plain **Left/Right** also horizontally scroll by 4 in the non-tree windows (Source Code,
  Threads, Stack, Records) — unchanged for Source Code; newly enabled for the lists.
- In the tree windows (Locals, Watches) plain **Left/Right keep their tree meaning**
  (collapse/parent, expand/child); use `<`/`>` there for horizontal scroll.
`hcol` resets to 0 when a window's content is replaced (file change, thread/frame switch,
new watch, cursor move for Records). `<`/`>` are suspended while the Watches editor is in input
mode.

### 11.5 Record Info window → navigable history trail (title `Records [r]`)
Replace the key/value Record Info content with a **history trail**: the ordered list of records
the cursor has *landed on* through user navigation. (The compact current position already lives
on the status row, §8, so no detail is lost.)
- Model: a bounded ring of ≤ **1024** `HistoryEntry(int tid, long index, long seq, int line,
  String classBinary, String method, String module, String pkg)`. When full, the oldest entry
  is dropped.
- Populate: append the cursor's new position after every user navigation that *lands*
  (stepInto/stepOver + reverses, exitFrame `e`/`E`, runToCurrentLine, selectThread, search-jump,
  file-open-jump). Append exactly ONE entry per action — step-over that internally skips many
  records still yields one entry. Do NOT append for intermediate engine steps, for `selectFrame`
  (view-only, no cursor move), or when selecting an entry from this list. Skip an append that
  exactly equals the current last entry (no consecutive duplicates).
- Render one line each: `seq <N> · t<tid> · line <L> · <Class.method> · <module> · <pkg>` where
  `pkg` = the class's package (binary name up to the last dot, or `(default)`), `module` =
  `SourcePath.module(classBinary,fileName)` best-effort (the owning source-project dir, e.g.
  `hedera-app`) or `?` when the source is unresolved. Lines horizontally scrollable (§11.4).
- Navigate: Up/Down select; Enter = `engine.seek(entry.tid, entry.index)` to jump the cursor
  back to that record (this does NOT append a new entry). Selection defaults to the newest.

### 11.6 Exit current frame — `e` / `E`
Two global keys operating on the innermost (execution) frame of the cursor:
- **`e`**: `engine.exitFrameForward()` — move to the frame's return: the greatest index `j` that
  is a LINE record with `frameEnter[j] == frameEnter[cursor]` and `j ≤ matchingExit[enter]`.
  If the cursor is already on that line (or the frame has no LINE record), no-op (moved=false,
  message "already at frame return").
- **`E`** (Shift+e): `engine.exitFrameBackward()` — move to the frame's entry: the least such
  `j`. No-op with "already at frame entry" when already there.
Both reset the selected frame to innermost and append one history entry when they move.

### 11.7 Browse method calls — regex (`/`)
The `/` dialog becomes a regex browser over the methods the recording actually entered. Engine
additions:
- `List<MethodSite> methodSites()` — distinct METHOD sites seen as METHOD_ENTER, each
  `record MethodSite(int siteId, String classBinary, String methodName, String descriptor,
  long invocationCount)`, sorted by `classBinary` then `methodName`. Built once, cached.
- `List<Call> callsOf(int siteId, int cap)` — the first `cap` invocations
  `record Call(long seq, int tid, long index)` of that site, ascending `seq`.
Dialog: a text field interpreted as a **regex** (case-insensitive, `Pattern.CASE_INSENSITIVE`;
an invalid regex is reported on the status row and matches nothing). It matches against each
site's `Class.method` string. Results are individual invocation rows (flatten the matching
sites' `callsOf`, ascending seq, capped at 500 total with a "showing first 500 of N" note)
shown as `seq <N> · t<tid> · <Class.method>`. Enter = `engine.seek` to the nearest LINE at/after
that call's record in its thread (append one history entry). Empty query lists nothing; ↑/↓
select; Esc closes.

### 11.8 Open a project source file — `F`
Global key `F` (Shift+f) opens a modal that accepts either a filesystem path, a path relative to
a source root, or a dotted class name, optionally suffixed with `:<line>`. Resolution:
- dotted name `a.b.C[:line]` → `SourcePath.resolveClass("a.b.C","C.java")`;
- a path ending in `.java` → try as absolute, then relative to each source root;
- on success, the Source Code window enters **browse mode**: it shows that file (line numbers,
  Java highlighting, horizontal scroll) positioned at the given line (centered), with NO
  execution highlight, and focus moves to Source Code. `o` (§11.2) leaves browse mode and
  returns to following execution. Browse mode is source-only — stack/locals/watches/records
  keep reflecting the (unchanged) execution cursor.
Engine is not involved. `SourcePath` gains `Optional<List<String>> linesOfFile(Path)`,
`Optional<Path> resolveClass(String classBinary, String fileName)`, and
`Optional<String> module(String classBinary, String fileName)`.

### 11.9 Engine API additions (summary)
```java
StepResult seek(int tid, long index);           // absolute jump to a LINE record (history/search)
StepResult exitFrameForward();                   // 'e'
StepResult exitFrameBackward();                  // 'E'
List<MethodSite> methodSites();                  // distinct entered methods (cached)
List<Call> callsOf(int siteId, int cap);         // a site's first `cap` invocations by seq
record MethodSite(int siteId, String classBinary, String methodName, String descriptor, long invocationCount) {}
record Call(long seq, int tid, long index) {}
```
`seek`/`exitFrame*` return `StepResult(moved,message)` and reset the selected frame to
innermost on a move, exactly like the other navigation methods.

### 11.10 Help / info line updates
The status row keeps the compact position and adds nothing. The help row base becomes (fitting
the width, elide as needed):
`F7 step  F8 over  ⇧/F9 F10 rev  e/E exit-frame  ^R run  o origin  / calls  . obj  F file  c/s/t/l/w/r focus  q quit`.

## 12. v1.2 changes

Authoritative over conflicting earlier text (§5, §6.1, §8, §11.4, §11.10). Everything not
mentioned here is unchanged.

### 12.1 Watches window removed
The Watches window is removed from the TUI entirely: no watch list, no `w` focus letter, no
`a`/`d` keys, no inline input mode (and therefore no "`<`/`>` suspended while editing" rule).
The right column becomes **four** stacked windows, top to bottom: Threads, Stack, Locals,
Records. `Engine.resolveWatch` stays as tested headless API (the engine is a library; only the
UI surface is dropped).

### 12.2 Right-column heights
The §5 height weights change from `{3, 5, 6, 4, 3}` (Threads, Stack, Locals, Watches, Records)
to `{5, 5, 6, 5}` (Threads, Stack, Locals, Records): the freed Watches weight goes to Threads
and Records, which each grow from 3 to 5, while Stack and Locals keep their share. All other §5
rules are unchanged (3-row minimum per window, Locals absorbs the rounding remainder, trimming
from the tallest below the screen minimum).

### 12.3 Darker selected-frame line highlight
`FRAME_LINE_BG` (the current-line background of a selected **non-innermost** frame, also reused
as the browse-mode line marker, §11.8) changes from ANSI bright-black — a light gray on which
most syntax foregrounds (white, bright-black comments, yellow) became unreadable — to xterm-256
indexed **236** (`#303030`): clearly darker than every syntax foreground yet still visible
against the default background. This is the palette's single deliberate exception to the
ANSI-16-only rule (256-colour support is ubiquitous; `EXEC_LINE_BG` stays ANSI blue).

### 12.4 Quit confirmation
`q` no longer quits immediately: it opens a small centered confirm box ("Really quit the
viewer?"). `y`/`Y`/Enter quits; **any** other key cancels it (status `quit cancelled`). While
the box is open all other dispatch is suspended. `Ctrl+C` remains an immediate quit (emergency
exit), as does the terminal's EOF; on the too-small screen (§1) `q` also quits immediately —
there is no room for the box. Inside the search dialogs `q` keeps typing into the query as
before; from the help overlay `q` closes the overlay and opens the confirm box. The help texts
say `q quit (y confirms)`.

## 13. v1.3 additions & changes

Authoritative over conflicting earlier text (§6.1, §8, §9, §11.7, §11.10, §12).
Everything not mentioned here is unchanged.

### 13.1 Modal-list scroll fix
Every modal result list (the `/` and `.` dialogs, and the new §13.3/§13.5 windows) must
scroll like the main list windows: the selection moves one row at a time, and the list
scrolls ONLY when the selection would leave the visible rows (top or bottom edge).
Concretely: each dialog keeps a PERSISTENT scroll offset (reset to 0 whenever the
query/mode changes or the dialog opens), adjusted incrementally from its previous value —
never recomputed from 0 each frame (the v1.1 bug that pinned a moving-up selection to the
bottom row until the top of the list was reached).

### 13.2 Instance browse — `/` mode toggle
The `/` dialog gains a second mode; `Tab` inside the dialog toggles it.
- **calls** (§11.7, unchanged). Title: `Browse method calls [/]  ⇥ instances`.
- **instances**. Title: `Browse instances [/]  ⇥ calls`. The regex (case-insensitive,
  `find()`) matches recorded instances' runtime class binary names (engine
  `classSites()`). Results are the matching classes' instances flattened ascending by
  `seq`, capped at 500 with a `showing first 500 of N` note; rows:
  `seq <N> · t<tid> · <SimpleClass>#<id>`.
The toggle keeps the typed query, recomputes results, resets selection and scroll. Enter
on an instance row does NOT move the execution cursor: it closes the dialog and opens the
instance explore window (§13.3) for that object id. Enter in calls mode is unchanged.

### 13.3 Instance explore window
A modal overlay presenting one instance as an expandable object tree resolved as-of the
CURRENT cursor seq; the execution cursor does not move while it is open and the right
windows keep reflecting the execution position. Title: `<SimpleClass>#<id>  (as of seq <S>)`.
Rows, top to bottom:
1. `[jump to creation]` — action row;
2. `[close]` — action row;
3. the object tree: root = `engine.objectTree(id)`, lazily expanded via
   `engine.children`, rendered like Locals (`▸`/`▾` glyphs, indentation).
Keys: ↑/↓ move the selection over ALL rows (actions + tree, §13.1 scrolling); on tree
rows →/↵ expand (when expandable), ← collapse; `<`/`>` horizontal scroll; Esc closes.
Enter on `[close]` closes. Enter on `[jump to creation]`: closes the window and moves the
cursor to the instance's `OBJECT_FIRST_SEEN` position — `engine.creationOf(id)`, then
seek to its `(tid, index)` and advance to the nearest FOLLOWING LINE in that thread
(§11.7 jump pattern), appending exactly ONE history entry; status:
`jumped to creation of <SimpleClass>#<id> (seq <N>)`. If `creationOf` returns nothing
(defensive), a status message instead and the window stays open.

### 13.4 Source-line cursor
The Source Code window gains a cursor line (the target for §13.5 and future line
commands). Rendering: the cursor line's GUTTER cells (the line number) use the selection
colours (SELECTION_BG/SELECTION_FG); the code cells keep their normal colours and
highlights, so the §6.1 exec/frame line highlight stays visible when the cursor sits on
it. The cursor renders only while the Source window is FOCUSED. Keys while Source is
focused (replacing the previous plain-scroll semantics):
- ↑/↓ move the cursor one line; the view scrolls only when the cursor would leave the
  visible rows (no margin — §13.1 semantics);
- PgUp/PgDn (and ^B/^F) move the cursor by a page, view following;
- Home / `g` → cursor to line 1; End / `G` → cursor to the last line (view follows);
- ←/→ and `<`/`>` keep their §11.4 horizontal-scroll meaning.
The cursor resets to the highlighted execution/frame line after every navigation that
moves the cursor or changes the shown file (steps, seeks, frame select, thread switch,
`o` origin), and to the requested line when a file opens in browse mode (§11.8).

### 13.5 Variable history — `v`
With the Source window focused, `v` inspects the cursor line (§13.4) of the SHOWN file
(exec or browse mode alike):
1. Parse the line for a class-variable (field) declaration with a pure lexical heuristic
   (`FieldDecl.parse(String) -> List<String>` candidates, unit-tested): consider the text
   before `=` (else before `;`); strip `//` comments; reject lines whose segment contains
   `(` or `)` (methods/calls) or starts with a control keyword (`if`, `for`, `while`,
   `return`, `switch`, `case`, `throw`, `new`, `else`, `do`, `try`, `catch`, `import`,
   `package`, `class`, `interface`, `enum`, `record`, `assert`, `yield`); require at
   least a type token AND a name token (≥ 2 identifier-ish tokens, `<...>`/`[]`
   stripped); support multi-declarators split on commas at angle-bracket depth 0
   (`int a, b;` → `a`, `b`). Candidates are the declared identifier(s), in order.
2. No candidate parses → status row `no class variable definition on this line`; no window.
3. For each candidate in order (first with hits wins):
   `engine.fieldWrites(shownFileName, name, 500)` — every FIELD_WRITE whose site field
   name equals the candidate AND whose owner class belongs to the shown file (the
   OUTERMOST simple name of the owner binary — text after the last `.`, cut at the first
   `$` — plus `.java` equals the file name; inner/nested classes thus match their
   enclosing file).
4. Hits → modal `Variable history [v]`, rows ascending by seq (§13.1 scrolling):
   `seq <N> · t<tid> · <Owner>#<oid>.<field>: <old> → <new>  (<File>:<line>)`
   (statics omit `#<oid>`). Enter = jump: seek to the record's `(tid, index)`, then
   position on the nearest LINE AT/BEFORE it (the write belongs to that line; fall
   FORWARD only if no LINE precedes), append one history entry. Esc closes. Note
   `showing first 500 of N` when capped.
5. Candidates parsed but zero hits → status row `no recorded writes for '<name>'`; no window.
Help: the Source-focus help line gains `v var-history`; the overlay gains a `v` row and
the `/` Tab toggle.

### 13.6 Engine API additions
```java
List<ClassSite> classSites();                    // distinct FIRST_SEEN runtime classes + instance counts,
                                                 // sorted by classBinary; built once, cached; immutable
List<Instance> instancesOf(String classBinary, int cap);  // that class's instances ascending seq;
                                                 // empty for unknown class or cap <= 0
Instance creationOf(long objectId);              // the id's OBJECT_FIRST_SEEN position, or null
List<FieldWriteHit> fieldWrites(String fileName, String fieldName, int cap); // §13.5 rule, ascending seq
record ClassSite(String classBinary, long instanceCount) {}
record Instance(long objectId, long seq, int tid, long index) {}
record FieldWriteHit(long seq, int tid, long index, long ownerId, String ownerClass,
                     String fieldName, boolean isStatic, String before, String after,
                     String fileName, int line) {}
```
All built by ONE additional lazy full pass over all threads' records (one shared builder,
cached), mirroring `methodSites()`. `before`/`after` are rendered display strings
(ValueRenderer; §2.3 runtime-tag rules for ref-typed fields, blob strings resolved).
`fileName`/`line` come from the write site. The pass also records, per object id, its
FIRST_SEEN `(seq, tid, index)` for `creationOf`.

### 13.7 Pure TUI helpers (unit-tested, terminal-free)
- `InstanceBrowse` — §13.2 regex filter + flatten + cap (mirrors `MethodBrowse`).
- `FieldDecl` — §13.5 declaration parser.
- The §13.1 scroll rule is exercised through the existing `adjustScroll` with persistent
  state; pin it with a test if `adjustScroll` moves/changes.

## 14. v1.4 additions & changes

Authoritative over conflicting earlier text (§13 above all). Everything not mentioned is
unchanged. Requires reader/engine support for schema-4 recordings (FORMAT.md §2.1/§3
STACK_SNAPSHOT); schema ≤ 3 recordings must keep working with every feature that does not
need captures.

### 14.1 Instance search by id
In the `/` dialog's instances mode the query additionally accepts:
- `<regex>#<digits>` — the class part is matched as before (case-insensitive `find()`
  over class binary names) AND only the instance whose objectId equals `<digits>` is
  listed (e.g. `Bytes#240`);
- bare `#<digits>` — that objectId in ANY class.
A `#` NOT followed by digits-only text keeps being treated as part of the regex. The cap
note logic is unchanged. Implemented in the pure `InstanceBrowse` helper (unit-tested).

### 14.2 In-place instance exploration; `g` = go to first mention
The §13.3 explore window is REMOVED. In instances mode the result rows themselves become
expandable object-tree roots, Locals-style, inside the dialog list:
- each instance row (label unchanged: `seq <N> · t<tid> · <SimpleClass>#<id>`) expands
  lazily via `engine.objectTree(id)` / `engine.children`, resolved as-of the CURRENT
  cursor seq; ↑/↓ move over the flattened rows, →/↵ expand (on expandable rows),
  ← collapse, `<`/`>` hscroll, §13.1 scrolling;
- `g` on ANY row acts on the selected row's OWNING instance (the root of its subtree):
  the cursor moves to the instance's first mention — `engine.firstMentionOf(id)`, seek to
  its `(tid, index)`, advance to the nearest FOLLOWING LINE (§11.7 jump pattern), ONE
  history entry, status `jumped to first mention of <SimpleClass>#<id> (seq <N>)` — and
  the dialog STAYS OPEN (the screen behind it re-renders at the new position; expanded
  trees re-resolve as-of the new seq). The user closes the dialog with Esc when done.
- Enter therefore no longer jumps/closes in instances mode — it is the tree expand
  toggle. Calls mode is entirely unchanged (Enter jumps and closes).
- Editing the query or toggling the mode rebuilds the list and drops expansion state.
Dialog hint (instances mode): `↑↓ move  →/↵ expand  ← collapse  g first-mention  Esc close`.

### 14.3 "Creation" renamed to "first mention"
`OBJECT_FIRST_SEEN` marks when the recorder first NOTICED an object — often long after
its construction (recording starts mid-life, nested discovery, ...). All API and UI text
changes accordingly: engine `creationOf(long)` is RENAMED to `firstMentionOf(long)`
(same contract), and no user-facing text may say "creation".

### 14.4 Info-line type information
The status row's position part becomes focus-sensitive:
- Source Code focused (or any other window not listed below): unchanged — the compact
  record info (`seq · t · site`).
- Locals focused: the FULL type of the selected row's value, e.g.
  `com.hedera.pbj.runtime.io.buffer.Bytes#240` for refs (runtime class binary name via
  the recording's first-seen class, `?#id` when unknown), `java.lang.String` for
  strings, the primitive tag name (`int`, `boolean`, ...) for inline values, `null` /
  `void` / `<absent>` otherwise. Unrecorded-frame placeholder rows show nothing special.
- `/` dialog open in instances mode: the full class binary name + `#id` of the selected
  row's owning instance.
A transient status message still takes precedence exactly as today (`msg — position`).

### 14.5 Unrecorded (grey) stack frames — schema 4
For schema-4 recordings the engine merges the reconstructed (recorded) stack with the
thread's nearest STACK_SNAPSHOT at/before the cursor:
- `Frame` gains a `boolean recorded` component. `stack()` returns, outermost-first: the
  UNRECORDED caller frames (grey), then the recorded frames exactly as today (innermost
  last). Unrecorded frames carry classBinary/methodName/fileName/line from the capture,
  `enterIndex = -1`, `descriptor = ""`, `isStatic = false`.
- Merge rule: let C be the capture's frames innermost-first and R_out the OUTERMOST
  recorded frame. Scan C from innermost for the first frame equal to R_out by
  (classBinary, methodName); if found at k, the unrecorded frames are C[k+1..] (R_out's
  callers). If the recorded stack is empty, ALL of C is unrecorded context. If no match,
  ALL of C is appended as unrecorded context below the recorded frames (stale-but-useful
  approximation; exactness is only guaranteed at the capture record itself). No capture
  at/before the cursor (or schema ≤ 3) → no unrecorded frames.
- `selectFrame` accepts unrecorded frames. `locals(f)` of an unrecorded frame is empty.
- TUI: unrecorded rows render DIM (grey), same layout (`Class.method (File:line)`); they
  are selectable; selecting one drives the Source window through the normal frame path
  (SourcePath resolution by classBinary/fileName, centered on the frame line with the
  frame-line highlight) and the Locals window shows `(unrecorded frame — no locals)`.
  The exec highlight and stepping semantics are untouched (stepping still operates on
  the execution cursor, not the selected frame).

### 14.6 Engine API deltas
```java
record Frame(..., boolean recorded)              // §14.5; all construction sites updated
Instance firstMentionOf(long objectId);          // renamed from creationOf (§14.3)
```
The engine parses STACK_SNAPSHOT records during index construction (per-thread ascending
capture positions) and exposes the merge only through stack() — no new public stack API.
The reader owns the STACK payload parsing (`CapturedFrame` records).

## 15. v1.5 additions & changes

Authoritative over conflicting earlier text (§13, §14). Everything not mentioned is
unchanged. All items are TUI-side except where noted.

### 15.1 Instance matching: simple name by default
In instances mode, a query containing NO `.` and NO `$` is matched (case-insensitive
`find()`) against each class's SIMPLE name — the binary name's segment after the last
`.` and after the last `$` — so `VirtualMapStateImpl` no longer matches its inner class
`...VirtualMapStateImpl$MerkleReadableStates`. A query containing `.` or `$` matches the
FULL binary name as before (explicit qualification intent). The §14.1 `#<id>` forms
compose with this rule (the class part follows it). Pure `InstanceBrowse`, unit-tested.

### 15.2 Search-dialog focus model
The `/` dialog gains two explicit focus states:
- **QUERY focus** (initial): the terminal cursor sits on the query line; printable keys
  edit the query, Backspace deletes; **Ctrl-Backspace or Ctrl-U** clears the query, the
  results and the §15.3 count line in one stroke. Pressing ↑ or ↓ switches to LIST focus.
- **LIST focus**: the terminal cursor is hidden. ↑/↓ move the selection; **Ctrl-F/Ctrl-B**
  (and PgDn/PgUp) page; **`g`/`G`** jump to the first/last row; in instances mode →/↵
  expand and ← collapse tree rows, and **`f`** jumps to the selected row's owning
  instance's FIRST MENTION (the §14.2 semantics previously on `g`: one history entry,
  dialog stays open); in calls mode ↵ jumps and closes (unchanged). Pressing **`/`**
  returns to QUERY focus (cursor reappears on the query line, editing resumes). Other
  printable keys are ignored in LIST focus.
Esc closes the dialog from either focus. The hint line reflects the active focus. §14.2's
list-active heuristic is superseded by this explicit model.

### 15.3 Result count line
A DIM (grey) line directly under the query shows `<N> results` — or
`showing first 500 of <N>` when capped (this replaces the previous bottom note) — and is
absent whenever there are no results (empty query, no matches, or after Ctrl-Backspace).

### 15.4 Numbered results
Result rows are numbered `1.` .. `N.` in both modes (in instances mode only the ROOT
rows carry numbers; expanded child rows stay indented and unnumbered under their root).

### 15.5 Dialog state persistence
The `/` dialog's complete state — mode, query text, results, selection, scroll,
expansion (instances mode) and focus state — survives Esc: reopening with `/` restores
it exactly. Expanded trees keep resolving as-of the CURRENT cursor seq at render time,
so their contents may legitimately differ after the cursor moved. The `.` and `F`
dialogs and the `v` window keep their transient behaviour.

### 15.6 Info line: Stack focus
With the Stack window focused, the status row's position part shows the SELECTED frame's
fully-qualified name: `<full.binary.Class>.<method>` (unrecorded/grey frames included).
The §14.4 Locals and instances-dialog rules are unchanged; every other focus keeps the
compact record info. (The current seq stays reachable via the Records window's newest
entry.)

### 15.7 Source Code title context
In exec mode the Source window title becomes
`Source Code [c]          <SimpleClass>.<method>` — the SELECTED frame's simple class
name and bare method name, separated from the fixed title by ten spaces — clipped by the
border as usual. Browse mode keeps `Source Code [c] — <fileName>`.

### 15.8 Empty-payload rendering (engine/recorder alignment)
Recorder ≥ v1.5 emits EMPTY FIELDS payloads for genuinely field-less classes (FORMAT.md
§3): the engine/TUI must render such an object as an empty expansion (`{}`-like: the
node is non-expandable or expands to nothing) rather than `<No info available>`, which
now specifically means "no snapshot observed at/before this seq".

## 16. v1.6 additions & changes

Authoritative over conflicting earlier text (§13.5, §15). Everything not mentioned is
unchanged. All items are TUI-side except the §16.7 engine additions.

### 16.1 Variable-history window overhaul
The `v` window (§13.5) changes:
- **Width**: 75% of the terminal width — `w = clamp(cols*3/4, 40, cols-2)` (was a fixed
  ≤74). Height rule unchanged.
- **Horizontal scroll**: Left/Right arrows scroll it (step `HSTEP`); `<`/`>` no longer do
  (they are freed here). ↑/↓ still move the selection.
- **Paging/jumps** like the list windows: `Ctrl-F`/`Ctrl-B` (and PgDn/PgUp) page by
  `viewH-1`; `g`/`Home` → first row, `G`/`End` → last row.
- **Title**: `Variable history [v] — <N> entries · <scope>` where `<scope>` is
  `this <SimpleClass>#<id>` when the this-instance filter is active, else `all instances`.
  `<N>` is the number of rows currently shown (post-filter, pre-cap-note).
- **Numbered rows**: each row is prefixed `1.` .. `N.` (right-aligned to the row count's
  width, then the existing `seq … · … : old → new (File:line)` text), participating in the
  Left/Right hscroll clip.
- **`this`-instance filter (Tab)**: `Tab` toggles between *all instances* (every recorded
  write of the field in the file — the §13.5 behaviour) and *this instance only* (writes
  whose owner object id equals the ENCLOSING instance — the selected frame's `this` at the
  moment `v` was pressed). The window remembers `fileName`, `fieldName` and `thisId`;
  toggling re-queries via the §16.7 owner-filtered engine calls, resets selection/scroll,
  and keeps the cap semantics (`showing first 500 of N` reflects the active scope). When
  there is no enclosing instance (`thisId == 0`: static context, or browse mode), Tab is a
  no-op and the title scope stays `all instances (no enclosing this)`.
- Statics still render without `#id`; the this-filter naturally lists nothing for a static
  field (owner id 0 ≠ any instance id) — acceptable, the toggle is for instance fields.
Hint line updated: `↑↓ select  ←→ hscroll  ^F/^B pg  g/G ends  ⇥ this/all  ↵ jump  Esc`.

### 16.2 Collapse Locals on frame switch / cross-thread jump
Whenever the SELECTED STACK FRAME changes (`selectStackRow`) or a navigation lands in a
DIFFERENT thread than the one previously shown (any `afterMove` where
`engine.currentTid()` differs from the last shown tid — thread select, Records-list jump,
search/first-mention/variable jumps into another thread), all Locals tree expansion is
reset (`localsTree.clearExpansion()`), so the tree opens fresh rather than replaying a
stale expansion path against unrelated locals. A plain in-thread step does NOT collapse
(the reader is following state and wants the tree kept open).

### 16.3 Records: jump to a record number — `j`
With the Records window focused, `j` opens a small centered modal `Jump to record`:
- a single input line accepting decimal digits (the global `seq` shown in the trail);
  Backspace edits; Esc closes.
- On Enter: resolve the seq via §16.7 `findSeq`. If found, close the dialog, position the
  cursor on the nearest LINE at/before that record in its thread (the §13.5 landing rule),
  `afterMove` + one history entry, status `jumped to seq <N> (t<tid>)`. If NOT found, the
  dialog stays open and shows, on its own line, `no record with seq <N>` (dim/red); further
  typing clears the error and lets the user retry.
- Non-digit printables are ignored. The dialog consumes all keys while open (Ctrl-C still
  the emergency quit).

### 16.4 Source Code in-file search — `Alt-F`
With the Source Code window focused (exec OR browse mode), `Alt-F` (Character `f` with
`isAltDown`, mirroring the existing Alt-R) enters an incremental in-file search:
- **Rendering**: the search field is drawn ON the Source title row, OVERWRITING the title,
  starting at the title's own start column (`rect.col()+2`, where the class name begins)
  and ending at `rect.col()+rect.width()-3` (the `]` sits two columns before the corner).
  Style: black background, YELLOW letters; text is `[ Search: <query>]` right-padded to the
  field width. When the current query has NO match the letters turn RED; with a match (or an
  empty query) they stay yellow. The window border/corners are preserved.
- **Matching**: case-insensitive substring over the shown file's lines (exec source or
  browse lines). Occurrences are every line containing the query (one hit per line;
  column of the first occurrence on that line is tracked for highlight). On each edit the
  "current" occurrence is the FIRST at/after the current cursor line (wrapping to the top),
  and the view scrolls so it is visible (`ScrollMath.center`), `srcCursor` set to it.
- **Navigation**: `Enter` = next occurrence, `Shift-Enter` OR `Ctrl-Enter` = previous
  (whichever the terminal delivers distinctly — `KeyType.Enter` with `isShiftDown`/
  `isCtrlDown`); as a universal fallback that works on every terminal, `↓` = next and `↑` =
  previous. Both directions CYCLE (next past the last → first; previous before the first →
  last). Each move re-centers and updates `srcCursor`.
- **Highlight**: the current occurrence's line carries the cursor/frame-line highlight
  (§13.4); additionally the matched substring columns are shown reverse-video when the
  match is within the visible horizontal window (best-effort — line highlight is the firm
  requirement).
- **Exit**: `Esc` leaves search mode and restores the normal title; the cursor stays on the
  last focused occurrence. Backspace edits the query; an empty query clears the
  match/highlight but stays in search mode.
Help overlay + Source-focus help line gain `Alt-F search  (↵ next  ⇧↵/↑↓ prev)`.

### 16.5 Freed keys
`<`/`>` retain their §11.4 per-window horizontal-scroll meaning in every window EXCEPT the
variable-history window (§16.1), which now uses the arrows.

### 16.6 (reserved)

### 16.7 Engine API additions
```java
List<FieldWriteHit> fieldWrites(String fileName, String fieldName, long ownerId, int cap);
long countFieldWrites(String fileName, String fieldName, long ownerId);
    // ownerId == 0 → all instances (the existing 2-arg/§13.5 behaviour, which now delegates
    // to these with ownerId 0); ownerId != 0 → only writes whose record target()==ownerId.
long frameThisId(int frameIndex);
    // the `this` object id of that stack frame (slot 0), or 0 for a static/unrecorded frame
    // or when no `this` is resolvable — reuses the §2.4 this-derivation locals() already does.
record SeqLocation(boolean found, int tid, long index) {}
SeqLocation findSeq(long seq);
    // locate the record with global seq `seq`: per-thread binary search (each thread file is
    // seq-ascending), returning the first thread carrying it; found=false when no record has
    // that seq. Does NOT move the cursor.
```
The two existing `fieldWrites`/`countFieldWrites` overloads keep their signatures and
delegate with `ownerId = 0`.

## 17. v1.7 additions & changes

Authoritative over conflicting earlier text (§2, §11, §13.5, §14.2/§14.5, §16). Items
17.4 needs a recorder ≥ v1.7 recording (older recordings keep the argN fallback);
everything else works on existing schema-4 recordings.

### 17.1 Execution-record stepping (engine)
Stepping stops ONLY at records that change the execution position — the **exec set**:
`LINE`, `METHOD_ENTER`, `METHOD_EXIT`. Metadata records (first-seen, snapshots,
thread-start, time-sync, stack captures) and value records (ARG, LOCAL_WRITE,
FIELD_WRITE, BRANCH) are never step landings — **except** (§28.9) a `BRANCH` record in a
branch-mode frame (one with no `LINE` records of its own), which joins the exec set so
branch-resolution recordings step naturally; landing legality is therefore per-index
(`Engine.isExecLanding`), not per record type.
- `ThreadIndex` gains `execIndices` (ascending record indices of the exec set) beside
  `lineIndices`; all stepping walks it.
- `stepInto` / `reverseStep`: the next/previous exec record in the thread.
- `stepOver` / `reverseStepOver`: the next/previous exec record at the CURRENT FRAME'S
  DEPTH or shallower, excluding the frame's own ENTER — LINEs of the current/outer
  frames, the current/ancestor frame's EXIT, and past the frame's end a SIBLING frame's
  ENTER/records (the walk flows into the thread's next same-depth invocation instead of
  dead-ending at frame boundaries — threads re-entered from uninstrumented code produce
  thousands of sibling top-level frames). Everything inside callees (strictly deeper) is
  skipped; a callee's ENTER can only be a stepInto landing.
- The cursor may now rest ON a `METHOD_ENTER` (the frame just entered — its locals are
  the seeded args; its current line falls back to the method site's line when no LINE has
  executed yet) or a `METHOD_EXIT` (frame about to pop; current line = last LINE of the
  frame, or the site line). stack()/locals()/recordInfo()/watches must all be correct at
  such positions. In callsOnly-scope threads (no LINE records at all) stepping therefore
  works for the first time — ENTER/EXIT are the stops.
- `runToCurrentLine` and `exitFrameForward/Backward` keep LINE-based semantics (they are
  line-oriented commands) but must be no-op-safe when the frame has no LINEs.
- The §11.5 history-append and §13.1 scrolling rules are untouched.

### 17.2 Jump landings on exec records
Every jump that previously advanced to "the nearest following LINE" now advances to the
nearest following EXEC record (§17.1 set): the `/` calls jump, the instances `f`
first-mention jump, and the `.` object jump. The `j` record jump and the variable-history
jump land on the nearest exec record AT/BEFORE the target (falling forward only when none
precedes). A jump that lands on a metadata record is a bug.

### 17.3 Explorable map keys and values (engine)
A MAP-form object's children change: each entry node (labelled `<key> → <value>` as
today) is now EXPANDABLE and yields exactly two children, `key = <key>` and
`value = <value>`, each carrying its own Value — so a Ref key/value is independently
explorable from there (per its own snapshot availability). SET/LIST/ARRAY children are
unchanged.

### 17.4 Real parameter names (recorder + reader + engine)
The agent records parameter names into METHOD sites at transform time (FORMAT.md §5
`#p<slot>=<name>|...` suffix, from the LVT with MethodParameters fallback). The reader
strips the suffix from `desc` and exposes a per-site slot→name map. Engine `locals()`
names ARG-seeded slots from it — `argN` only when the recording carries no names.

### 17.5 Frame-selected position and stepping (engine + TUI)
- Engine: `SeqLocation frameAsOf(int frameIndex)` — the record the frame's shown state is
  reconstructed as-of (its last exec record at/before the cursor; the ENTER itself when
  none): `found=false` for unrecorded/out-of-range frames.
- TUI: with Source focused, the info line's record info reflects the SELECTED frame's
  as-of record (its seq/site), not the innermost, whenever a non-innermost frame is
  selected. On any step/run/exit-frame command while a non-innermost RECORDED frame is
  selected, the TUI first `engine.seek`s to that frame's as-of record and then applies
  the step — the cursor genuinely moves (one history entry via the normal doStep path;
  the previous position remains reachable from the Records window).

### 17.6 Recording name in the Threads title row (TUI)
The top-right window's border row shows the recording directory's NAME right-aligned,
ending exactly 2 columns left of the window's top-right corner, to the right of
`Threads [t]`. Names longer than 21 characters render as first-10 + `…` + last-10.
`Ctrl-D` (any focus) opens a small centered popup titled `Recording` showing the full
ABSOLUTE path (wrapped as needed); Esc closes it. Help overlay gains the binding.

### 17.7 Open-file browser (TUI)
The `F` dialog is redesigned; title `Open file [F]`.
- Content: a directory TREE rooted at the project root(s) (the `--repo`/source roots),
  listing subdirectories and `.java` files. ↑/↓ move, →/↵ expand a directory (↵ on a FILE
  opens it in browse mode and closes the dialog), ← collapses, Ctrl-F/Ctrl-B page, g/G
  ends. The full file index under the roots is walked once and cached.
- The `file:` query line is a case-insensitive REGEX filter over file names: only matching
  files are listed, a directory whose subtree has no matching file disappears, and the
  tree updates on every keystroke. Clearing the filter restores everything.
- Per-directory expand/collapse state is remembered ACROSS filter changes (a directory
  that disappears and reappears keeps its prior state) and across dialog close/reopen —
  the dialog's whole state (filter, selection, scroll, expansion) is persistent like
  §15.5, so `F` reopens exactly where it was closed. The §15.2 QUERY/LIST focus model
  applies (arrows enter the list, `/`-style return-to-query via typing? no — `F` has no
  competing key: any printable edits the filter from LIST focus too is NOT wanted; use
  the §15.2 model exactly: printables edit only in QUERY focus).
- Esc closes. The old dotted-class/`path:line` input form is retired (§11.8 superseded).

### 17.8 Native Shift-F7 / Shift-F8 (TUI)
Reverse step / reverse step-over bind to: `F7`+shift and `F8`+shift (xterm `;2~`
modifier form), PLUS `F15`/`F16` as aliases — Apple Terminal encodes Shift-F7/F8 with
the legacy "+8" scheme (`ESC[28~`/`ESC[29~`), which lanterna decodes as plain F15/F16
(user-verified). The F9/F10 fallbacks are REMOVED. Help texts say `⇧F7/⇧F8 reverse`.

## 18. v1.8 additions & changes

Authoritative over conflicting earlier text (§15.7, §17.5-§17.6). Viewer-only; works on
all schema-4 recordings.

### 18.1 Source title separator
In exec mode the Source title no longer blanks the gap with spaces: the border's own
dashes stay visible between the fixed title and the frame text, which is rendered as
`␣<SimpleClass>.<method>␣` (one space on each side) at the same offset as before —
`Source Code [c] ────── Class.method ──…`. Browse mode unchanged.

### 18.2 Recording-name padding
The §17.6 name block becomes `␣<name>␣` — shifted one column left so the trailing space
(not the name) ends 2 columns before the top-right corner.

### 18.3 Line-less recorded frames and threads (callsOnly visualization)
New engine accessors: `boolean frameHasLines(int frameIndex)` — whether the RECORDED
frame executed any LINE record within its span (false for unrecorded/out-of-range) — and
`boolean threadHasLines(int tid)` — whether the thread carries any LINE record at all.
- **Source**: when the shown frame is recorded but line-less, the highlight sits on the
  method DECLARATION line — found by scanning the source upward from the frame's current
  line (the site's first body line), at most 30 lines, for the first line containing the
  method name followed by `(` (annotation/comment-only lines skipped; constructors use
  the class's simple name; fallback = the frame's line). The EXEC highlight background
  for such a frame is DARK GREEN (new `Palette.EXEC_NOLINE_BG`, xterm-256 indexed 22)
  instead of blue; a selected non-innermost line-less frame keeps the §12.3 frame marker.
- **Stack**: rows of recorded-but-line-less frames render with a LIGHT GREEN foreground
  (new `Palette.CALLS_ONLY_FG`, ANSI bright green); unrecorded frames stay grey.
- **Threads**: rows of threads with NO LINE records at all (enter/exit-only or metadata
  only) render with the same light green; the current-thread `*` marker is unaffected.
Help overlay gains one line explaining the green marking.

### 18.4 Thread-kind cache file
On open, after the indices are built, the engine writes `viewer-thread-kinds.tsv` into
the recording directory (best-effort, silent on failure; not rewritten when present and
consistent): one `tid<TAB>LINES|CALLS` row per thread — LINES = the thread carries LINE
records. The file makes the classification available without replaying the recording
(external tools, future fast-start); the live TUI colors from the in-memory indices
regardless. The reader's `verify` must tolerate the extra file (it only looks at known
names).

### 18.5 Ctrl-D popup, selection-friendly
The §17.6 popup becomes FULL terminal width with NO side decoration: a top border row
(carrying the `Recording` title), the absolute path rendered on bare full-width lines
(wrapped, no leading/trailing decoration characters), and a bottom border row — so a
terminal selection of a long path grabs clean text. Esc still closes.

## 19. v1.9 additions & changes

Authoritative over conflicting earlier text (§16.4, §18.3). Viewer-only; works on all
schema-4 recordings. "Green context" = the INNERMOST (execution) frame is recorded but
line-less (`!engine.frameHasLines(stack().size()-1)`) — the callsOnly case §18.3 colors.

### 19.1 Declaration line only for the innermost line-less frame
The §18.3 declaration-line highlight + dark-green background apply ONLY when the SELECTED
frame IS the innermost (execution) frame. A selected NON-innermost line-less frame shows
its own reconstructed `currentLine` (the call-site line the engine already reports) with
the ordinary §12.3 frame marker — not the declaration line, not green. (Bug fix: v1.8
ran `DeclLine` for every line-less selected frame, so selecting any outer callsOnly frame
jumped to the same declaration line.)

### 19.2 Jump to line-trace — `j` / `J`
New engine `StepResult toLineTrace(boolean forward)`: moves the cursor to the next
(`forward`) / previous LINE record in the CURRENT thread strictly after/before the cursor
index; no-op `StepResult(false,"no further line-trace record in this thread")` when none.
On a move it resets the selected frame and is driven through the normal `doStep`
(afterMove + one history entry).
- Keys `j` (forward) / `J` (reverse) are GLOBAL and active ONLY in a green context; there
  they take precedence over the §16.3 Records-window `j` (seq dialog) — the seq dialog is
  still reached from any line-traced record. This mirrors the help swap (19.3): the base
  help slot shows `e/E exit-frame` when line-traced and `j/J jump to line-trace` when
  green, and the two are mutually exclusive.
- `e`/`E` (exit-frame) remain bound only when NOT in a green context.

### 19.3 Green info-line suffix
When in a green context, the status row keeps its normal focus-dependent position text
(§14.4/§15.6 — seq·t·site for Source/Threads, the frame's full name for Stack, the value's
full type for Locals) and then appends 10 spaces + `No line trace info available` rendered
in the §18.3 `CALLS_ONLY_FG` green. A transient status message still takes precedence over
the whole thing; the suffix is absent on any line-traced position. The base help slot
swaps `e/E exit-frame` ↔ `j/J jump to line-trace` with the context.

### 19.4 In-file search (`Alt-F`) overhaul (supersedes §16.4 rendering + motion)
- **Fixed left-anchored field**: `[ Search: <input> ]` is a COMPACT box anchored at the
  title start (`rect.col()+2`), NOT stretched to the right border; the `]` sits at a fixed
  column and never moves as the query grows (a long query scrolls within the input area,
  showing its tail). Black background, yellow letters (red when the query has no match at
  all). Border corners preserved.
- **Visible cursor**: the terminal cursor is shown one column after `Search: `, at the
  input caret; it is not hidden while searching.
- **Motion from the current line**: forward search starts at the current line and finds
  the next occurrence AFTER the current one; reverse finds the previous one BEFORE it.
  Keys: `Enter` or `↓` = forward, `Shift-Enter`/`Ctrl-Enter` or `↑` = reverse — `Alt-Enter` is preferred (it arrives as Enter+alt via ESC+CR and is
  distinguishable on Apple Terminal, where Shift/Ctrl-Enter are not); arrows are the
  universal fallback. Each move
  re-centres and sets `srcCursor` to the occurrence; the matched line carries the §13.4
  highlight.
- **Boundary invert, then wrap**: when a forward search finds no occurrence between the
  current one and END of file, the WHOLE title line between the brackets (brackets
  included) inverts colour and the cursor does NOT move; the NEXT forward press wraps to
  the first occurrence from the top and restores normal colour. Reverse is symmetric
  against the TOP of file, wrapping to the last occurrence. Any query edit or direction
  change clears the pending-boundary state. An empty query clears occurrences/highlight
  but stays in search mode. `Esc` exits and restores the title.

## 20. v2.0 additions & changes

Authoritative over conflicting earlier text (§16.3, §19.2, §19.4). Viewer-only; all
schema-4 recordings. Numeric/date/hex decoration and wrapper-inlining live in ONE engine
value-formatting path so locals, object trees, watches, var-history and the new §20.9
window all render identically.

### 20.1 Inline wrapper scalars
When a value is a reference to an always-dump wrapper (`AtomicInteger`/`AtomicLong`/
`AtomicBoolean`/`StringBuilder`/`StringBuffer` — the FORMAT §7 scalar wrappers), its
display is `<SimpleClass>#<id>  [ <value> ]` — the id, then two spaces, then the wrapper's
current `value` (as-of the cursor seq, from its snapshot) in `[ … ]` with a space inside
each bracket — so the scalar is visible WITHOUT expanding. The inner value carries the
§20.4/§20.5 numeric decoration. Boxed numbers/booleans and strings are already shown
inline by value (unchanged). Such wrapper nodes are non-expandable.

### 20.2 AtomicReference inline
An `AtomicReference` renders `AtomicReference#<id>  [ <value> ]` too: `[ null ]` when its
`value` is null (no expansion needed), `[ <SimpleClass>#<rid> ]` when it holds a
reference — and in the non-null case it stays EXPANDABLE so the referent can be explored.

### 20.3 Lambda → jump to definition (Enter)
A value whose runtime class is a lambda (rendered `Lambda/0x…#id`, i.e. class binary
contains `$$Lambda`) is jumpable: in the Locals tree and in the `.` object-search
results, `Enter` on a lambda node jumps to the lambda's FIRST MENTION
(`engine.firstMentionOf` → nearest following exec record, one history entry) — the closest
recoverable "definition" (the capture/creation point). New engine `boolean
isLambda(long objectId)`. In Locals this overrides expand for lambda nodes (their captured
`arg$N` fields are still reachable elsewhere); non-lambda nodes expand as before.

### 20.4 Time values → date
A numeric value (int/long) is annotated with its UTC date when it falls in one of three
non-overlapping epoch ranges (years ≈2001–2096): seconds `[1e9,4e9)`, millis `[1e12,4e12)`,
nanos `[1e18,4e18)`. The suffix is `  (YYYY-MM-DD HH:MM:SS UTC)`. Applies wherever a numeric
value is rendered (int reaches only the seconds range; long reaches all three).

### 20.5 Integer hex
Every integral value shows its hex after the decimal: `<decimal>  0x<HEX>`, zero-padded to
the type width — byte 2, char 4, short 4, int 8, long 16 hex digits (uppercase). Booleans
get no hex. A long is decorated with BOTH hex and, when applicable, the §20.4 date. Order:
`<decimal>  0x<HEX>[  (<date> UTC)]`. Chars keep their `'c'` decimal form then `0x00NN`.

### 20.6 Green highlight preserved
When a §18.3 green (line-less) row is the SELECTED row in Threads or Stack, the selection
uses `CALLS_ONLY_FG` (green) as the foreground on `SELECTION_BG` instead of `SELECTION_FG`,
so the row's green identity survives being highlighted.

### 20.7 In-file search field (supersedes §19.4 geometry/colour)
The `Alt-F` field spans the title row symmetrically: `[` two columns right of the top-left
corner (`rect.col()+2`), `]` two columns left of the top-right corner (`rect.col()+width-3`)
— fixed, full-width, `[ Search: <query………> ]`. Foreground is always YELLOW (no red state;
the §19.4 boundary invert still signals end-of-file). The caret is visible one space after
`Search: `. Every occurrence of the query VISIBLE in the source body is highlighted
(matched substring reverse-video / distinct background) in addition to the current-match
line highlight. Motion/boundary/keys (Enter, Alt/Shift/Ctrl-Enter, ↑/↓) are unchanged from
§19.4.

### 20.8 Browse mode yields to execution navigation (bug fix)
Opening a file with `F` enters browse mode; today stepping / frame-select / thread-switch /
record-jump leave the Source window stuck on the browsed file until `o`. Fix: ANY action
that moves the execution cursor or changes the shown frame/thread (`doStep`, `afterMove`,
`selectStackRow`, all jumps) automatically EXITS browse mode and shows the relevant
execution source. `o` still returns manually; browse mode now only persists across pure
source scroll / in-file search.

### 20.9 Global variable-change list — `V`
`V` (global, any focus) opens a window listing FIELD_WRITE transitions across the whole
recording. A `filter:` line filters by field name (case-insensitive substring; empty = all).
Rows `seq <N> · t<tid> · <Owner>#<oid>.<field>: <old> → <new>` (statics omit `#oid`),
ascending seq, capped at 1000 with a `showing first 1000 of N` note. Keys: ↑/↓ select,
←/→ (and `<`/`>`) hscroll, `g`/`G` ends, `Ctrl-F`/`Ctrl-B` page, `Enter` = jump to that
record's seq (nearest exec record, one history entry) and close, `Esc` close. New engine
`List<FieldWriteHit> fieldWriteChanges(String nameSubstring, int cap)` + `long
countFieldWriteChanges(String nameSubstring)` over the existing per-name write index.

### 20.10 Run-to-line fix + reverse
`Ctrl-R` = run forward to the current line, `Ctrl-Q` = run backward to it (both
thread-preserving). BUG FIX: the target line is the INNERMOST frame's current source line
resolved to its LINE site (v1.7 left the cursor able to rest on ENTER/EXIT records, whose
`location()` is not a LINE site, so the old code always failed); when the cursor is not on
a LINE, resolve the frame's current line's site first. When no other record passes through
that line in the run direction, no move + info-line message `no earlier/later pass through
this line`. Threads without LINE records report `no line records in this thread`. The old
`Alt-R` reverse binding is removed.

### 20.11 Help window + trimmed status help
The bottom help line no longer lists `Ctrl-R`/`Ctrl-Q`/`V`/`Ctrl-X`; it ends with
`F1 help`. `F1` (and `?`) opens the help WINDOW (the existing overlay, extended) listing
EVERY command incl. the ones dropped from the status line. `Ctrl-X` is a synonym for `q`
(quit → the §12.4 confirm box); it is documented only in the help window.

### 20.12 Records window: `c` replaces `j`
In the Records window the seq-jump dialog moves from `j` to `c` (§16.3), freeing `j` for
the §19.2 global jump. Because `c` is normally the Source focus letter, the focus switch
yields to Records: when Records is focused, `c` opens the seq-jump dialog; switch to the
Source window from Records via `Tab`. (`j`/`J` keep their §19.2 green-context meaning.)

### 20.13 Per-window scroll keys
Each window scrolls its viewport independently of the selection: `Ctrl-P` up, `Ctrl-N`
down (one line); `Ctrl-K` left, `Ctrl-L` right (one hstep) as synonyms for the existing
`←`/`→` and `<`/`>`. NOTE the user-requested `Ctrl-I`/`Ctrl-M`/`Ctrl-J` are the terminal's
Tab/Enter/LF byte codes and cannot be bound without hijacking those keys, so the
collision-free `Ctrl-P`/`Ctrl-N`/`Ctrl-K`/`Ctrl-L` are used; `Ctrl-K` matches the
user's "K = horizontal" intent. Arrow and `<`/`>` mappings are retained.

## 21. v2.1 additions & changes

Authoritative over conflicting earlier text (§20.4, §11.7/§15/§17.7, §19.4, §20.9/§20.10/
§20.13, §12.4). Viewer-only except §21.4 (recorder/format). All schema-4 recordings.

### 21.1 Date view is name-gated
The §20.4 UTC date suffix now applies ONLY to a value whose VARIABLE NAME contains
`time`, `date` or `instant` (case-insensitive) AND whose int/long value is in a
seconds/millis/nanos range. The v2.0 blanket "any in-range number is dated" is REMOVED
(it dated unrelated large ints). Hex (§20.5) is unchanged — still on every integral value.
Names come from the local/field/watch label; nameless contexts (list/array elements, map
keys/values) get no date. Implementation: the date part moves out of the context-free
`toValue` into the name-bearing render sites (locals, snapshot fields, watches), which pass
the name to the number formatter; hex stays in `toValue`.

### 21.2 In-file search direction indicator (supersedes §19.4/§20.7 reverse keys)
The `Alt-F` field prompt shows the search direction as an arrow inside the label:
`[ Search↓: <query> ]` (down = forward, the default) or `[ Search↑: <query> ]` (up =
reverse). `Tab` flips the arrow. On a flip the arrow is drawn INVERTED (black arrow on the
amber/yellow field background) as a transient cue; it returns to normal (yellow arrow on
black) as soon as the user types or presses Enter. `Enter` searches in the arrow's current
direction (no more Option/Alt-Enter, Shift/Ctrl-Enter — those are removed; ↑/↓ are also
removed as search-motion keys since the field owns the arrow). `Ctrl-U` clears the query.
`Esc` exits. The §19.4 boundary-invert-then-wrap still applies in the active direction.

### 21.3 File-open browser rework (supersedes §17.7)
Window width 2/3 of the terminal. Two modes:
- **Browse (empty filter)**: the directory tree as in §17.7 (expand/collapse folders,
  Enter on a file opens it). Entering a folder appends to the current path; the STATUS
  line shows that current path.
- **Filter (non-empty query)**: the query filters ALL files under the project root(s)
  (recursive, case-insensitive substring/regex); folder expand/collapse is HIDDEN and the
  result is a FLAT file list. Each row shows the path's SUFFIX so the file name is always
  visible (leading directories may be clipped); the row is horizontally scrollable to
  reveal the prefix. The STATUS line shows the SELECTED file's full path (or as much as
  fits).
Focus: typing edits the filter; when the selection is at the TOP of the list, `↑` (and
`/`, kept) returns focus to the filter field and clears the list highlight — matching the
§15.2 model. `Enter` on a file opens it (browse mode); `Esc` closes; state persists across
reopen (§15.5).

### 21.4 Record the source commit (recorder/format)
The recording script captures the target project's `git rev-parse HEAD` (empty when there
is no `.git`) and passes it to the agent (`sourceCommit=<hash>`); the runtime echoes it to
`manifest.properties` as `sourceCommit` (FORMAT.md §13). The viewer surfaces it in the
`Ctrl-D` recording popup (a `commit <hash>` line; `(working tree — no .git)` when empty).
Config: new `AgentConfig.sourceCommit` / `RuntimeConfig.sourceCommit`. Schema unchanged.

### 21.5 Containers: size label + always populated (extends §7 always-dump)
Every unfolded container (map/set/list/collection AND array) renders its type with the
size appended: `<SimpleClass>#<id>, size=<totalSize>` (the true size at snapshot time, not
the ≤64 shown-entry count). This applies at ALL depths and regardless of the shown-entry
cap (the existing `…+N more` leaf stays). Containers must be POPULATED whenever a snapshot
exists at/before the cursor — investigate and fix any case where a container that has a
container-form snapshot still shows empty (e.g. a field whose value is a container but the
node was built non-expandable, or a snapshot chosen incorrectly as-of seq). If genuinely no
snapshot exists at/before the cursor, `<No info available>` remains correct.

### 21.6 Uncapped instance/method browse + thread filter
The `/` browser (both calls and instances modes) no longer trims to 500 — it uses ALL
matching records (`Engine.instancesOf`/`callsOf` called with an unbounded cap; the
`showing first N of M` note disappears when all are shown). A SECOND input field sits to
the right of the regex field with prompt `thread: `; a non-empty value filters results to
that thread id (blank = all threads). Rows unchanged. (List rendering is already
scroll-virtualised, so an all-records list is fine.)

### 21.7 Run-to-line = selected line, + cross-thread variants (supersedes §20.10)
"Current line" means the SELECTED (highlighted-number) source line `srcCursor` in the
shown frame's file, resolved to that `(classBinary, line)`'s LINE site — NOT the cursor
record's own line. Commands:
- `Ctrl-R` forward / `Ctrl-Q` backward — next/previous record PASSING THROUGH that line in
  the CURRENT thread (thread-preserving).
- `Ctrl-T` forward / `Ctrl-W` backward — same, but ANY thread (by global seq), not
  thread-preserving.
When no such record exists in the direction, an info-line message; browse mode / no
resolvable line ⇒ a message. New engine `runToLine(String classBinary, int line, boolean
forward, boolean sameThread) -> StepResult`.

### 21.8 Remove the Ctrl-C immediate exit
`Ctrl-C` no longer quits the viewer (the abrupt, unconfirmed exit is removed everywhere it
was a fallback). Quit is only `q` / `Ctrl-X` → the §12.4 confirm box. EOF/stream-close
still ends the loop.

### 21.9 Scroll-up key `Ctrl-P` → `Ctrl-U`
Per-window viewport scroll-up moves from `Ctrl-P` to `Ctrl-U` (§20.13); `Ctrl-N` (down),
`Ctrl-K`/`Ctrl-L` (left/right) unchanged. In the `Alt-F` search field and dialog inputs,
`Ctrl-U` keeps its §21.2/§15.2 clear-the-query meaning (input modes consume it first);
globally it scrolls the focused window up.

### 21.10 Help-window-only commands
`Ctrl-R`/`Ctrl-Q`/`Ctrl-T`/`Ctrl-W` (run-to-line family), `V`, `Ctrl-X` and the scroll keys
appear in the `F1` help WINDOW but NOT on the trimmed status line, which keeps ending
`F1 help`.

## 22. v2.2 additions & changes

Authoritative over conflicting earlier text (§21). Viewer-only except §22.7 (recorder/
script). All schema-4 recordings.

### 22.1 Broader, token-based date gate
The §21.1 date gate becomes richer and TOKEN-based (not raw substring, which mis-fired on
`update`⊇`date`, `runtime`⊇`time`). The variable name is split into lowercase tokens on
camelCase boundaries and `_`/`$`; a date is shown when ANY token is in the time set AND the
value is a non-negative int/long in a seconds/millis/nanos range. Time-token set (tunable):
`time, date, datetime, instant, timestamp, ts, second, seconds, sec, milli, millis, ms,
nano, nanos, micro, micros, start, end, epoch, expiry, expiration, expires, deadline,
elapsed, since, until, created, updated, modified`. Valid dates are ≥ 1970-01-01 (negative
values never dated — already enforced). Hex still on every integral regardless of name.

### 22.2 `F` dialog: prefix on the Info line; right-aligned names; match highlight
In flat-filter mode: the selected file's full path is shown on the global INFO line
(screen's last-but-one row), NOT inside the dialog. Rows RIGHT-ALIGN the (root-relative)
path so the file name is always flush-right/visible; the query's matches within each row
are HIGHLIGHTED (all occurrences, possibly several per row). Horizontal scroll still
reveals the clipped prefix.

### 22.3 Up-at-top returns to the input field (all list windows)
In every window/dialog that has a text-input field above a list (the `/` browser, the `V`
window, the `F` dialog), pressing `↑` while the selection is on the FIRST list row returns
focus to the input field and clears the list highlight. In the `/` browser this returns to
the LEFT (regex) field.

### 22.4 `/` dialog: in-field cursor + edge crossing
The `/` dialog's two inputs become real text fields with a caret: `←`/`→` move the caret
within the focused field. From the LEFT (regex) field, `→` at the right-most caret moves
focus to the `thread:` field (caret at its start). From the `thread:` field, `←` at the
left-most caret returns to the regex field (caret at its end). `↓` from either field enters
the list (§22.3 `↑` returns). Backspace/typing act at the caret. (Digits-only still enforced
in `thread:`.)

### 22.5 Run-to-line works on the shown file (browse + exec) — supersedes §21.7 resolution
Run-to-line targets the SHOWN file's `srcCursor` line regardless of mode: engine gains
`runToLine(String fileName, int line, boolean forward, boolean sameThread)` resolving LINE
sites by the file symbol's base name (covers inner classes sharing the file, and browse
mode where there is no frame). The TUI passes the shown file's base name (the selected
frame's file in exec mode, the browsed file in browse mode) + `srcCursor`. `Ctrl-R`/`Ctrl-Q`
(same-thread fwd/back) and `Ctrl-T`/`Ctrl-W` (any-thread fwd/back) all work in browse mode
and on the current stepped-into file. FIX `Ctrl-Q`: verify the same-thread BACKWARD search
actually moves (investigate the reported failure end-to-end — binding, engine backward
scan, and the browse-mode bail that currently blocks it). Unresolvable file/line ⇒ info
message.

### 22.6 `Alt-F`: space before the arrow
The field prompt is `[ Search ↓: <query> ]` / `[ Search ↑: <query> ]` — one space between
`Search` and the direction arrow.

### 22.7 Timestamp always preserved (recorder/script)
The recording script adds `com.hedera.hapi.node.base.Timestamp` to `snapshotAlways` so
Timestamp instances are baseline-dumped at any depth (their `seconds`/`nanos` never come
out `<No info available>`). Needs a fresh recording.

### 22.8 Container sizes in the `/` instance-explore
The `, size=N` container label (§21.5) must also appear when exploring an instance's tree
inside the `/` dialog (its nodes currently omit it). Route the `/` explore tree through the
same ref-rendering as Locals so map/set/list sizes show there too.

### 22.9 Numeric element alignment; arrays omit the size suffix
When a container's elements are all integers, RIGHT-ALIGN them padded with spaces to the
width of the longest `Integer.toString` in that container (per-container). When all
elements are floating-point, right-align on the INTEGER PART (pad the text before the
decimal point to the max integer-part width). Mixed/non-numeric: unchanged. ARRAYS do NOT
get the `, size=N` suffix (their length already shows in the type, e.g. `int[8192]#id`);
maps/sets/lists keep it.

### 22.10 `/` and `F` dialog geometry
Both the `/` browser and the `F` file dialog are 2/3 of the terminal WIDTH and 3/4 of its
HEIGHT.

### 22.11 `V` window: thread filter + uncapped
The `V` variable-change window gains a `thread:` filter field with the §22.4 edge-crossing
behaviour, filtering transitions to that thread (blank = all). The list is UNTRIMMED (all
matching writes, `Integer.MAX_VALUE` cap) so every item is explorable. Engine
`fieldWriteChanges`/`countFieldWriteChanges` gain a thread-id filter param.

### 22.12 ID fields are expandable
A field whose name denotes an ID — exactly `id` (any case), or ending `Id`/`ID`, or
starting `id` followed by an uppercase letter / `_` / `$` — is marked EXPANDABLE when its
value is a reference (object id), UNLESS the value is a primitive or a boxed/atomic number
(those stay non-expandable). So an `accountId = AccountID#5` (or an id holding any recorded
object) can always be drilled into.

### 22.13 Jump-to-record: last-record hint + `l`; Records opens it with `r`
The `Jump to record` modal shows the highest record seq as a hint (`1..<max>`). A new
global `l` jumps to the LAST record (highest seq → nearest exec record). Engine
`lastRecord() -> SeqLocation` (or `maxSeq()`), reused by both. In the RECORDS window the
modal now opens with `r` (not `c`), so `c` again switches focus to the Source window from
Records (undoing the §20.12 yield). `r` elsewhere still switches focus to Records.

### 22.14 (engine) run-to-line by file name
`runToLine(String fileName,int line,boolean forward,boolean sameThread)`: lazily index LINE
sites by `(fileBaseName, line) -> siteIds`; same-thread scans the current thread's
`lineIndices` strictly after/before the cursor for any of those sites; any-thread scans all
threads by global seq. Keep the §21.7 `(classBinary,...)` overload if still referenced, or
migrate its callers/tests.

## 23. v2.3 fixes

Bug fixes / refinements to §22. Viewer-only.

### 23.1 Ctrl-Q run-to-line-backward (IXON)
Ctrl-Q never reached the app because the terminal's XON/XOFF flow control (Ctrl-S/Ctrl-Q =
XOFF/XON) swallowed it — which is why Ctrl-R/Ctrl-T/Ctrl-W worked but Ctrl-Q did not (the
engine's backward scan was already correct). Main clears IXON on the controlling tty
(`stty -ixon </dev/tty`) after lanterna's raw-mode setup; `stopScreen` restores the termios
lanterna snapshotted BEFORE that, so the user's original IXON is preserved on exit (no explicit
re-enable, which would wrongly force IXON on for a user who runs with it off). Best-effort; a tty
that can't do it simply keeps the old behaviour.

### 23.2 `.` (find object usage): uncapped + class-usage + working jump
- The `RESULT_CAP` (200) trim is removed — the whole usage list is shown; only a high safety
  cap (100k) guards against a degenerate broad substring matching millions of records (OOM),
  with a `showing first 100000 of N — narrow the filter` note when hit.
- A CLASS-name query now lists every record that references an INSTANCE of the class (the
  record's `target`, or a before/after `OBJECT_ID` slot, resolving to a matching class) —
  ENTER `this`, ARG values, FIELD_WRITE owners/values, snapshots and first-seen alike — so
  it is a real usage finder, not just the creation (`OBJECT_FIRST_SEEN`) list it used to be.
  Matching resolves the class's instance ids once from the engine's class index, then the
  record scan is O(1) set membership per slot (not a per-record class resolution).
  An id query (`#N`/`N`) still lists usages of that id.
- Enter now transitions: it lands on the nearest exec record at/before the selected record
  (`landOnNearestExecAtOrBefore`, the helper the V/Records/var-history jumps use) — the old
  `seekToSeq` stepping could leave the cursor unmoved. `seekToSeq` is removed.

### 23.3 Source cursor line full-line tint
The moved cursor line (↑/↓, the highlighted-number line) now tints its WHOLE line, not just
the gutter: the gutter keeps its bright selection marker (§13.4) and the code cells get
`CURSOR_LINE_BG` (xterm-256 indexed 234, `#1c1c1c`) — deliberately DARKER than
`FRAME_LINE_BG` (236) so an actual record/frame position always outranks it; when the cursor
sits on the exec/frame line, that highlight wins outright (the tint never overrides a record
position).

## 24. v2.4 additions & changes

### 24.1 `.` (find object usage) — full dialog parity
The `.` dialog behaves like the other list windows/dialogs. It is 2/3 of the terminal
width × 3/4 of its height (§22.10), and adopts the `/` browser's §15.2/§21.6 model wholesale:
- The query row holds TWO fields — `query:` (left) and `thread:` (right, digits only,
  blank = all threads; every edit re-filters the scan to that thread id). `→` at the
  query's right edge crosses into `thread:`, `←` at its left edge crosses back; carets,
  insertion and Ctrl-U/Ctrl-W/Ctrl-Backspace clearing as in §22.4.
- The §15.3 count line (`N results`, or the §23.2 cap note) sits DIM directly under the
  query; the list runs down to the hint row.
- Focus: QUERY ↔ THREAD ↔ LIST. `↓` enters the list, `↑` on the first row returns to the
  query, `/` returns from anywhere in the list; the selection highlight shows only in
  LIST focus (§22.3). Enter (LIST) jumps and closes; Esc closes. The dialog stays
  transient (fresh per open).
- LIST keys match every other window: ↑/↓ move; Ctrl-F/Ctrl-B and PgDn/PgUp page;
  g/G first/last; Ctrl-U/Ctrl-N scroll the viewport one line (selection dragged along
  only at the view's edge); `<`/`>`, `←`/`→` and Ctrl-K/Ctrl-L horizontally scroll the
  rows (whole row participates; the same keys work in the `/` calls list).

### 24.2 `.` rows — object-resolved metadata records + match highlight
- A row whose record is `OBJECT_FIRST_SEEN` or `SNAPSHOT` resolves to the OBJECT itself:
  `seq N · tT · Class#id first seen` / `… snapshot` (their `location` is no source site,
  so the old site text degraded to a bare record-type name).
- Every occurrence of the typed filter inside a row's visible text repaints reverse-video
  (yellow, the §22.2 file-browser style), case-insensitively, on selected and unselected
  rows alike.

### 24.3 `.` rows — ' -> ' containment chain to an accessible object
Each visible row answers: is the row's object directly usable in the context of that
record — i.e. is it the value of a local variable, a method argument or `this` in the
record's own (top) frame, reconstructed as-of that record? If YES the row is unadorned.
If NOT, the row continues with the object's containment chain, walked upward until an
object that IS directly accessible: ` -> Parent#id -> … -> name=Class#id` (the final,
accessible object is annotated with the variable name that reaches it). ` -> ?` marks an
object not reachable from the frame's roots within the search limits (depth ≤ 5 from the
roots, ≤ 200 objects expanded per row) — or a row with no frame context at all.

Implementation contract: chains are computed lazily for exactly the visible rows and
cached per result list (invalidated on every recompute). Resolution seeks the engine to
the row's record, gathers the top frame's locals/args/`this` as roots, BFS-descends the
object graph as-of that record (map entries pass through one structural level, §17.3;
cycles guarded), and restores the engine position afterwards — the cursor never moves.

### 24.4 Source cursor line — lighter tint
`CURSOR_LINE_BG` is xterm-256 indexed 235 (`#262626`) — one step lighter than §23.3's
234 so the tint reads clearly, still strictly darker than `FRAME_LINE_BG` (236) so an
actual record/frame position always outranks it (precedence unchanged from §23.3).

### 24.5 Recorded-line gutter (visited-line index)
The viewer consumes the FORMAT.md §12.1 visited-line index (`lines.tsv`, built by one
full record scan after recording — `reader lines` — or lazily on the recording's first
use; `Main` pre-builds it before the alternate screen with a
`building line index (first use of this recording)...` note so both the CLI and the
viewer then read it back for free):
- In the Source window, a line the recording touched at least once shows a WHITE line
  number; unvisited lines keep the dim grey. The §13.4 cursor-line inversion still wins.
- Works identically in exec mode (the frame's class+file resolve to the §12.1 key) and
  browse mode (the browsed file's path relative to its owning source root IS the key).

### 24.6 `F` (Open file) — project-root trees, coverage colours, full scrolling
- Trees start at the PROJECT roots: every source root under a `--repo` dir is shown from
  that dir — one top-level tree per repo (named by its absolute path), with every
  directory between the repo root and the package roots visible as ordinary expandable
  levels, source roots sharing a prefix merged (`hedera-node/…/src/main/java/com/…`).
  A source root under no repo keeps its own top-level tree (old shape). FLAT-filter rows
  show the path relative to the top-level tree, so the whole project-relative path is
  visible (and horizontally scrollable).
- Coverage colours: a FILE the recording touched (its source-root-relative path is a
  §12.1 key) renders WHITE; other files use the Source gutter's dim grey. In BROWSE mode
  a DIRECTORY renders white iff at least one covered file lies beneath it. Selection
  inversion still wins; the §22.2 match highlight still paints on top in FLAT mode.
- Scrolling in all four directions, like every other window: Ctrl-U/Ctrl-N viewport
  line scroll (selection dragged along only at the view's edge), Ctrl-K/Ctrl-L and
  `<`/`>` horizontal in BOTH modes (browse rows clip through the offset too; `←`/`→`
  additionally scroll in FLAT mode, keeping their expand/collapse role in BROWSE),
  Ctrl-F/Ctrl-B / PgDn/PgUp paging and g/G as before.

## 25. v2.5 additions & changes

### 25.1 Unvisited line numbers darker
The Source gutter's THREE-way line-number colouring: a line the recording visited (§24.5)
stays WHITE; an unvisited line's number is now `UNVISITED_FG` — xterm-256 indexed 239
(`#4e4e4e`), deliberately DARKER than the `DIM` chrome grey (which the §24.6 file-browser
rows and other de-emphasised chrome keep) — so recorded and unrecorded lines read apart at
a glance while unvisited numbers recede. The §13.4 cursor-line inversion still wins. The
palette's fourth 256-colour exception.

## 26. v2.6 additions & changes

### 26.1 `.` (find object usage) — numbering, persistence, field navigation, `Class#id`
- Result rows are numbered from 1 (§15.4 style), the number participating in horizontal
  scroll like the `/` lists.
- The dialog is PERSISTENT like `/` (§15.5): Esc/close keeps query, thread filter, results,
  selection, scroll and focus; reopening with `.` continues exactly where it left (carets at
  each field's end; the §24.3 chains cache rides along).
- The query accepts the `/` instances grammar: `#123`, bare `123` AND `Class#123` all select
  the specific object (the class part is informational — the id is unique).
- The §24.3 containment chain drops the `" -> ?"` marker: a row whose object cannot be
  traced to an accessible root within the limits simply carries no suffix.

### 26.2 Dialog field navigation — `Tab`, `^F`, `^T`
In the `.` dialog `Tab` toggles the `query:` ↔ `thread:` fields (the `/` browser keeps Tab
for calls ↔ instances). `^F` focuses the filter/query field and `^T` the thread field from
ANY focus, in both the `/` and `.` dialogs; the `F` browser's `^F` focuses its filter too.
Consequently `^F` no longer pages inside these dialogs — PgDn (and `^B`/PgUp) still page.
(§27.2 moves the filter-focus key to `^L` and returns `^F` to PageDown.)

### 26.3 Sources pinned to the recording's git commit
A recording is only line-accurate against the commit it was made at (`sourceCommit`,
§21.4/FORMAT.md §13); the checkout may have drifted — methods move, whole modules/packages
migrate. When the manifest's commit RESOLVES in a `--repo` (git present, commit in the
object store), that repo's sources are read from git's tree at that exact commit:
- Discovery via `git ls-tree -r <commit>`: every `.java` under a `src/<set>/java` segment,
  keyed package-relatively — classes whose source root does not even exist in the current
  checkout (e.g. a module migrated on another branch) still resolve.
- Content via `git show <commit>:<path>`, cached; a file also present in the working tree
  reads its PINNED content (dirty/drifted checkouts included).
- Resolved paths may be VIRTUAL (`<repo>/<relpath>` absent from disk); browse-open, the
  §24.5 gutter and coverage keys all route through the pinned view.
- The `F` browser lists the COMMIT's files for pinned repos (virtual trees, §24.6 coverage
  colours intact) instead of walking the drifted working tree.
- Startup prints one `sources: <repo> pinned to git commit <hash> (N files…)` note per
  pinned repo, flagging when the checked-out HEAD differs.
- Priority: a class/path found in a pinned commit reads PINNED content on EVERY access
  path (frame resolution, browse-open, coverage keys) — one class, one content. The
  working tree is the fallback: explicit `--source` roots and a pinned repo's own
  discovered roots serve only what the pinned commits do not cover (e.g. a file recorded
  from a dirty checkout that never made it into the commit).
- Fallbacks: a repo without git / the commit (shallow clone, gc, pre-v2.1 recording
  without `sourceCommit`) falls back to working-tree discovery exactly as before.

### 26.4 Dates by value range alone (2000–2100), floats included
The §21.1/§22.1 name gate is RETIRED: no variable name is consulted. Any positive int/long
— or float/double — whose value lands in one of three epoch ranges gets the UTC suffix
`(YYYY-MM-DD HH:MM:SS UTC)` after its hex: seconds `[946684800, 4102444800)`, millis
and nanos scaled likewise — each dating between 2000-01-01 and 2100-01-01. Every INLINE
render site is covered identically (locals, args, snapshot fields, field-write transitions,
watches, container elements, map keys/values). Microseconds were initially left out;
§27.3 adds them. The §22.9 element alignment understands the new suffixes.

### 26.5 Array/container element forms
- Element indices right-align to the widest shown index (`" [9] = "` under `"[10] = "`) so
  the equals signs of a >10-element container line up.
- A displayable byte (32–126) appends its character after the hex: `72  0x48  'H'`.
- A char shows `'c'`, two spaces, its decimal code left-padded to 5, two spaces, the hex:
  `'A'     65  0x0041`.
- Like the §20.5 hex, the byte/char forms are CONTEXT-FREE — they apply at every INLINE
  render site (locals, fields, watches), not only to container elements.

### 26.6 Info line peeks scalar values
While exploring in Locals or the `/` instances browser, a selected node holding a native/
boxed primitive, a string (`String`/`StringBuilder`/`StringBuffer`) or a scalar atomic
(`AtomicInteger`/`AtomicLong`/`AtomicBoolean`) appends `  =  <value>` to the info line
after the type info — hex and the §26.4 date included, strings simply filling to the end
of the line. The value shown is exactly the explored node's rendered value. A nested
(descriptor-less) primitive whose decorated text defeats the type heuristics shows the
value alone rather than a guessed type followed by the same text twice. (§27.4 retires
that fallback for engine-produced values: their tag names the exact type.)

### 26.7 Opening record — `--seq N`, `--last`, line-trace default
- `viewer --seq N` opens on the nearest exec record at/before global seq N — N itself
  need not exist (real recordings have in-range seq holes from never-flushed counter
  ticks; anything at/before the nearest existing seq qualifies). Only an N preceding the
  whole recording reports `no record at or before seq N`.
- `viewer --last` opens on the recording's LAST line-traced record (mutually exclusive
  with `--seq`).
- By default the viewer opens on the FIRST line-traced record of the initial thread
  (an ACCEPTED `--tid`, else thread 1) so the Source window shows a project file
  immediately; a thread without line records keeps the engine's default position, and a
  rejected `--tid`'s error message is never overwritten by the landing status.

### 26.8 Find-in-window (Alt-F) for Threads, Stack, Locals, Records
Alt-F on a focused list window opens the SAME search field the Source window has (§16.4/
§21.2), drawn over the window's title: typing jumps the selection to the match at/after it,
`↵` searches in the field's arrow direction (Tab flips ↓/↑ with the §19.4 two-press
boundary inversion), every visible occurrence highlights reverse-video in the rows, Esc
closes leaving the selection on the last match. Matching is case-insensitive over the rows
exactly as rendered. A Stack landing selects the frame (the same path as ↑/↓); the other
windows move their selection only.

### 26.9 Records: cross-thread exec stepping (`+`/`=`, `-`/`_`)
In the Records window `+` or `=` jumps to the NEXT — and `-` or `_` to the PREVIOUS —
record with line info or method enter/exit (the §17.1 exec set) by GLOBAL seq, crossing
threads freely (unlike F7/⇧F7, which stay in the current thread). Backed by the new
engine API `toExecAnyThread(forward)`; each jump appends one history entry. The bottom
help row describes the keys while Records is focused, and the Help window lists them.

## 27. v2.7 fixes

### 27.1 Instances-tree viewport scroll drags the selection
`Ctrl-U`/`Ctrl-N` in the `/` browser's instances tree behaved unlike the `.`/calls flat
lists: the tree scrolled the offset only, and the render-time selection-visibility clamp
snapped it back once the selection reached a viewport edge — so scrolling stuck. The tree
now uses the same rule as the flat lists (a new viewport-aware `TreeView.scrollBy`): the
selection is dragged along the nearest edge when it would fall outside, so scrolling
continues freely. The MAIN windows' §20.13 semantics (offset pinned by the selection)
are deliberately unchanged.

### 27.2 `^L` focuses the filter; `^F` is PageDown again
§26.2 gave `^F` a second job (focus-the-filter) inside the `/`, `.` and `F` dialogs,
colliding with its PageDown role everywhere else. Now: `^L` focuses the filter/query field
(and `^T` the thread field, unchanged) in all three dialogs, from any focus; `^F` pages
down again exactly like everywhere else (`^B` pages up). Consequently `^L` no longer
horizontal-scrolls inside these dialogs — `^K`, `<`/`>` and `←`/`→` still do.

### 27.3 Microseconds date range
The §26.4 trio left a gap between millis and nanos: a microseconds timestamp like
`startTime = 1338404429826375` (2012-05-30 19:00:29 UTC) never dated. A fourth range —
micros `[946684800000000, 4102444800000000)`, dating between 2000-01-01 and 2100-01-01
like the others — closes it, for ints/longs and floats/doubles alike.

### 27.4 The info line always names the explored value's type
`Value.Primitive` now carries the Java type its FORMAT §2.3 tag denotes (`"long"`,
`"char"`, …), assigned by the engine's single INLINE render path — so the info line names
the exact type even for NESTED fields whose slot descriptor is unknown (an `AccountID`'s
`shardNum` reads `long  =  11  0x000000000000000B`, not a bare value). The `/` instances
browser's info line gains the same type-and-value text after the owning instance's
`class#id`. Hand-built values without tag knowledge keep the old text/descriptor
heuristics.

## 28. v2.8 additions & changes

### 28.1 Source title = the file's repo-relative path
The Source window title text is the shown file's path from the repo (`.git`) root
(via the §26.3 pinned view or the `--repo` bases; source-root-relative, then the bare name,
as fallbacks), drawn `␣<path>␣` on the top border between `┌─ Source Code [c] ────` and
`───┐` — right-anchored with its trailing space four columns before the corner, starting no
earlier than four dashes after the title, and trimmed from the LEFT with a leading ellipsis
when too long. It replaces both the old §18.1 frame text and the browse-mode title suffix
(the class/method identity now lives in §28.2's pinned lines and the Stack window).

### 28.2 Sticky definition lines
When the enclosing CLASS declaration line is scrolled above the viewport (≤ the top line),
it is pinned as the first Source body row; when the enclosing METHOD/constructor
declaration is also out of view (≤ top+1 with the class pinned), it is pinned as the second
row — file, then class, then method, exactly the context that would otherwise be invisible.
Pinned rows are REAL rows: real line numbers, syntax, highlights; the body keeps its normal
top-anchored mapping (the pins simply cover the top rows; scrolling up reveals them and the
pins dissolve — nothing is pinned that could otherwise be seen). Declarations are found
lexically, scanning UP from the frame's current line, so the NEAREST declaration wins —
which disambiguates overloads, and a constructor pins its own definition line (never an
`<init>` rendering). Exec mode only (browse has no frame identity).

### 28.3 Scroll-only partial redraw
A pure scroll/selection-move key repaints ONLY the focused window (its rectangle is cleared
and redrawn) plus the status and help rows — every other window's cells stay untouched in
the back buffer, and no engine state is recomputed for them. Anything else — jumps, `o`,
frame selection, opening/closing any window or dialog, a terminal resize — keeps the full
render. Correctness rule: a scroll never moves the engine, so the skipped windows are
still exact.

### 28.4 Unpinned scrolling everywhere
Supersedes §20.13's pinned-offset semantics: the main list windows (Threads, Stack, Locals,
Records — the last previously did not scroll at all) scroll exactly like the dialogs — the
viewport moves freely and the selection is dragged along the nearest edge when it would
fall outside (`dragScroll`/`dragSel`, one shared rule). The Stack drag moves the VIEW
selection only; it never selects a frame (↑/↓ and Enter still do).

### 28.5 `.` (find object usage) — simple-name matching + landing-context chains
- An unqualified class query (no `.`/`$`) matches the SIMPLE class name only (the §15.1
  rule), so `buffer` no longer matches every class whose PACKAGE contains `.buffer.`;
  qualified queries still match the full binary name.
- The §24.3 containment chains are computed at the exec LANDING of the row's record —
  exactly where Enter puts the cursor — with roots drawn from EVERY stack frame's
  locals/args/`this` (innermost first) and higher search limits (depth 6, 600 expansions),
  so an instance that is not directly visible in Locals shows the path to a variable that
  IS explorable there.

### 28.6 Open-file browser: single-child chain expansion
Expanding a directory that contains exactly one subdirectory expands recursively until a
directory with more than one entry (or a leaf) is reached; collapsing does the reverse —
the directory plus every single-entry ancestor collapse together (stopping below a root or
at a multi-entry parent), the selection landing on the topmost collapsed row.

### 28.7 Go to line — Alt-L
Alt-L opens a small line-number modal for the shown file; Enter centres the chosen line
(exactly like `o` centres the execution line) and puts the source cursor on it; Esc
cancels. (Terminals do not deliver Command-key chords, so Option/Alt carries the binding.)

### 28.8 Structure window — `S`
`S` (shift-s) opens the Structure window for the class enclosing the CURRENT LINE IN THE
FILE (browse mode included — the outline is lexical, independent of execution): three
side-by-side, independently scrollable lists — fields | constructors & methods | inner
types. `←`/`→`/Tab switch the active list, ↑↓/PgUp/PgDn/g/G move in it, `a` toggles
appearance ↔ alphabetical ordering for all three lists at once, Enter jumps to the selected
member's definition (centred) and closes, Esc closes. The window keeps its state across
close/reopen until the shown class changes, then resets to the upper-left; the note line
shows the selected member's full declaration text.

### 28.9 Branch-resolution recording mode (engine side)
FORMAT.md §10/§11 gains the `branchOnly` scope — brackets + args + locals + fields +
BRANCH records, no LINE records. The engine treats such frames as first-class:
- A BRANCH record joins the exec set (stepping lands on it) and the line-info index
  EXACTLY when its frame executed no LINE record — line-mode frames, where every BRANCH
  immediately follows its LINE, never double-stop.
- A BRANCH cursor supplies the current line from its site; a branch-mode frame's shown
  line follows its last decided branch (`lineInfoIndices` back every line-oriented command:
  j/J line trace, run-to-line, exit-frame, the (file,line) site index).
- `frameHasLineInfo`/`threadHasLineInfo` replace the LINE-only predicates for the §18.3
  green treatment and the Threads/Stack colouring: a branch-mode frame has line info and
  renders like a line-traced one; only true enter/exit-only frames stay green.
- Landing legality is now per-index (`Engine.isExecLanding`), not per record type.

### 28.10 Two-step method entry — F7/⇧F7 (line and branch modes alike)
F7, when the NEXT exec record is a `METHOD_ENTER`, first STOPS on the call line without
moving the engine — the callee's name highlights on that line, the status names the call —
and the second F7 performs the entry; from a `METHOD_EXIT` cursor (chained calls like
`a().b()`) the CALLER frame is shown while armed, since the call line lives there. ⇧F7 is
symmetric: leaving an ENTER backward arrives AT its call site (armed, callee highlighted,
F7 re-enters), and a reverse step that would land ON an ENTER lands at the call site before
it instead — one logical step, one history entry. Any actual move disarms the stop. F8/⇧F8
keep their classic frame-sticky behaviour, and the engine peek (`peekExec`) never moves the
cursor, so no additional records are needed.
