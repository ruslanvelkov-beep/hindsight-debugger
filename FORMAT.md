# Hindsight Recording Format & Hook API — v1

Single source of truth for the on-disk recording format, the runtime hook API, and the
agent configuration grammar. Implementations in `hooks/`, `agent/`, and `reader/` MUST
conform to this document exactly. Schema version: **1**.

Scope of v1 (deliberate): method enter/exit (+args, +return/throw), local-variable
writes, field writes (before → after), object identity, occasional 1-level shallow
snapshots, string/boxed-primitive values by value. NOT in v1: array stores, monitor
events, allocation events (first-seen records subsume them), line-step records.

## 1. Recording directory

One recording = one directory (`outDir`). The agent creates it (parents included). If it
exists and is non-empty, the agent instead creates a sibling `"<outDir>-<n>"` with the
smallest free `n ≥ 2` and logs the substitution. Contents:

| File | Writer | Contents |
|---|---|---|
| `manifest.properties` | runtime | schema/recorder versions, JVM info, pid, start epoch millis, echoed agent args |
| `records-<tid>.bin` | runtime | fixed 40-byte event records for thread `tid` (5-digit zero-padded, e.g. `records-00003.bin`) |
| `blob-<tid>.bin` | runtime | variable-length payloads referenced by that thread's records |
| `symbols.tsv` | agent | transform-time symbols (classes, methods, fields, vars, files) |
| `sites.tsv` | agent | transform-time site table (one row per instrumented location) |
| `rt-symbols.tsv` | runtime | runtime symbols (thread names, runtime class names, snapshot field names) |
| `agent.log` | agent | diagnostics; transform failures (fail-open — class left uninstrumented) |
| `TRUNCATED` | runtime | marker file, created iff recording stopped early (size cap / internal error); first line = reason |

All binary integers are **little-endian**. `tid` is a process-local dense counter
(1, 2, 3, …) assigned on a thread's first recorded event — NOT the Java thread id
(the mapping is recorded via `THREAD_START`).

## 2. Record layout — `records-<tid>.bin`

Fixed 40-byte records, record `i` at byte offset `i × 40`. No file header.

| Offset | Size | Field | Meaning |
|---|---|---|---|
| 0 | 8 | `seq` | global monotonic sequence number (shared `AtomicLong` across threads, starts at 1); total order for cross-thread merge |
| 8 | 1 | `type` | record type, §2.1 |
| 9 | 1 | `flags` | bit field, §2.2 |
| 10 | 2 | `aux` | `[tag: high byte][idx: low byte]` — `tag` per §2.3; `idx` = arg index, local slot (capped at 255; 255 = "≥255, see site"), or arg count for ENTER |
| 12 | 4 | `location` | site id (§5) — for `OBJECT_FIRST_SEEN`/`THREAD_START` a symbol id instead (see type) |
| 16 | 8 | `target` | object id (§6); 0 = null / not applicable |
| 24 | 8 | `before` | encoded value slot (§2.4) |
| 32 | 8 | `after` | encoded value slot (§2.4) |

### 2.1 Record types

| Value | Name | location | target | aux idx | before | after |
|---|---|---|---|---|---|---|
| `0x02` | `METHOD_ENTER` | method site | `this` id (0 for static **and** for constructors) | arg count (capped 255) | — | — |
| `0x04` | `ARG` | method site | — | arg index | — | encoded arg value |
| `0x03` | `METHOD_EXIT` | method site | `this` id for constructors, else 0 | — | — | encoded return value; throwable object id when `EXCEPTIONAL` |
| `0x10` | `FIELD_WRITE` | field-write site | owner object id (0 for statics) | before-slot runtime tag (ref-typed fields only, else 0) | encoded old value | encoded new value |
| `0x20` | `LOCAL_WRITE` | local-write site | — | slot (capped 255) | — | encoded new value |
| `0x60` | `OBJECT_FIRST_SEEN` | **rt-symbol id** of the object's runtime class | new object id | — | — | — |
| `0x61` | `SNAPSHOT` | 0 | object id | — | — | blob offset of snapshot payload (`AFTER_KIND=BLOB`) |
| `0x70` | `THREAD_START` | **rt-symbol id** of thread name | — | — | — | Java thread id (`Thread.getId()`) |
| `0x72` | `TIME_SYNC` | 0 | — | — | — | `System.currentTimeMillis()` |

Ordering rules:
- Every `ARG` record for a call immediately follows its `METHOD_ENTER` in the same
  thread file (arg 0 first). `this` is NOT an arg record (it is ENTER's `target`).
- `OBJECT_FIRST_SEEN` is written (by whichever thread first registers the object) with a
  `seq` strictly lower than any record referencing that object id.
- `THREAD_START` is the first record of every thread file, immediately followed by a
  `TIME_SYNC`. A further `TIME_SYNC` is emitted every 65 536 records of that thread.
- `METHOD_EXIT` with `EXCEPTIONAL` set means the frame unwound with a throwable. If a
  method's exit is missing at end-of-file, the frame was live at shutdown (reader must
  tolerate truncated tails).

### 2.2 Flags

| Bit | Name | Meaning |
|---|---|---|
| 0–1 | `BEFORE_KIND` | 0 = NONE, 1 = INLINE, 2 = OBJECT_ID, 3 = BLOB |
| 2–3 | `AFTER_KIND` | same encoding |
| 4 | `EXCEPTIONAL` | on `METHOD_EXIT`: frame unwound via throw |
| 5 | `STATIC` | on `FIELD_WRITE`: static field (redundant with site table; convenience) |
| 6 | `TRUNCATED` | a string in this record's blob payload was truncated to `maxString` |
| 7 | `BOXED` | value was a boxed primitive (`Integer` etc.) recorded INLINE with its primitive tag |

### 2.3 Value type tags (aux high byte)

`0` REF, `1` INT, `2` LONG, `3` FLOAT, `4` DOUBLE, `5` BOOLEAN, `6` BYTE, `7` CHAR,
`8` SHORT, `9` STRING, `10` NULL, `12` VOID.

For primitive-typed `FIELD_WRITE` and for `LOCAL_WRITE` the tag reflects the
**declared** type; for `ARG`/`METHOD_EXIT` it reflects the runtime encoding decision
(§2.4). For **reference-typed** `FIELD_WRITE` the declared type is recoverable from the
site row, so the record instead carries the two slots' **runtime** tags: the tag byte
holds the AFTER slot's runtime tag and the aux idx byte the BEFORE slot's — this is what
lets a reader decode a boxed primitive stored in a reference-typed field (each slot is
independently encoded per §2.4, so before may be OBJECT_ID while after is INLINE+BOXED).

### 2.4 Value encoding

Given a value to record, the runtime encodes `(kind, tag, 8-byte slot)`:

1. **Primitive** (from a type-specialised hook): `kind=INLINE`, tag = primitive tag.
   Bits: int-likes sign-extended to 64-bit (`boolean` → 0/1, `char` zero-extended);
   `float` → `Float.floatToRawIntBits` zero-extended; `double` → `doubleToRawLongBits`;
   `long` as-is.
2. **null**: `kind=NONE`, `tag=NULL`, slot = 0.
3. **String**: UTF-8 bytes of `s.substring(0, min(len, maxString))` written as a blob
   entry (kind `UTF8`); `kind=BLOB`, `tag=STRING`, slot = blob offset; set record flag
   `TRUNCATED` if cut. Per-thread memoisation MAY dedup identical strings (same offset).
4. **Boxed primitive** (`Integer`, `Long`, `Short`, `Byte`, `Character`, `Boolean`,
   `Float`, `Double`): unbox → rule 1, plus record flag `BOXED`.
5. **Any other reference**: `kind=OBJECT_ID`, `tag=REF`, slot = object id (§6).

## 3. Blob layout — `blob-<tid>.bin`

Concatenated entries; a "blob offset" points at an entry's first byte. Entry:

```
magic  : 1 byte  = 0xB5
kind   : 1 byte  (1 = UTF8, 2 = SNAPSHOT)
len    : 4 bytes LE — payload length
payload: len bytes
```

`UTF8` payload: raw UTF-8 bytes (already truncated).

`SNAPSHOT` payload (1-level shallow dump):

```
classSym  : 4 bytes  — rt-symbol id of runtime class
fieldCount: 2 bytes  — max 64 fields; instance fields only, superclass fields included
                       (subclass-first order), statics excluded
per field:
  nameSym : 4 bytes  — rt-symbol id (kind FIELDNAME)
  tag     : 1 byte   — §2.3
  kind    : 1 byte   — §2.2 KIND encoding
  value   : 8 bytes  — per §2.4; BLOB kind points at a UTF8 entry written earlier in
                       this same blob file
```

## 4. Symbols — `symbols.tsv`, `rt-symbols.tsv`

UTF-8 text, one record per line: `id<TAB>kind<TAB>text`. Text has `\` → `\\`, tab →
`\t`, newline → `\n` escaped. Writers flush eagerly (a crashed run must still parse).

- `symbols.tsv` (agent-side, ids dense from 1): kinds `CLASS` (binary name, e.g.
  `com.hedera.node.app.workflows.ingest.IngestWorkflow`), `METHOD` (bare name), `FIELD`
  (bare name), `VAR` (bare name), `FILE` (source file name).
- `rt-symbols.tsv` (runtime-side, ids = `0x4000_0000 | n`, n dense from 1): kinds
  `THREAD`, `RTCLASS` (runtime class binary name), `FIELDNAME`.

Readers MUST treat the two id spaces as one map (they cannot collide).

## 5. Sites — `sites.tsv`

One line per instrumented location, assigned at transform time, ids dense from 1:

`siteId<TAB>kind<TAB>classSym<TAB>fileSym<TAB>line<TAB>memberSym<TAB>desc<TAB>slot`

- kind `METHOD`: `memberSym` = method name symbol, `desc` = JVM method descriptor
  (literal text, not a symbol), `line` = first line number in body (0 if unknown),
  `slot` = `-1`. Access bit for static is appended to desc as a suffix `#s` when the
  method is static (readers need it to map ENTER args to parameter names).
- kind `FIELD`: `memberSym` = field name, `desc` = field type descriptor plus `#s`
  suffix when static, `line` = line of the write, `slot` = `-1`. `classSym` is the
  **field owner** class (may differ from the instrumented class).
- kind `LOCAL`: `memberSym` = variable name, `desc` = type descriptor, `line` = line of
  the store, `slot` = LVT slot index. `classSym` = declaring class of the method
  containing the store. NOTE: one site per **store instruction**, so several sites can
  share the same variable.

`fileSym` = FILE symbol of the class's source file (0 if unknown). `line` = 0 when no
LineNumberTable.

Known v1 limitation: sites (and CLASS symbols) are keyed by binary name with no
classloader dimension — two same-named classes in different loaders share one site id
and the first loader's file/line info. Acceptable for single-boot-layer JPMS targets
like the consensus node; revisit if plugin-style loaders enter scope.

## 6. Object identity

- Object ids are process-global, monotonically increasing positive `long`s from a
  registry keyed on reference identity (weak keys; ids never reused). 0 = null.
- On first registration the registering thread emits `OBJECT_FIRST_SEEN` (with the
  runtime class rt-symbol) before the referencing record.
- `String` and boxed primitives are never registered — always by value (§2.4).

## 7. Snapshots

With probability `1/snapshotEvery` per **object-id resolution** (cheap counter +
xorshift, per-thread), the worker emits a `SNAPSHOT` of that object: reflective 1-level
dump per §3. Reflection failures (inaccessible module, hidden class) silently skip.
`snapshotEvery=0` disables. Snapshot values never recurse: nested refs become object
ids, nested strings become UTF8 blob entries, everything else per §2.4.

## 8. Hook API (bootstrap classpath, package `io.hindsight.hooks`)

Instrumented bytecode may ONLY call these static methods (plus
`io.hindsight.runtime.HindsightRuntime` for agent-side control). All hooks:
never throw; no-op when recording inactive; guard against reentrancy
(`ThreadWorker.inHook`). Signatures are frozen:

```java
public final class MethodHooks {
    public static void enter(int siteId, Object self, Object[] args);
    public static void enterCtor(int siteId, Object[] args);          // before super(); no self
    public static void exit(int siteId, Object boxedReturnOrNull, int returnTag); // returnTag per §2.3 (VOID=12, REF=0, or prim tag)
    public static void exitCtor(int siteId, Object self);             // normal ctor completion
    public static void exitExceptional(int siteId, Throwable thrown); // non-ctor only
}

public final class FieldWriteHook {
    // owner == null for statics (or PUTFIELD about to NPE — recorded then aborts anyway)
    public static void recordIntLike(int siteId, Object owner, int before, int after, int tag); // INT/BOOLEAN/BYTE/CHAR/SHORT
    public static void recordLong  (int siteId, Object owner, long before, long after);
    public static void recordFloat (int siteId, Object owner, float before, float after);
    public static void recordDouble(int siteId, Object owner, double before, double after);
    public static void recordObject(int siteId, Object owner, Object before, Object after);
}

public final class LocalWriteHook {
    public static void writeIntLike(int siteId, int slot, int value, int tag);
    public static void writeLong   (int siteId, int slot, long value);
    public static void writeFloat  (int siteId, int slot, float value);
    public static void writeDouble (int siteId, int slot, double value);
    public static void writeObject (int siteId, int slot, Object value);
}
```

Runtime control surface used by the agent (package `io.hindsight.runtime`):

```java
HindsightRuntime.boot(RuntimeConfig cfg)   // once, from premain, before transformers install
HindsightRuntime.recordingDir()            // Path — the RESOLVED dir (after the "-<n>" sibling rule);
                                           // the agent writes symbols.tsv / sites.tsv / agent.log here
HindsightRuntime.setTriggerSite(int siteId) // agent calls when it transforms the trigger method
HindsightRuntime.startRecording()           // manual / autostart
HindsightRuntime.stopRecording()
```

Trigger semantics: `MethodHooks.enter` on the trigger site while not yet recording
calls `startRecording()` and records that very ENTER (the trigger invocation is the
first business record). `autostart=true` bypasses the trigger.

`RuntimeConfig` (plain fields, constructed by the agent, hooks-module class):
`Path outDir; int maxString; int snapshotEvery; int recordBufKB; int blobBufKB;
long maxTotalMB; boolean autostart; int sweepMillis;`

## 9. Runtime architecture requirements

- **Per-thread workers** (`ThreadLocal` + registry map for shutdown): two direct
  `ByteBuffer` chunks (records `recordBufKB`, blob `blobBufKB`). Full chunk → handed to
  the writer queue with `(tid, stream, buffer)`; worker takes a fresh buffer from a
  small free-list or allocates.
- **Single writer daemon** (`hindsight-writer`): `ArrayBlockingQueue` (capacity 256);
  producers block when full (backpressure, no data loss). Writer owns all
  `FileChannel`s (lazy open per `(tid, stream)`). Writer thread's own ThreadWorker is
  permanently suspended.
- **Global `seq`**: one `AtomicLong.getAndIncrement()` per record.
- **Shutdown hook**: `stopRecording()`, quiesce (~50 ms), steal each live worker's
  partial chunks, drain queue, flush + close channels, write manifest end fields.
  Last-instant events may be lost; that is accepted and documented.
- **Size cap**: writer tracks total bytes; over `maxTotalMB` → `stopRecording()`,
  create `TRUNCATED` marker.
- **Internal errors**: never propagate to app threads. First error of each kind logged
  to stderr with `hindsight:` prefix; recording continues if safe, else clean stop +
  `TRUNCATED`.
- Threads beyond 4 096 workers: further threads are not recorded (log once).

## 10. Agent configuration grammar

`-javaagent:hindsight-agent.jar=key=value,key=value,...` — top-level separator `,`,
list-valued keys use `|` between elements. Keys:

| Key | Default | Meaning |
|---|---|---|
| `hooks` | required | path to hooks jar (appended to bootstrap search) |
| `outDir` | `.hindsight` | recording directory (§1) |
| `include` | required | package/class binary-name prefixes → FULL instrumentation (brackets + args + locals + fields) |
| `callsOnly` | empty | prefixes → brackets + args only (no locals/fields) |
| `exclude` | empty | prefixes excluded entirely; wins over include/callsOnly |
| `autostart` | `true` | start recording at premain |
| `trigger` | none | `binary.class.Name#methodName` — first entry starts recording (implies `autostart=false` unless given) |
| `maxString` | `512` | max recorded string length (chars) |
| `snapshotEvery` | `8192` | §7; `0` = off |
| `recordBufKB` | `256` | per-thread record chunk |
| `blobBufKB` | `128` | per-thread blob chunk |
| `maxTotalMB` | `204800` | recording size cap |
| `sweepMillis` | `500` | background sweeper flushes every worker's partial chunks at this interval, bounding SIGKILL data loss; `0` = off |
| `debug` | `false` | verbose agent logging to `agent.log` + stderr |

Matching: longest-prefix wins among {include, callsOnly, exclude}; non-matching classes
untouched. Hard ignores regardless of config: `io.hindsight.`, `java.`, `javax.`,
`jdk.`, `sun.`, `com.sun.`, `net.bytebuddy.`, and array/hidden/synthetic-loader classes.

## 11. Instrumentation requirements (agent module)

- ByteBuddy `AgentBuilder` installed in premain (before app classes load; no
  retransformation needed). Fail-open per class: any transform error → original
  bytecode + `agent.log` entry.
- **JPMS**: hooks live in the bootstrap unnamed module; instrumented classes may be in
  named modules ⇒ ensure read edges (`assureReadEdgeTo`/`redefineModule`) for every
  instrumented module to the hooks' module.
- **Method brackets** via `Advice` (inlined): `@Advice.OnMethodEnter` →
  `MethodHooks.enter(siteId, this-or-null, args)`; `@Advice.OnMethodExit(onThrowable =
  Throwable.class, backupArguments = false)` → `exit`/`exitExceptional`. The per-method
  `siteId` int constant is bound via a custom `Advice.OffsetMapping` annotation
  (`@SiteId`). Constructors use a separate Advice pair (`enterCtor`/`exitCtor`, no
  `onThrowable` — JVMS forbids wrapping the uninitialized-this region).
- Skipped methods: `abstract`, `native`, ACC_BRIDGE, empty default ctors MAY be
  instrumented (keep — they show object creation). Synthetic `lambda$…` methods ARE
  instrumented (lambda bodies matter).
- **Locals + fields** via a `MethodNode`-based pass (asm-tree) so the LVT and line
  numbers are fully known before splicing; splice straight-line, stack-balanced
  sequences ONLY (no new branches/handlers/merge points) ⇒ existing stack-map frames
  stay valid; temps appended at `maxLocals` (grow it; never reuse before a frame node);
  write with `COMPUTE_MAXS` only (never `COMPUTE_FRAMES` — it class-loads during
  transform).
- **Local writes**: after every `xSTORE` and `IINC` whose slot has a matching LVT entry
  covering the instruction *after* the store: reload and call the matching
  `LocalWriteHook`. No LVT entry (synthetic temp, no `-g`) → skip silently.
- **Field writes**: before each `PUTFIELD`/`PUTSTATIC` in include-scope: capture old
  value with a `GETFIELD`/`GETSTATIC` via scratch locals (exact sequences below), call
  the matching `FieldWriteHook`, then perform the original write. In constructors,
  PUTFIELDs before the first `INVOKESPECIAL <init>` are skipped (uninitialized `this`
  cannot escape to the hook; conservative over-skip accepted).

Field-write splice — uniform two-temp sequence, valid for category-1 AND category-2
values (no `SWAP`/`DUP_X` juggling). `PUTFIELD`, stack `[o, v]`:
`xSTORE tV; ASTORE tO; LDC site; ALOAD tO; ALOAD tO; GETFIELD f; xLOAD tV; [LDC tag;]
INVOKESTATIC hook; ALOAD tO; xLOAD tV; (original PUTFIELD)`.
`PUTSTATIC`, stack `[v]`:
`xSTORE tV; LDC site; ACONST_NULL; GETSTATIC f; xLOAD tV; [LDC tag;]
INVOKESTATIC hook; xLOAD tV; (original PUTSTATIC)`.
(The injected `GETFIELD` may NPE one instruction earlier than the original `PUTFIELD`
would have — behaviour-equivalent. Note `ASTORE tO` is also why pre-`super()` PUTFIELDs
must be skipped: storing an uninitialized `this` is a verify error.)

- **callsOnly scope**: only Advice brackets, no MethodNode pass.
- The MethodNode pass must iterate a snapshot of the original instruction list (never
  instrument its own splices) and must run on the ORIGINAL method code (i.e. compose
  so the pass sees pre-Advice bytecode; verify in tests — the LVT-only rule also
  protects against Advice temps).

## 12. Reader requirements (`reader/` module, JDK 25 ok, not bootstrap-constrained)

- Parse a recording dir; lazily map record files; merge streams by `seq`.
- CLI (`io.hindsight.reader.Main`):
  - `dump --dir D [--thread N] [--limit N] [--from-seq S] [--method SUBSTR] [--depth-max N]`
    → indented call tree, one line per record, e.g.
    `[seq 12360 t3] Account#123.balance: 1000 -> 900 (TransferService.java:88)`
  - `stats --dir D` → per-type and per-thread record counts, blob sizes, top methods
    by enter count, object count, wall-clock span from TIME_SYNCs.
  - `verify --dir D` → invariant checks, non-zero exit on violation: per-thread seq
    strictly increasing; ENTER/EXIT balance (truncated-tail tolerant); every
    `location`/symbol id resolvable; every blob offset lands on `0xB5` magic with sane
    length; `OBJECT_FIRST_SEEN` precedes (by seq) every reference to that id; aux tags
    and flags within spec.
- Value rendering: INLINE by tag (`true`/`'c'`/`3.14`), BLOB strings quoted (with `…`
  when TRUNCATED), object ids as `SimpleClassName#id`, `null`, `void`.

## 13. Versioning

`manifest.properties` keys: `schema=1`, `recorder=<project version>`, `jvm`, `pid`,
`startEpochMillis`, `args`. Readers refuse unknown major schema.
