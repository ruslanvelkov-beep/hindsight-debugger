# Hindsight

A backward-in-time debugger for the JVM: a Java agent instruments your code,
records every state-changing event to disk, and a terminal viewer lets you
reconstruct any thread or object's state at any past step — and step **forward
and backward** through execution — after the program has finished.

Not a live debugger. Not a profiler. Hindsight answers "what was the value of
`successUsd` at line 1014" — and "how did it get there" — without you having to
set a breakpoint first.

## Modules

- **`hooks/`** — pure-JDK runtime loaded on the bootstrap classloader: the static
  methods instrumented bytecode calls (one per event type), the per-thread ring
  buffer, object-ID registry, and disk writers. Depends on nothing outside `java.base`.
- **`agent/`** — the `-javaagent` (ByteBuddy + ASM): bytecode visitors that wire
  those calls into the classes you name, plus the instrumentation-scope grammar.
- **`reader/`** — parses a recording and reconstructs values; a headless CLI
  (`dump` / `find` / `stats` / `verify` / `lines`).
- **`viewer/`** — the interactive terminal debugger (lanterna TUI) over a recording.
  Depends on `reader`.
- **`sample/`** — a tiny `BankSim` app used as an instrumentation fixture and the
  recording demo.

`FORMAT.md` (recording format + agent grammar) and `VIEWER.md` (viewer behaviour)
are the authoritative specs.

## Build

```bash
./gradlew build                 # compile + tests (strict: -Xlint:all -Werror, JDK 25 toolchain)
./gradlew :viewer:installDist   # -> viewer/build/install/viewer/bin/viewer
./gradlew :reader:installDist   # -> reader/build/install/reader/bin/reader
```

## Record a program

Attach the agent and name the packages to instrument (the trailing dot is a prefix):

```bash
java -javaagent:agent/build/libs/hindsight-agent.jar=hooks=hooks/build/libs/hooks.jar,outDir=.hindsight,include=your.package. \
     -jar your-app.jar
```

Instrumentation scopes — set one per package prefix; the **longest prefix wins**:

- **`include=`** — full detail: method enter/exit + args + local & field writes + line/branch flow.
- **`branchOnly=`** — branch decisions + variable writes, but **no** per-line records (smaller recordings).
- **`callsOnly=`** — method enter/exit + args only.
- **`exclude=`** — never instrument.

Fastest end-to-end demo — record the bundled sample and print how to open it:

```bash
scripts/record-sample.sh
```

## Inspect from the CLI

The `reader` reconstructs a finished recording without re-running anything:

```bash
reader stats  --dir <rec>                                    # record counts, top methods, wall-clock span
reader dump   --dir <rec> [--thread N] [--method SUBSTR] [--from-seq S] [--limit N]
reader find   --dir <rec> --object <id | Class#id>           # first-seen, snapshots, every reference
reader verify --dir <rec>                                    # integrity check (exit 1 on violations)
```

Every `dump` / `find` line is prefixed with its **`[seq N]`** record number — the
stable coordinate you cite as evidence and hand to the viewer.

## Viewer

The viewer is an interactive terminal debugger that replays a finished recording:
it follows the recorded execution line by line and lets you move **forward and
backward** in time while inspecting the exact state at every step. Launch it on a
recording, optionally opening straight on a record:

```bash
viewer --dir <rec> --repo .                # open at the start
viewer --dir <rec> --repo . --seq 481207   # open on record (seq) 481207
viewer --dir <rec> --repo . --seqs 9986854,10373437,10414976   # Records pre-filled with these, focused on the first
```

- **Step through time** — step into / over (`F7` / `F8`) and **reverse** step into /
  over (`Shift+F7` / `Shift+F8`); `o` snaps back to the executing line. (At a call,
  `F7` stops on the call line first; press again to enter.)
- **Run to a line** — reach the next / previous time execution hits the **selected**
  source line: `Ctrl+R` (forward) / `Ctrl+Q` (backward) stay in the current thread
  (bound per-thread), while `Ctrl+T` (forward) / `Ctrl+W` (backward) do the same
  across **any** thread, in global record order.
- **Visit an exact record** — launch with `--seq N` (or `--last`), or focus the Records
  window (`r`) and press `r` again to jump to any record number. `--seqs N,N,…` opens
  focused on a Records list pre-filled with your chosen records, positioned on the first;
  in the Records window, moving the selection (`↑`/`↓`) jumps there — no Enter needed.
- **Follow method calls** — `/` browses every method the run entered (regex) and jumps
  to any single invocation; `e` / `E` exit the current frame forward / backward.
- **Inspect locals & fields** — the Locals window (`l`) shows the live locals and object
  fields at the cursor, expandable into nested objects; `v` shows a variable's full write
  history.
- **Explore objects** — `.` finds every use of an object; browse instances by class and
  open any one to watch it evolve over the run.
- **Stack & threads** — the Stack window (`s`) shows the call stack at the cursor
  (unrecorded frames greyed); the Threads window (`t`) lists every thread — select one to
  follow its timeline.
- **Read the source** — `F` opens any project file or class (`:line` optional), `S` shows
  the structure of the current class, `Alt-L` goes to a line, `Alt-F` searches within the
  file; source is resolved from `--repo` / `--source` (and from git at the recording's
  `sourceCommit` when present).
- **Orient** — the status row always shows `seq · thread · Class.method · file:line`, and
  the Records window keeps a navigable trail of everywhere you've landed. `F1` help, `q` quit.

Focus a window with `c` (Source Code) · `t` (Threads) · `s` (Stack) · `l` (Locals) ·
`r` (Records), or `Tab` to cycle.

## On-disk format

After a run the recording directory holds the following (file names reflect the
storage they serve, not the project):

| File | Contains |
|---|---|
| `main.records` | Fixed-size 32-byte step records, indexed by `record# × 32` |
| `values.blob` | Variable-length payloads (strings, snapshots, oversize values) |
| `snapshots.idx` | `(object_id, step#) → values offset` for periodic object dumps |
| `objects.tab` | `object_id → class_id, first_seen, tail_snapshot_offset` |
| `frames.tab` | `frame_id → method_id, parent_frame, thread_id, entry/exit step#` |
| `classes.tab` | `class_id → class symbol, field layout` |
| `class_fields.tab` | Per-class field rows (`field_id, name_symbol, type, ref-or-primitive`) |
| `locations.tab` | `(file_id, line) → location_id` dedup |
| `symbols.txt` | Line-indexed text: class names, method names, file names, thread names |
| `manifest.txt` | JVM version, schema version, recorder version, capture time |

See `FORMAT.md` for the full schema.

## License

GPL-3.0-or-later. See [`LICENSE`](LICENSE). Every source file carries an
`SPDX-License-Identifier: GPL-3.0-or-later` header.
