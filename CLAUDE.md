# Working on Hindsight with Claude

Hindsight is a backward-in-time (omniscient) debugger for the JVM: a Java agent instruments a
program's bytecode, records every state-changing event to disk, and a terminal viewer lets you
reconstruct any thread's or object's state at any past step — and step forward **and backward**
through execution — after the program has finished. It is not a live debugger and not a profiler.

This file orients a fresh Claude session. Read it first, then the two authoritative specs.

## The specs are the source of truth

- **`FORMAT.md`** — the on-disk recording format, the runtime hook API, and the agent
  configuration grammar (§10). The `hooks/`, `agent/`, and `reader/` modules must conform to it
  exactly. If code and `FORMAT.md` disagree, treat `FORMAT.md` as authoritative and reconcile.
- **`VIEWER.md`** — the viewer's behaviour, section by section (windows, keybindings, stepping,
  reconstruction rules). The viewer is specified here first; features are added as new numbered
  sections. When changing viewer behaviour, update the matching `VIEWER.md` section in the same
  change.

Both specs are dense and current — prefer them over inferring behaviour from the code.

## Module map

| Module | What it is | Constraints |
|---|---|---|
| `hooks/` | Bootstrap-classpath runtime: the static methods instrumented bytecode calls, the per-thread ring buffer, object-ID registry, disk writers. | Pure `java.base` only; `--release 21`. |
| `agent/` | The `-javaagent` (ByteBuddy + ASM): bytecode visitors that wire calls into matched classes; the `AgentConfig` scope grammar. | `--release 21`. |
| `reader/` | Parses a recording, reconstructs values, and a CLI (`dump`/`find`/`stats`/`verify`/`lines`). | JDK 25. |
| `viewer/` | The interactive terminal debugger (lanterna TUI) over a recording. Depends on `reader`. | JDK 25. |
| `sample/` | A tiny `BankSim` app used as an instrumentation fixture and the recording demo. | — |

## Build, test, run

```bash
./gradlew build                       # compile + all tests (strict: -Xlint:all -Werror, JDK 25 toolchain)
./gradlew :viewer:installDist         # produces viewer/build/install/viewer/bin/viewer
./gradlew :reader:installDist         # produces reader/build/install/reader/bin/reader
```

Tests are self-contained (synthetic recordings assembled byte-by-byte from `FORMAT.md`) **except**:
- The `*RealRecordingTest` / real-capture tests self-skip unless you point them at real recordings:
  `./gradlew test -Dhindsight.recordingsDir=<dir containing recordings>` (or the
  `HINDSIGHT_RECORDINGS_DIR` env var).
- `reader`'s end-to-end test records `sample` in a subprocess; it self-skips when the jars aren't
  built.

## Record a program, then view it

Fastest path — record the bundled sample and open it:

```bash
scripts/record-sample.sh
# then, as printed:
viewer/build/install/viewer/bin/viewer --dir <recording-dir> --repo .
```

To record **your own** program, attach the agent and name the packages to instrument:

```bash
java -javaagent:<agent.jar>=hooks=<hooks.jar>,outDir=<dir>,include=<your.package.> -jar app.jar
```

Instrumentation scopes (longest-prefix wins; see `FORMAT.md` §10/§11):
- `include=` — full detail (method enter/exit + args + local & field writes + line/branch flow).
- `branchOnly=` — like `include` but records BRANCH decisions and writes with **no** per-line
  records (smaller recordings; you still see which branch ran and every variable transition).
- `callsOnly=` — method enter/exit + args only.
- `exclude=` — never instrument.

The viewer resolves source from `--source <root>` / `--repo <dir>` and, when the recording's
manifest carries a `sourceCommit`, reads the sources from git at that exact commit (so a drifted
checkout still shows the code that actually ran).

## Viewer keys (see `VIEWER.md` for the full set)

`c/t/s/l/r` focus Source/Threads/Stack/Locals/Records · `F7`/`Shift+F7` step in/back (two-step at a
call: first press stops on the call line, second enters) · `F8`/`Shift+F8` step over/back · `o`
return to the executing line · `/` browse method calls & instances · `.` find object usage · `F`
open a file · `S` structure of the current class · `Alt-F` search · `Alt-L` go to line · `F1` help
· `q` quit.

## Conventions

- Java: `--release 21` for `hooks`/`agent`, JDK 25 for `reader`/`viewer`; the build is strict
  (`-Xlint:all -Werror`) — warnings fail. Keep new code warning-clean.
- Every source file starts with `// SPDX-License-Identifier: GPL-3.0-or-later` (this project is GPL-3.0;
  see `LICENSE`).
- Match the surrounding code's style and comment density. Comments explain constraints the code
  can't show, not what the next line does.
- When you change behaviour, update the corresponding `FORMAT.md` / `VIEWER.md` section in the same
  change, and add or extend a test (synthetic recordings for engine/format; pure-helper tests for
  the TUI pieces).
