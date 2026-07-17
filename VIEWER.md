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

`viewer --dir <recordingDir> [--source <root1:root2:...>]... [--repo <dir>]... [--tid N]`

- `--dir` (required): a recording directory (`Recording.open`).
- `--source`: colon-separated source roots (each a directory that is the package root, i.e.
  `<root>/com/foo/Bar.java`). Repeatable.
- `--repo`: a directory tree under which every `*/src/main/java` and `*/src/test/java`
  directory is auto-discovered and added as a source root. Repeatable.
- `--tid`: initial thread to focus (default: the thread of the first LINE record by global
  `seq`; if none, the lowest tid).
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
