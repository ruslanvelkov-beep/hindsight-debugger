# Hindsight

A backward-in-time debugger for the JVM: instruments your code via a Java agent,
records every state-changing event to disk, and lets you reconstruct any thread
or object's state at any past step after the program has finished.

Not a live debugger. Not a profiler. Hindsight answers "what was the value of
`successUsd` at line 1014" — and "how did it get there" — without you having
to set a breakpoint first.

## Modules

- **`hooks/`** — pure-JDK runtime hooks loaded onto the bootstrap classloader.
  Contains the static methods that instrumented bytecode calls (one per recorded
  event type), plus the per-thread ring buffer, object-ID registry, and disk
  writers. Cannot depend on anything outside `java.base`.
- **`agent/`** — fat Java agent jar. Includes ByteBuddy + ASM and the bytecode
  visitors that wire calls to the hooks into matched classes. Has a `Premain-Class`
  manifest entry, so it's started via `-javaagent`.

## Running

```bash
./gradlew build

java \
    -javaagent:agent/build/libs/hindsight-agent.jar=hooks=hooks/build/libs/hooks.jar,outDir=.hindsight \
    -cp <your-classpath> \
    your.MainClass
```

After the run, the `.hindsight/` directory contains the recording. File names
inside reflect the storage they serve, not the project name:

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

## Status

Pre-implementation scaffolding. The build compiles, but no transformers are wired
up yet. See the TODOs in `HindsightAgent`, `HindsightRuntime`, and `ThreadWorker`.

Build order (planned):

1. `HindsightRuntime` + `ThreadWorker` + `ObjectIdRegistry` + on-disk writers — get
   the data path correct in isolation, fed by hand-written events.
2. Method enter/exit injector (ByteBuddy `Advice`) — first real instrumentation pass.
3. Field-write injector (ASM `MethodVisitor`).
4. Array-store, monitor, throw injectors.
5. Periodic snapshot sampler.

Each step is independently usable.

## License

GPL-3.0-or-later. See [`LICENSE`](LICENSE). Every source file carries an
`SPDX-License-Identifier: GPL-3.0-or-later` header.
