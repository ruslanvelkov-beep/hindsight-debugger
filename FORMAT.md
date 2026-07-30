# Hindsight Recording Format & Hook API — v1

Single source of truth for the on-disk recording format, the runtime hook API, and the
agent configuration grammar. Implementations in `hooks/`, `agent/`, and `reader/` MUST
conform to this document exactly. Schema version: **4**.

Scope: method enter/exit (+args, +return/throw), local-variable writes, field writes
(before → after), object identity (+array length), bounded-depth shallow snapshots (with
container and array unfolding), string/boxed/enum values, source-line flow, and
conditional-branch conditions. NOT captured: array element stores, monitor events,
allocation events (first-seen records subsume them). Schema history: 1 = base; 2 =
snapshot `form` byte + first-seen display names; 3 = LINE/BRANCH records + array length
in first-seen; 4 = STACK_SNAPSHOT records (periodic live stack traces, §2.1/§3) with the
STACK blob kind and the METHODNAME/FILENAME rt-symbol kinds. The reader accepts all
four. Recorder ≥ v1.3 additionally emits nested baseline snapshots and container-refresh
snapshots (§7) — a purely behavioural addition within schema 3.

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
| `lines.tsv` | reader (derived) | §12.1 visited-line index — computed after recording or lazily on first use; safe to delete (rebuilt on demand) |

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
| `0x05` | `LINE` | LINE site (§5) | — | — | — | — |
| `0x06` | `BRANCH` | BRANCH site (§5; opcode in its `slot`) | — | — | int compares: first operand (2-operand only) | int compares: the (last) operand; ref compares: computed boolean result |
| `0x03` | `METHOD_EXIT` | method site | `this` id for constructors, else 0 | — | — | encoded return value; throwable object id when `EXCEPTIONAL` |
| `0x10` | `FIELD_WRITE` | field-write site | owner object id (0 for statics) | before-slot runtime tag (ref-typed fields only, else 0) | encoded old value | encoded new value |
| `0x20` | `LOCAL_WRITE` | local-write site | — | slot (capped 255) | — | encoded new value |
| `0x60` | `OBJECT_FIRST_SEEN` | **rt-symbol id** of the object's runtime class (enums: the *declaring* enum class) | new object id | — | arrays: element count (`BEFORE_KIND=INLINE`, tag `INT`); else 0 | enums: display-name blob offset (`AFTER_KIND=BLOB`, tag `STRING`, UTF8 constant name); else 0 |
| `0x61` | `SNAPSHOT` | 0 | object id | — | — | blob offset of snapshot payload (`AFTER_KIND=BLOB`) |
| `0x70` | `THREAD_START` | **rt-symbol id** of thread name | — | — | — | Java thread id (`Thread.getId()`) |
| `0x72` | `TIME_SYNC` | 0 | — | — | — | `System.currentTimeMillis()` |
| `0x73` | `STACK_SNAPSHOT` | 0 | — | — | — | blob offset of STACK payload (`AFTER_KIND=BLOB`) — schema 4 |

Ordering rules:
- Every `ARG` record for a call immediately follows its `METHOD_ENTER` in the same
  thread file (arg 0 first). `this` is NOT an arg record (it is ENTER's `target`).
- `OBJECT_FIRST_SEEN` is written (by whichever thread first registers the object) with a
  `seq` strictly lower than any record referencing that object id.
- `THREAD_START` is the first record of every thread file, immediately followed by a
  `TIME_SYNC`. A further `TIME_SYNC` is emitted every 65 536 records of that thread.
- `STACK_SNAPSHOT` (schema 4, `stackEvery > 0`): the thread's live Java stack at emission
  time — captured alongside the thread's FIRST business record and thereafter once every
  `stackEvery` records of that thread. Frames are innermost-first with the recorder's own
  frames (`io.hindsight.*`, the capture machinery) stripped, so the innermost remaining
  frame is (best effort) the instrumented method whose hook fired. Readers use the
  nearest preceding capture as the approximate uninstrumented-caller context of later
  records in the same thread; exactness is only guaranteed AT the capture record.
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
kind   : 1 byte  (1 = UTF8, 2 = SNAPSHOT, 3 = STACK — schema 4)
len    : 4 bytes LE — payload length
payload: len bytes
```

`UTF8` payload: raw UTF-8 bytes (already truncated).

`STACK` payload (schema 4 — a §2.1 `STACK_SNAPSHOT`'s live stack, innermost first):

```
count : 2 bytes — frames after stripping the recorder's own
per frame:
  classSym  : 4 bytes — rt-symbol id (kind RTCLASS, class binary name)
  methodSym : 4 bytes — rt-symbol id (kind METHODNAME, bare method name)
  fileSym   : 4 bytes — rt-symbol id (kind FILENAME, source file name; 0 if unknown)
  line      : 4 bytes — signed source line (-1/0 if unknown)
```

`SNAPSHOT` payload (bounded shallow dump — one level per payload, §7 depth rules; schema 2):

```
classSym  : 4 bytes  — rt-symbol id of runtime class
form      : 1 byte   — 0 FIELDS, 1 MAP, 2 SET, 3 LIST, 4 ARRAY
```

Form `FIELDS` (plain objects):

```
fieldCount: 2 bytes  — max 64 fields; accessible instance fields only, superclass
                       fields included (subclass-first order), statics excluded
per field:
  nameSym : 4 bytes  — rt-symbol id (kind FIELDNAME)
  tag     : 1 byte   — §2.3
  kind    : 1 byte   — §2.2 KIND encoding
  value   : 8 bytes  — per §2.4; BLOB kind points at a UTF8 entry written earlier in
                       this same blob file
```

Forms `MAP`/`SET`/`LIST`/`ARRAY` (unfolded containers — `java.base` implementations
and arrays only; a custom collection's iterator may run arbitrary application code and
is dumped as FIELDS instead):

```
totalSize : 4 bytes  — the container's size at dump time
count     : 2 bytes  — entries actually captured (max 64)
per entry (MAP: key entry then value entry, 2 per count; others: 1 per count):
  tag     : 1 byte   — §2.3
  kind    : 1 byte   — §2.2 KIND encoding
  value   : 8 bytes  — per §2.4
```

Schema 1 payloads have no `form` byte and are always FIELDS-shaped. Recorder ≥ v1.5: a
class that DECLARES no instance fields at all (and is not hidden or synthetic — capture
less lambdas stay silent) produces an EMPTY FIELDS payload (`fieldCount = 0`), letting
readers distinguish a genuinely stateless object (`{}`) from an unreadable one; a class
whose declared fields are all inaccessible still produces NO snapshot record (earlier
recorders emitted no record in either case). Enums are never snapshotted — their
constant name travels in their `OBJECT_FIRST_SEEN` record (§6).

## 4. Symbols — `symbols.tsv`, `rt-symbols.tsv`

UTF-8 text, one record per line: `id<TAB>kind<TAB>text`. Text has `\` → `\\`, tab →
`\t`, newline → `\n` escaped. Writers flush eagerly (a crashed run must still parse).

- `symbols.tsv` (agent-side, ids dense from 1): kinds `CLASS` (binary name, e.g.
  `com.hedera.node.app.workflows.ingest.IngestWorkflow`), `METHOD` (bare name), `FIELD`
  (bare name), `VAR` (bare name), `FILE` (source file name).
- `rt-symbols.tsv` (runtime-side, ids = `0x4000_0000 | n`, n dense from 1): kinds
  `THREAD`, `RTCLASS` (runtime class binary name), `FIELDNAME`, and (schema 4)
  `METHODNAME` / `FILENAME` for §3 STACK payload frames.

Readers MUST treat the two id spaces as one map (they cannot collide).

## 5. Sites — `sites.tsv`

One line per instrumented location, assigned at transform time, ids dense from 1:

`siteId<TAB>kind<TAB>classSym<TAB>fileSym<TAB>line<TAB>memberSym<TAB>desc<TAB>slot`

- kind `METHOD`: `memberSym` = method name symbol, `desc` = JVM method descriptor
  (literal text, not a symbol), `line` = first line number in body (0 if unknown),
  `slot` = `-1`. Access bit for static is appended to desc as a suffix `#s` when the
  method is static (readers need it to map ENTER args to parameter names). Recorder ≥
  v1.7 appends one more optional suffix `#p<slot>=<name>[|<slot>=<name>...]` carrying the
  PARAMETER NAMES by first-instruction LVT slot (from the LocalVariableTable, falling back
  to the MethodParameters attribute; omitted entirely when neither names the params) —
  e.g. `(IJ)V#s#p0=amount|1=memo`. Names are Java identifiers (no escaping needed).
  Readers strip both suffixes from `desc` and expose the slot→name map; recordings
  without `#p` keep the `argN` fallback.
- kind `FIELD`: `memberSym` = field name, `desc` = field type descriptor plus `#s`
  suffix when static, `line` = line of the write, `slot` = `-1`. `classSym` is the
  **field owner** class (may differ from the instrumented class).
- kind `LOCAL`: `memberSym` = variable name, `desc` = type descriptor, `line` = line of
  the store, `slot` = LVT slot index. `classSym` = declaring class of the method
  containing the store. NOTE: one site per **store instruction**, so several sites can
  share the same variable.
- kind `LINE`: `memberSym` = 0, `desc` = "", `line` = the source line, `slot` = -1.
  Deduplicated per `(class, line)` — one row however many times the line executes.
- kind `BRANCH`: `memberSym` = 0, `desc` = "", `line` = the source line, `slot` = the JVM
  conditional-jump opcode (e.g. `0xA2` = `IF_ICMPGE`). One site per jump instruction.

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
- Enums ARE registered (they can own mutable state and be `this`), but their
  `OBJECT_FIRST_SEEN` carries the constant name as a display-name blob (§2.1) and they
  are never snapshotted; readers render them as `ClassName.CONSTANT`.

## 7. Snapshots

Two triggers, same payload (reflective bounded dump per §3):

1. **First sight** (`snapshotFirstSeen`, default on): every object receives a baseline
   `SNAPSHOT` immediately after its `OBJECT_FIRST_SEEN` — the state from which later
   recorded field writes evolve the object.
2. **Sampling refresh** (`snapshotEvery`): with probability `1/snapshotEvery` per
   object-id resolution (cheap countdown re-armed from a per-thread xorshift), the
   worker re-snapshots the resolved object — refreshing long-lived objects whose state
   drifted via uninstrumented code (container mutations above all). `snapshotEvery=0`
   disables. A refresh dump of a FIELDS-form object additionally re-dumps the current
   contents of up to 16 of its direct **container-valued** fields (map/set/list/array),
   even when those containers are already registered — the checkpoint that makes
   evolving collections inspectable (recorder ≥ v1.3).

**Depth** (`snapshotDepth`, default 2, recorder ≥ v1.3): every dump runs at a nesting
level; the triggering dump is level 1. Objects newly registered while dumping at level
`L` (nested references) always get `OBJECT_FIRST_SEEN`; when `L < snapshotDepth` they
also receive a baseline dump of their own, at level `L+1` (queue-driven off the pending
first-seen list — never recursive). Levels count *dumps*, not field hops: depth 2 means
"objects discovered inside a snapshot get their own snapshot; objects discovered inside
THAT one get first-seen only". `snapshotDepth=1` reproduces the pre-v1.3 depth-1
containment.

**Always-dump containers** (recorder ≥ v1.7): the §3-unfolded containers — `java.base`
maps/sets/collections and ALL arrays — are baseline-dumped at first sight at ANY nesting
level (depth-exempt, like the wrappers below), so `HashMap`/`ConcurrentHashMap`/`HashSet`/
`ArrayList`/`Vector`/... contents are inspectable no matter how deep the discovery, with
the usual 64-entry cap. Unfolding stays restricted to `java.base` implementations (§3's
safety rule: a custom collection's iterator may run arbitrary application code).

**Always-dump wrappers** (recorder ≥ v1.3): the `java.base` single-value wrappers —
`AtomicInteger`/`AtomicLong`/`AtomicBoolean`/`AtomicReference` (read via their `final`
`get()`) and `StringBuilder`/`StringBuffer` (read via `toString()`, `maxString`
truncation) — are baseline-dumped at first sight at ANY nesting level (depth-exempt),
and the trigger-2 refresh walk re-dumps them alongside containers (their state drifts
invisibly the same way). Their payload is a FIELDS form with a single synthetic
`value` field carrying the current content; no reflection is involved, so no module
opens are needed. The `snapshotAlways` config key (§10) lists ADDITIONAL class-name
prefixes exempted from the depth limit (dumped per the normal reflective/unfolding
rules; not part of the refresh walk). Note `String` and boxed primitives need none of
this — they are never object-registered and always travel by value (§2.4/§6).

Payload shape by object kind — plain objects dump fields; `java.base` maps, sets,
lists/collections and **all arrays** are UNFOLDED into their elements (§3 forms; arrays
as ARRAY-form payloads with element values per §2.4, 64-entry cap, true length in
`totalSize` — unfolded since the schema-2/3 recorders). Enums are never snapshotted (§6).
Field-less vs unreadable classes follow the §3 empty-payload rule (recorder ≥ v1.5).
Reflection failures (inaccessible module, hidden class) silently skip. Snapshot values
never recurse *within one payload*: nested refs become object ids, nested strings become
UTF8 blob entries, everything else per §2.4. All dumps are bounded: ≤ 64 fields/entries
per payload, ≤ 16 container re-dumps per refresh, nested fan-out bounded by the payload
caps and the depth limit.

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
`Path outDir; int maxString; int snapshotEvery; boolean snapshotFirstSeen;
int snapshotDepth; String[] snapshotAlways; int stackEvery; int recordBufKB;
int blobBufKB; long maxTotalMB; boolean autostart; int sweepMillis;`

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
| `snapshotOpen` | empty | prefixes whose packages are opened to the hooks module for reflective §7 snapshots WITHOUT instrumentation — for data/model classes (e.g. PBJ records, `java.time`) outside the instrumentation scope whose contents dumps must read. Recorder ≥ v1.5: matching packages of every boot-layer module (incl. `java.base`) are opened EAGERLY at premain — classes there may load before any transform event — and later-defined modules keep being handled lazily on class load |
| `autostart` | `true` | start recording at premain |
| `trigger` | none | `binary.class.Name#methodName` — first entry starts recording (implies `autostart=false` unless given) |
| `maxString` | `512` | max recorded string length (chars) |
| `snapshotEvery` | `8192` | §7 sampling refresh; `0` = off |
| `snapshotFirstSeen` | `true` | §7 baseline snapshot of every object at first sight |
| `snapshotDepth` | `2` | §7 nested-baseline depth; `1` = pre-v1.3 behaviour (must be ≥ 1) |
| `snapshotAlways` | empty | prefixes always baseline-dumped at first sight regardless of `snapshotDepth` (§7 always-dump; the `java.base` wrappers are exempt unconditionally and need no entry) |
| `stackEvery` | `1024` | §2.1 STACK_SNAPSHOT cadence: capture the live stack with each thread's first business record and every N records thereafter; `0` = no captures at all (schema 4) |
| `lines` | `true` | emit LINE records at each source line (FULL scope) — §2.1 flow |
| `branches` | `true` | emit BRANCH records at each conditional jump (FULL scope) — §2.1 condition values |
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
  - `find --dir D --object ID [--limit N]` (ID: bare number or `Class#id`) → single raw
    pass locating one object id: its `OBJECT_FIRST_SEEN`, any `SNAPSHOT`s (rendered), and
    a count + capped sample of records referencing it (as `this`/owner, or a before/after
    value slot). Record-level references only — nested references inside another object's
    snapshot payload are not counted. Exit 0 whether or not the id is present.
  - `stats --dir D` → per-type and per-thread record counts, blob sizes, top methods
    by enter count, object count, wall-clock span from TIME_SYNCs.
  - `verify --dir D` → invariant checks, non-zero exit on violation: per-thread seq
    strictly increasing; ENTER/EXIT balance (truncated-tail tolerant); every
    `location`/symbol id resolvable; every blob offset lands on `0xB5` magic with sane
    length; `OBJECT_FIRST_SEEN` precedes (by seq) every reference to that id; aux tags
    and flags within spec.
- Value rendering: INLINE by tag (`true`/`'c'`/`3.14`), BLOB strings quoted (with `…`
  when TRUNCATED), object ids as `SimpleClassName#id`, `null`, `void`.
- `STACK_SNAPSHOT` rendering (schema 4): one dump line `stack-snapshot (<n> frames)`
  followed (in dump) by the frames as `at Class.method (File:line)`, innermost first.
  `verify` additionally checks each STACK payload: count within payload bounds and every
  frame's class/method/file symbol id resolvable (fileSym 0 allowed).

### 12.1 Derived visited-line index — `lines.tsv` (reader ≥ v2.4)

Part of the recording's *calculated* info: for every source file present in the recording,
the set of lines the recording touched at least once. Computed by ONE full scan of all
record files — either right after taking the recording (`lines` CLI command) or lazily the
first time a consumer needs it (`LineIndex.openOrBuild`) — then persisted in the recording
dir so both the CLI and the viewer read it back for free.

- A record marks `(file, line)` iff its `location` is a **site** (types `METHOD_ENTER`,
  `METHOD_EXIT`, `ARG`, `LINE`, `BRANCH`, `FIELD_WRITE`, `LOCAL_WRITE`) whose
  `fileSym ≠ 0` and `line > 0` — i.e. executed statement lines (`LINE`/`BRANCH`), write
  lines (`FIELD_WRITE`/`LOCAL_WRITE`) and the declaration line of every entered method
  (so enter/exit-only methods still surface). `OBJECT_FIRST_SEEN`/`THREAD_START` carry
  symbol ids, not sites, and are skipped.
- File key: the class's package path + the site's file name, `/`-joined
  (`com/foo/Bar.java`; bare `Bar.java` in the default package) — exactly the relative
  path the viewer's source resolution probes under each source root, so keys match
  on-disk files without further mapping. Multiple classes in one file merge into one key.
- On-disk format: header line `# hindsight lines v1 records=<total record count>`, then
  one TSV row per file, sorted by key: `<key>\t<runs>` where runs are sorted,
  comma-separated line numbers with `a-b` ranges (`14,16-19,42`).
- Loading validates the header (version + record count matches the recording); a
  mismatched or corrupt file is rebuilt, atomically replaced (temp file + move; run
  values above the class-file line limit 65535 are treated as corruption). The index is
  a pure derivation — deleting `lines.tsv` merely costs the next open a rescan — and
  persisting it is BEST-EFFORT: an unwritable recording dir serves the freshly built
  index from memory and simply rescans on the next open.
- CLI: `lines --dir D` → builds (if absent/stale) and prints per-file visited-line
  counts plus totals; exit 0.

## 13. Versioning

`manifest.properties` keys: `schema=4`, `recorder=<project version>`, `jvm`, `pid`,
`startEpochMillis`, `args`, and (recorder ≥ v2.1) `sourceCommit` = the target project's git
HEAD commit hash at record time (empty when the project has no `.git`), so a viewer can
offer to check out the exact sources the recording was made against. Readers refuse unknown major schema; this reader accepts
1 (no snapshot `form` byte, no display names, no LINE/BRANCH), 2 (adds form byte + display
names), 3 (adds LINE/BRANCH records and array length in first-seen), and 4 (adds
STACK_SNAPSHOT records, the STACK blob kind and the METHODNAME/FILENAME rt-symbol kinds).
