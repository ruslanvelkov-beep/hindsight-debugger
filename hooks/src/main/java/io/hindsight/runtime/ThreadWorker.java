// SPDX-License-Identifier: Apache-2.0
package io.hindsight.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * Per-thread event assembler (FORMAT.md §9). One instance per recorded thread; the hot path
 * for every event is a {@code ThreadLocal.get()} followed by a buffer push into one of two
 * chunk buffers (fixed 40-byte records, variable-length blob entries). Full chunks are handed
 * to the single {@link ChunkWriter} daemon.
 *
 * <p>Owned by exactly one thread, but the runtime's shutdown hook must be able to
 * <em>steal</em> the partial chunks of every live worker. All buffer-touching paths therefore
 * synchronize on {@code this}: uncontended for the owner (nanoseconds on modern JVMs) and the
 * only thing that makes the steal produce whole records instead of a torn 40-byte tail.
 *
 * <p>The {@link #inHook} flag is the reentrancy guard demanded by §8: anything the recorder
 * itself causes to run (classloading during reflection, {@code toString} never — we don't call
 * it — but static initializers of instrumented classes absolutely) re-enters the hooks on the
 * same thread and must see "already recording an event" and back off.
 */
public final class ThreadWorker {

    /**
     * Set by a hook for the duration of one event so re-entrant hook invocations no-op.
     * Same-thread access only; deliberately not volatile.
     */
    public boolean inHook;

    /** When true this thread records nothing (writer thread, worker cap, shutdown). */
    public volatile boolean suspended;

    private static final int STRING_MEMO_CAP = 4096;

    private final HindsightRuntime.Instance rt;
    private final int tid;
    private final boolean denied;

    private ByteBuffer recordBuf;
    private ByteBuffer blobBuf;

    /** Bytes of the blob stream already handed to the writer; offsets = base + position. */
    private long blobFileBase;

    private long recordCount;

    /** Dedup of identical String instances/values → blob offset (§2.4 rule 3), bounded. */
    private final HashMap<String, Long> stringMemo;

    // ---- snapshot sampling state (§7): cheap countdown re-armed from a xorshift PRNG ----
    private long xorshiftState;
    private long snapshotCountdown;

    /**
     * Nesting level of the snapshot dump currently executing on this thread (§7): 0 = no dump
     * in progress, 1 = a triggering dump (first-sight baseline or sampling refresh), and a
     * baseline dump of an object discovered at level {@code L} runs at {@code L + 1}. Non-zero
     * doubles as the reentrancy guard the old {@code inSnapshot} boolean provided: while any
     * dump runs, the sampling countdown must not fire and queued dumps must not start.
     */
    private int dumpLevel;

    // ---- scratch slots for the single-value encoder (single-threaded by ownership) ----
    private int encKind;
    private int encTag;
    private int encExtraFlags;
    private long encSlot;

    // ---- per-worker scratch for pre-encoded ENTER args (aux idx caps at 255, §2) ----
    private final int[] argScratchFlagsTag = new int[256];
    private final long[] argScratchSlots = new long[256];

    ThreadWorker(HindsightRuntime.Instance rt, int tid, String threadName, long javaThreadId) {
        this.rt = rt;
        this.tid = tid;
        this.denied = false;
        this.stringMemo = new HashMap<>();
        long seed = System.nanoTime() ^ (javaThreadId * 0x9E3779B97F4A7C15L);
        this.xorshiftState = seed == 0 ? 0x2545F4914F6CDD1DL : seed;
        rearmSnapshotCountdown();
        // §2.1: THREAD_START is the first record of every thread file, then a TIME_SYNC.
        final int nameSym = rt.symbols.intern(RuntimeSymbols.Kind.THREAD, threadName);
        emit(Wire.TYPE_THREAD_START, Wire.KIND_INLINE << Wire.AFTER_KIND_SHIFT, Wire.TAG_LONG, 0,
                nameSym, 0, 0, javaThreadId);
        emitTimeSync();
    }

    /** Sentinel constructor: a permanently-suspended worker that owns nothing. */
    private ThreadWorker() {
        this.rt = null;
        this.tid = 0;
        this.denied = true;
        this.suspended = true;
        this.stringMemo = null;
    }

    /**
     * A permanently-suspended sentinel handed to threads that must never record (the writer
     * daemon, threads past the 4096-worker cap) so the hooks' cheap {@code suspended} check
     * short-circuits them without re-probing the registry on every event.
     */
    static ThreadWorker denied() {
        return new ThreadWorker();
    }

    int tid() {
        return tid;
    }

    boolean isDenied() {
        return denied;
    }

    // =====================================================================================
    // High-level record emitters, called by the hook classes (hence public).
    // =====================================================================================

    /** METHOD_ENTER followed immediately by one ARG record per argument (§2.1). */
    public synchronized void recordMethodEnter(int siteId, Object self, Object[] args) {
        final long targetId = self == null ? 0 : resolveObjectId(self);
        final int argc = args == null ? 0 : args.length;
        // Encode every argument BEFORE emitting the ENTER: encoding can emit
        // OBJECT_FIRST_SEEN (and snapshot) records, which must land before the ENTER
        // so §2.1's "each ARG immediately follows its ENTER" ordering holds.
        for (int i = 0; i < argc && i < argScratchFlagsTag.length; i++) {
            encodeRef(args[i]);
            argScratchFlagsTag[i] = ((encKind << Wire.AFTER_KIND_SHIFT) | encExtraFlags) << 8 | (encTag & 0xFF);
            argScratchSlots[i] = encSlot;
        }
        emit(Wire.TYPE_METHOD_ENTER, 0, Wire.TAG_REF, argc, siteId, targetId, 0, 0);
        for (int i = 0; i < argc && i < argScratchFlagsTag.length; i++) {
            emit(Wire.TYPE_ARG, argScratchFlagsTag[i] >>> 8, argScratchFlagsTag[i] & 0xFF, i,
                    siteId, 0, 0, argScratchSlots[i]);
        }
    }

    /**
     * METHOD_EXIT for a normal (non-constructor) return. {@code returnTag} is the declared
     * return shape per §2.3: VOID, REF, or a primitive tag when the instrumentation shim boxed
     * a primitive return value (recorded INLINE without the BOXED flag — the boxing is our own
     * calling-convention artifact, not a property of the program).
     */
    public synchronized void recordMethodExit(int siteId, Object boxedReturnOrNull, int returnTag) {
        if (returnTag == Wire.TAG_VOID) {
            emit(Wire.TYPE_METHOD_EXIT, 0, Wire.TAG_VOID, 0, siteId, 0, 0, 0);
        } else if (returnTag == Wire.TAG_REF) {
            encodeRef(boxedReturnOrNull);
            emit(Wire.TYPE_METHOD_EXIT, (encKind << Wire.AFTER_KIND_SHIFT) | encExtraFlags, encTag, 0,
                    siteId, 0, 0, encSlot);
        } else if (boxedReturnOrNull == null) {
            // A primitive tag with no value would be an agent bug; degrade to a NULL record.
            emit(Wire.TYPE_METHOD_EXIT, 0, Wire.TAG_NULL, 0, siteId, 0, 0, 0);
        } else {
            final long bits = boxedBits(boxedReturnOrNull, returnTag);
            emit(Wire.TYPE_METHOD_EXIT, Wire.KIND_INLINE << Wire.AFTER_KIND_SHIFT, returnTag, 0,
                    siteId, 0, 0, bits);
        }
    }

    /** METHOD_EXIT of a constructor: target carries the freshly-initialized {@code this} (§2.1). */
    public synchronized void recordCtorExit(int siteId, Object self) {
        final long selfId = self == null ? 0 : resolveObjectId(self);
        emit(Wire.TYPE_METHOD_EXIT, 0, Wire.TAG_VOID, 0, siteId, selfId, 0, 0);
    }

    /** METHOD_EXIT with EXCEPTIONAL set; {@code after} carries the throwable's object id (§2.1). */
    public synchronized void recordMethodExitExceptional(int siteId, Throwable thrown) {
        final long thrownId = thrown == null ? 0 : resolveObjectId(thrown);
        emit(Wire.TYPE_METHOD_EXIT,
                Wire.FLAG_EXCEPTIONAL | (Wire.KIND_OBJECT_ID << Wire.AFTER_KIND_SHIFT),
                Wire.TAG_REF, 0, siteId, 0, 0, thrownId);
    }

    /** FIELD_WRITE of a primitive-typed field; bits are already §2.4-rule-1 encoded. */
    public synchronized void recordFieldWriteInline(
            int siteId, Object owner, long beforeBits, long afterBits, int tag) {
        final long ownerId = owner == null ? 0 : resolveObjectId(owner);
        final int flags = Wire.KIND_INLINE
                | (Wire.KIND_INLINE << Wire.AFTER_KIND_SHIFT)
                | (owner == null ? Wire.FLAG_STATIC : 0);
        emit(Wire.TYPE_FIELD_WRITE, flags, tag, 0, siteId, ownerId, beforeBits, afterBits);
    }

    /** FIELD_WRITE of a reference-typed field; before/after independently encoded per §2.4. */
    public synchronized void recordFieldWriteObject(int siteId, Object owner, Object before, Object after) {
        final long ownerId = owner == null ? 0 : resolveObjectId(owner);
        encodeRef(before);
        final int kindB = encKind;
        final int tagB = encTag;
        final long slotB = encSlot;
        final int extraB = encExtraFlags;
        encodeRef(after);
        final int flags = kindB
                | (encKind << Wire.AFTER_KIND_SHIFT)
                | extraB
                | encExtraFlags
                | (owner == null ? Wire.FLAG_STATIC : 0);
        // §2.3: the declared type of the field lives in the site row, so the record's tag byte
        // carries the AFTER slot's runtime tag and the aux idx byte the BEFORE slot's — this is
        // what lets the reader decode a boxed primitive stored in a reference-typed field.
        emit(Wire.TYPE_FIELD_WRITE, flags, encTag, tagB, siteId, ownerId, slotB, encSlot);
    }

    /** LOCAL_WRITE of a primitive-typed local; bits are already §2.4-rule-1 encoded. */
    public synchronized void recordLocalWriteInline(int siteId, int slot, long bits, int tag) {
        emit(Wire.TYPE_LOCAL_WRITE, Wire.KIND_INLINE << Wire.AFTER_KIND_SHIFT, tag, slot,
                siteId, 0, 0, bits);
    }

    /** LOCAL_WRITE of a reference-typed local, value encoded per §2.4. */
    public synchronized void recordLocalWriteObject(int siteId, int slot, Object value) {
        encodeRef(value);
        emit(Wire.TYPE_LOCAL_WRITE, (encKind << Wire.AFTER_KIND_SHIFT) | encExtraFlags, encTag, slot,
                siteId, 0, 0, encSlot);
    }

    /** LINE: execution crossed into the source line described by {@code siteId} (§7 flow). */
    public synchronized void recordLine(int siteId) {
        emit(Wire.TYPE_LINE, 0, Wire.TAG_VOID, 0, siteId, 0, 0, 0);
    }

    /** BRANCH on one int operand (compare vs 0): after = the operand value (§2.1). */
    public synchronized void recordBranchInt1(int siteId, int value) {
        emit(Wire.TYPE_BRANCH, Wire.KIND_INLINE << Wire.AFTER_KIND_SHIFT, Wire.TAG_INT, 0,
                siteId, 0, 0, value);
    }

    /** BRANCH on two int operands: before = a, after = b; the reader derives the result. */
    public synchronized void recordBranchInt2(int siteId, int a, int b) {
        emit(Wire.TYPE_BRANCH, Wire.KIND_INLINE | (Wire.KIND_INLINE << Wire.AFTER_KIND_SHIFT),
                Wire.TAG_INT, 0, siteId, 0, a, b);
    }

    /** BRANCH on one reference operand (null test): after = the computed boolean result. */
    public synchronized void recordBranchRefNull(int siteId, boolean result) {
        emit(Wire.TYPE_BRANCH, Wire.KIND_INLINE << Wire.AFTER_KIND_SHIFT, Wire.TAG_BOOLEAN, 0,
                siteId, 0, 0, result ? 1L : 0L);
    }

    /** BRANCH on two reference operands (identity ==/!=): after = the computed boolean result. */
    public synchronized void recordBranchRefCmp(int siteId, boolean result) {
        emit(Wire.TYPE_BRANCH, Wire.KIND_INLINE << Wire.AFTER_KIND_SHIFT, Wire.TAG_BOOLEAN, 0,
                siteId, 0, 0, result ? 1L : 0L);
    }

    // ---- pending OBJECT_FIRST_SEEN reservations (§6): seq claimed under the registry bin
    // ---- lock (cheap), bytes + symbol interning materialised outside it (may block on I/O).
    private long[] pendingFsIds = new long[4];
    private long[] pendingFsSeqs = new long[4];
    private Object[] pendingFsObjects = new Object[4];
    private int[] pendingFsLevels = new int[4];
    private int pendingFsCount;

    // ---- pending baseline dumps (§7 depth rules): objects whose FIRST_SEEN is out but whose
    // ---- own dump must wait for the outermost drain loop — dumps are never recursive.
    private long[] pendingDumpIds = new long[4];
    private Object[] pendingDumpObjects = new Object[4];
    private int[] pendingDumpLevels = new int[4];
    private int pendingDumpCount;

    /**
     * Called by {@link ObjectIdRegistry} from inside its map-bin lock: reserves the record's
     * {@code seq} (strictly lower than any later reference, §2.1) without doing any I/O.
     * Always invoked on this worker's own thread (the registering thread). The strong
     * reference to the object is short-lived: it is cleared by the drain that immediately
     * follows on the same thread. The entry carries its §7 discovery level: an object
     * registered while a level-{@code L} dump is executing is discovered at {@code L + 1}
     * (top-level registrations, {@code dumpLevel} 0, are discovered at level 1).
     */
    synchronized void notePendingFirstSeen(long id, Object obj) {
        if (pendingFsCount == pendingFsIds.length) {
            // First-sight snapshots can register many nested objects before the outer drain
            // loop consumes them — grow rather than drop.
            pendingFsIds = java.util.Arrays.copyOf(pendingFsIds, pendingFsCount * 2);
            pendingFsSeqs = java.util.Arrays.copyOf(pendingFsSeqs, pendingFsCount * 2);
            pendingFsObjects = java.util.Arrays.copyOf(pendingFsObjects, pendingFsCount * 2);
            pendingFsLevels = java.util.Arrays.copyOf(pendingFsLevels, pendingFsCount * 2);
        }
        pendingFsIds[pendingFsCount] = id;
        pendingFsSeqs[pendingFsCount] = rt.nextSeq();
        pendingFsObjects[pendingFsCount] = obj;
        pendingFsLevels[pendingFsCount] = dumpLevel + 1;
        pendingFsCount++;
    }

    /**
     * Materialises reserved first-seen records; called right after the bin lock is released.
     *
     * <p>Reentrant by design: a §7 baseline snapshot resolves nested field references, each of
     * which notes-and-drains its own first-seen through this same method. Entries are
     * therefore <em>popped before processing</em>, so a nested drain only ever sees its own
     * additions, and FIFO order keeps per-thread-file records in ascending seq order — the
     * reserved FIRST_SEEN seq must reach the file before any later-seq record this thread
     * emits.
     *
     * <p>Dumps are decoupled from FIRST_SEEN emission (§7 depth rules): an entry discovered at
     * level {@code M} queues a baseline dump of its own iff {@code M ≤ snapshotDepth} and
     * first-sight snapshots are on. The queue is only run by the <em>outermost</em> drain
     * ({@code dumpLevel} 0); a drain re-entered from inside a dump merely appends, and the
     * outer loop picks the additions up — dumping is queue-driven, never recursive.
     */
    synchronized void drainPendingFirstSeen() {
        while (pendingFsCount > 0) {
            final long seq = pendingFsSeqs[0];
            final long id = pendingFsIds[0];
            final Object obj = pendingFsObjects[0];
            final int level = pendingFsLevels[0];
            pendingFsCount--;
            System.arraycopy(pendingFsSeqs, 1, pendingFsSeqs, 0, pendingFsCount);
            System.arraycopy(pendingFsIds, 1, pendingFsIds, 0, pendingFsCount);
            System.arraycopy(pendingFsObjects, 1, pendingFsObjects, 0, pendingFsCount);
            System.arraycopy(pendingFsLevels, 1, pendingFsLevels, 0, pendingFsCount);
            pendingFsObjects[pendingFsCount] = null;
            emitObjectFirstSeenAt(seq, id, obj);
            if (rt.snapshotFirstSeen && (level <= rt.snapshotDepth || alwaysDump(obj))) {
                // §7: baseline snapshot at first sight; later field writes evolve it.
                // Always-dump wrappers and snapshotAlways-matched classes are depth-exempt.
                enqueuePendingDump(id, obj, level);
            }
        }
        if (dumpLevel == 0) {
            runPendingDumps();
        }
    }

    private void enqueuePendingDump(long id, Object obj, int level) {
        if (pendingDumpCount == pendingDumpIds.length) {
            pendingDumpIds = java.util.Arrays.copyOf(pendingDumpIds, pendingDumpCount * 2);
            pendingDumpObjects = java.util.Arrays.copyOf(pendingDumpObjects, pendingDumpCount * 2);
            pendingDumpLevels = java.util.Arrays.copyOf(pendingDumpLevels, pendingDumpCount * 2);
        }
        pendingDumpIds[pendingDumpCount] = id;
        pendingDumpObjects[pendingDumpCount] = obj;
        pendingDumpLevels[pendingDumpCount] = level;
        pendingDumpCount++;
    }

    /**
     * Executes queued baseline dumps FIFO until the queue is empty. Only ever entered with
     * {@code dumpLevel} 0 (asserted by the callers' guards): while an entry's dump runs at its
     * own level, nested registrations re-enter {@link #drainPendingFirstSeen}, which emits
     * their FIRST_SEEN immediately and appends further dump entries this same loop consumes.
     */
    private void runPendingDumps() {
        while (pendingDumpCount > 0) {
            final long id = pendingDumpIds[0];
            final Object obj = pendingDumpObjects[0];
            final int level = pendingDumpLevels[0];
            pendingDumpCount--;
            System.arraycopy(pendingDumpIds, 1, pendingDumpIds, 0, pendingDumpCount);
            System.arraycopy(pendingDumpObjects, 1, pendingDumpObjects, 0, pendingDumpCount);
            System.arraycopy(pendingDumpLevels, 1, pendingDumpLevels, 0, pendingDumpCount);
            pendingDumpObjects[pendingDumpCount] = null;
            dumpLevel = level;
            try {
                emitSnapshot(obj, id);
                if (level == 1) {
                    lastBaselineId = id; // suppress an immediate duplicate sampler snapshot
                }
            } catch (Throwable t) {
                HindsightRuntime.reportInternalError("first-seen-snapshot", t);
            } finally {
                dumpLevel = 0;
            }
        }
    }

    /**
     * OBJECT_FIRST_SEEN with the §2.1 extras: enums carry their constant name as a display-name
     * blob in the {@code after} slot (the readable identity of a singleton is its constant, not
     * a numeric id), and their class symbol is the declaring enum class — constant-specific
     * bodies compile to anonymous subclasses whose names ({@code Foo$1}) help nobody.
     */
    private void emitObjectFirstSeenAt(long seq, long id, Object obj) {
        if (obj instanceof Enum<?> e) {
            final int classSym =
                    rt.symbols.intern(RuntimeSymbols.Kind.RTCLASS, e.getDeclaringClass().getName());
            final byte[] name = e.name().getBytes(StandardCharsets.UTF_8);
            final long offset = writeBlobEntry(Wire.BLOB_KIND_UTF8, name, name.length);
            if (offset >= 0) {
                emitWithSeq(seq, Wire.TYPE_OBJECT_FIRST_SEEN,
                        Wire.KIND_BLOB << Wire.AFTER_KIND_SHIFT, Wire.TAG_STRING, 0,
                        classSym, id, 0, offset);
                return;
            }
            emitWithSeq(seq, Wire.TYPE_OBJECT_FIRST_SEEN, 0, Wire.TAG_REF, 0, classSym, id, 0, 0);
            return;
        }
        final Class<?> cls = obj.getClass();
        final int classSym = rt.symbols.intern(RuntimeSymbols.Kind.RTCLASS, cls.getName());
        if (cls.isArray()) {
            // Array length in the before slot (INLINE INT) so the reader can show byte[8192]#id
            // at every reference site, not just where a snapshot happens to unfold it (§2.1).
            final int len = java.lang.reflect.Array.getLength(obj);
            emitWithSeq(seq, Wire.TYPE_OBJECT_FIRST_SEEN, Wire.KIND_INLINE, Wire.TAG_INT, 0,
                    classSym, id, len, 0);
            return;
        }
        emitWithSeq(seq, Wire.TYPE_OBJECT_FIRST_SEEN, 0, Wire.TAG_REF, 0, classSym, id, 0, 0);
    }

    /**
     * Shutdown-hook path: hands whatever partial chunks this worker holds to the writer.
     * Synchronized against the owner so only whole records are stolen; events the owner emits
     * after the steal land in fresh buffers that are never flushed — the documented
     * "last-instant events may be lost" tradeoff of §9.
     */
    synchronized void stealBuffers() {
        if (denied) {
            return;
        }
        flushRecords();
        flushBlob();
    }

    // =====================================================================================
    // Value encoding (§2.4)
    // =====================================================================================

    /**
     * Encodes one reference value into the {@code enc*} scratch slots. Resolution happens
     * before the scratch is written because resolving an object id can recursively encode
     * nested values (snapshot sampling) and would clobber half-written scratch state.
     */
    private void encodeRef(Object v) {
        if (v == null) {
            encKind = Wire.KIND_NONE;
            encTag = Wire.TAG_NULL;
            encSlot = 0;
            encExtraFlags = 0;
            return;
        }
        if (v instanceof String s) {
            encodeString(s);
            return;
        }
        final int boxedTag = boxedTag(v);
        if (boxedTag >= 0) {
            encKind = Wire.KIND_INLINE;
            encTag = boxedTag;
            encSlot = boxedBits(v, boxedTag);
            encExtraFlags = Wire.FLAG_BOXED;
            return;
        }
        final long id = resolveObjectId(v); // may recurse; must precede scratch writes
        encKind = Wire.KIND_OBJECT_ID;
        encTag = Wire.TAG_REF;
        encSlot = id;
        encExtraFlags = 0;
    }

    /** Strings longer than this are recorded but never memoised — the memo keys hold the FULL string. */
    private static final int STRING_MEMO_MAX_LEN = 4096;

    /** §2.4 rule 3: UTF-8 blob entry, {@code maxString} truncation, per-thread memoisation. */
    private void encodeString(String s) {
        final boolean truncated = s.length() > rt.maxString;
        final boolean memoisable = s.length() <= STRING_MEMO_MAX_LEN;
        Long offset = memoisable ? stringMemo.get(s) : null;
        if (offset == null) {
            final String cut = truncated ? s.substring(0, rt.maxString) : s;
            final byte[] bytes = cut.getBytes(StandardCharsets.UTF_8);
            offset = writeBlobEntry(Wire.BLOB_KIND_UTF8, bytes, bytes.length);
            if (offset >= 0 && memoisable) {
                if (stringMemo.size() >= STRING_MEMO_CAP) {
                    stringMemo.clear(); // crude but bounded; dedup is an optimisation, not a right
                }
                stringMemo.put(s, offset);
            }
        }
        if (offset < 0) {
            // Blob stream unavailable (entry larger than a chunk / writer gone): degrade to null.
            encKind = Wire.KIND_NONE;
            encTag = Wire.TAG_NULL;
            encSlot = 0;
            encExtraFlags = 0;
            return;
        }
        encKind = Wire.KIND_BLOB;
        encTag = Wire.TAG_STRING;
        encSlot = offset;
        encExtraFlags = truncated ? Wire.FLAG_TRUNCATED : 0;
    }

    /**
     * Object-id resolution with §7 snapshot sampling: roughly one resolution in
     * {@code snapshotEvery} also emits a shallow SNAPSHOT of the object — a level-1 refresh
     * dump, followed by the recorder-≥v1.3 container-field re-dumps and by baseline dumps of
     * any objects the refresh discovered within the depth limit.
     */
    private long resolveObjectId(Object o) {
        final long id = ObjectIdRegistry.idFor(o, this);
        // If idFor's drain just emitted this object's first-sight baseline, a sampler
        // snapshot on the same resolution would be an immediate byte-equivalent duplicate.
        // Only the OUTERMOST resolution may consume (and clear) the marker: nested
        // resolutions from level-2+ queued dumps run through here before the outer
        // resolution returns, and clearing unconditionally would wipe the marker they
        // did not set — re-enabling exactly the duplicate this suppresses.
        final boolean justBaselined;
        if (dumpLevel == 0) {
            justBaselined = id != 0 && id == lastBaselineId;
            lastBaselineId = 0;
        } else {
            justBaselined = false;
        }
        if (id != 0 && rt.snapshotEvery > 0 && dumpLevel == 0 && --snapshotCountdown <= 0) {
            rearmSnapshotCountdown();
            if (!justBaselined) {
                try {
                    dumpLevel = 1; // nested resolutions inside the dump must not snapshot again
                    emitSnapshot(o, id);
                    refreshContainerFields(o);
                } catch (Throwable t) {
                    HindsightRuntime.reportInternalError("snapshot", t);
                } finally {
                    dumpLevel = 0;
                }
                runPendingDumps(); // §7 depth rules: baselines of objects the refresh discovered
            }
        }
        return id;
    }

    /** Object id whose first-sight baseline the drain emitted last; consumed by the sampler. */
    private long lastBaselineId;

    /**
     * Test seam: primes the §7 sampling countdown so the next {@code snapshotEvery > 0}
     * object-id resolution fires a refresh dump deterministically — the xorshift re-arm makes
     * the natural countdown impossible to force from a test. Same-thread use only.
     */
    synchronized void forceSnapshotCountdownForTests(long value) {
        this.snapshotCountdown = value;
    }

    private void rearmSnapshotCountdown() {
        long x = xorshiftState;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        xorshiftState = x;
        final long every = rt == null ? 0 : rt.snapshotEvery;
        // Uniform on [1, 2·every] → mean ≈ every, i.e. probability ≈ 1/snapshotEvery.
        snapshotCountdown = every <= 0 ? Long.MAX_VALUE : 1 + ((x >>> 1) % (2 * every));
    }

    // =====================================================================================
    // Snapshots (§3 payload, §7 policy)
    // =====================================================================================

    /**
     * §7 payload dispatch: arrays and {@code java.base} maps/collections are unfolded into
     * their elements — the interesting state of a {@code HashMap} is its entries, not its
     * bucket table. Unfolding is restricted to {@code java.base} implementations because a
     * custom collection's iterator can run arbitrary application code (lazy loads, disk I/O —
     * e.g. VirtualMap) that must never fire from inside a recording hook. Enums get no
     * snapshot at all: their identity is the constant name already carried by their
     * OBJECT_FIRST_SEEN record.
     */
    private void emitSnapshot(Object o, long id) {
        final Class<?> cls = o.getClass();
        if (o instanceof Enum<?>) {
            return;
        }
        if (isJavaBaseWrapper(o)) {
            emitWrapperSnapshot(o, id, cls);
            return;
        }
        if (cls.isArray()) {
            emitUnfoldedSnapshot(o, id, cls, Wire.SNAPSHOT_FORM_ARRAY);
        } else if (o instanceof java.util.Map && isJavaBase(cls)) {
            emitUnfoldedSnapshot(o, id, cls, Wire.SNAPSHOT_FORM_MAP);
        } else if (o instanceof java.util.Collection && isJavaBase(cls)) {
            emitUnfoldedSnapshot(o, id, cls,
                    o instanceof java.util.Set ? Wire.SNAPSHOT_FORM_SET : Wire.SNAPSHOT_FORM_LIST);
        } else {
            emitFieldSnapshot(o, id, cls);
        }
    }

    private static boolean isJavaBase(Class<?> cls) {
        return cls.getModule() == Object.class.getModule();
    }

    /** True when §3/§7 unfold {@code v} into elements: arrays and {@code java.base} maps/collections. */
    private static boolean isUnfoldedContainer(Object v) {
        final Class<?> c = v.getClass();
        return c.isArray()
                || ((v instanceof java.util.Map || v instanceof java.util.Collection) && isJavaBase(c));
    }

    /**
     * The §7 always-dump {@code java.base} single-value wrappers: the core Atomic cells and the
     * mutable string builders. Their state drifts invisibly (uninstrumented {@code java.base}
     * mutation, exactly like containers), their dump is a single bounded value, and their
     * accessors are {@code final} — safe to call from a hook even for app-defined subclasses.
     */
    private static boolean isJavaBaseWrapper(Object v) {
        return v instanceof java.util.concurrent.atomic.AtomicInteger
                || v instanceof java.util.concurrent.atomic.AtomicLong
                || v instanceof java.util.concurrent.atomic.AtomicBoolean
                || v instanceof java.util.concurrent.atomic.AtomicReference
                || v instanceof StringBuilder
                || v instanceof StringBuffer;
    }

    /**
     * Whether {@code o} is baseline-dumped regardless of its §7 discovery level: the
     * {@code java.base} wrappers unconditionally, plus any class matching a configured
     * {@code snapshotAlways} prefix (small value carriers like PBJ's {@code OneOf} that appear
     * arbitrarily deep). Called only on the cold first-sight path.
     */
    private boolean alwaysDump(Object o) {
        if (isJavaBaseWrapper(o)) {
            return true;
        }
        final String[] prefixes = rt.snapshotAlways;
        if (prefixes.length == 0) {
            return false;
        }
        final String name = o.getClass().getName();
        for (final String p : prefixes) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * §7 trigger 2, container refresh (recorder ≥ v1.3): after a sampling-refresh dump of a
     * FIELDS-form object, re-dump the <em>current</em> contents of up to
     * {@link Wire#SNAPSHOT_MAX_CONTAINER_REDUMPS} of its direct container- or wrapper-valued
     * fields ({@code java.base} maps/sets/lists, arrays, and the {@link #isJavaBaseWrapper
     * always-dump wrappers}) — even when those objects are already registered. Their mutations
     * run inside uninstrumented {@code java.base} code, so without this checkpoint a long-lived
     * collection's contents (or an Atomic cell's value) drift invisibly between the first-sight
     * baseline and the end of the recording. Each re-dump is an ordinary SNAPSHOT record for
     * the field value's id, executed at level 2: objects newly discovered inside it get
     * FIRST_SEEN always and a dump of their own only when {@code 2 < snapshotDepth}. Called
     * only from the refresh path with {@code dumpLevel} 1; objects that themselves unfold or
     * wrap are skipped — their refresh dump already IS their contents.
     */
    private void refreshContainerFields(Object o) {
        if (o instanceof Enum<?> || isUnfoldedContainer(o) || isJavaBaseWrapper(o)) {
            return;
        }
        final Field[] plan = SNAPSHOT_PLANS.get(o.getClass());
        int redumps = 0;
        for (final Field f : plan) {
            if (redumps >= Wire.SNAPSHOT_MAX_CONTAINER_REDUMPS) {
                break;
            }
            if (f.getType().isPrimitive()) {
                continue;
            }
            final Object v;
            try {
                v = f.get(o);
            } catch (Throwable t) {
                continue; // reflection failures silently skip (§7)
            }
            if (v == null || !(isUnfoldedContainer(v) || isJavaBaseWrapper(v))) {
                continue;
            }
            redumps++;
            final int outerLevel = dumpLevel;
            dumpLevel = 2; // the container re-dump runs at level 2 (§7)
            try {
                final long containerId = ObjectIdRegistry.idFor(v, this);
                if (containerId != 0) {
                    emitSnapshot(v, containerId);
                }
            } catch (Throwable t) {
                HindsightRuntime.reportInternalError("container-refresh", t);
            } finally {
                dumpLevel = outerLevel;
            }
        }
    }

    /**
     * §7 always-dump wrapper payload: a FIELDS payload with a single synthetic {@code value}
     * field carrying the wrapper's current content — Atomic cells via their {@code final}
     * {@code get()}, StringBuilder/StringBuffer via {@code toString()} (§2.4 string rules,
     * {@code maxString} truncation). No reflection is involved, so no module opens are needed
     * and the dump works on any launch configuration.
     */
    private void emitWrapperSnapshot(Object o, long id, Class<?> cls) {
        try {
            if (o instanceof java.util.concurrent.atomic.AtomicInteger a) {
                encInline(Wire.TAG_INT, a.get());
            } else if (o instanceof java.util.concurrent.atomic.AtomicLong a) {
                encInline(Wire.TAG_LONG, a.get());
            } else if (o instanceof java.util.concurrent.atomic.AtomicBoolean a) {
                encInline(Wire.TAG_BOOLEAN, a.get() ? 1 : 0);
            } else if (o instanceof java.util.concurrent.atomic.AtomicReference<?> a) {
                encodeRef(a.get()); // nested ref registers per the normal §7 level rules
            } else {
                encodeString(o.toString()); // builders: the current text
            }
        } catch (Throwable t) {
            return; // a racing mutation garbled the read: no payload beats a wrong one
        }
        final ByteBuffer payload = ByteBuffer.allocate(21).order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(rt.symbols.intern(RuntimeSymbols.Kind.RTCLASS, cls.getName()));
        payload.put((byte) Wire.SNAPSHOT_FORM_FIELDS);
        payload.putShort((short) 1);
        payload.putInt(rt.symbols.intern(RuntimeSymbols.Kind.FIELDNAME, "value"));
        payload.put((byte) encTag);
        payload.put((byte) encKind);
        payload.putLong(encSlot);
        final long offset = writeBlobEntry(Wire.BLOB_KIND_SNAPSHOT, payload.array(), payload.position());
        if (offset >= 0) {
            emit(Wire.TYPE_SNAPSHOT, Wire.KIND_BLOB << Wire.AFTER_KIND_SHIFT, 0, 0, 0, id, 0, offset);
        }
    }

    /** Sets the {@code enc*} scratch to an INLINE primitive slot (§2.4 rule 1). */
    private void encInline(int tag, long slot) {
        encKind = Wire.KIND_INLINE;
        encTag = tag;
        encSlot = slot;
        encExtraFlags = 0;
    }

    /** Unfolded §3 payload (forms MAP/SET/LIST/ARRAY): totalSize, then up to 64 elements. */
    private void emitUnfoldedSnapshot(Object o, long id, Class<?> cls, int form) {
        final int cap = Wire.SNAPSHOT_MAX_FIELDS;
        final int entryBytes = form == Wire.SNAPSHOT_FORM_MAP ? 20 : 10;
        final ByteBuffer payload =
                ByteBuffer.allocate(11 + cap * entryBytes).order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(rt.symbols.intern(RuntimeSymbols.Kind.RTCLASS, cls.getName()));
        payload.put((byte) form);
        payload.putInt(0); // totalSize — patched below; size() itself can throw on drifted views
        payload.putShort((short) 0); // count — patched below
        int included = 0;
        int total = 0;
        // Iteration reads another thread's possibly-mutating structure — and a drifted view
        // (e.g. a stale ArrayList$SubList after structural modification of its backing list,
        // exactly what a §7 container refresh re-dumps) throws from size() before yielding a
        // single element. Both placeholders are written BEFORE the try so any throw still
        // leaves a well-formed §3 payload with whatever was collected.
        try {
            if (form == Wire.SNAPSHOT_FORM_ARRAY) {
                total = java.lang.reflect.Array.getLength(o);
                for (int i = 0; i < total && included < cap; i++) {
                    encodeRef(java.lang.reflect.Array.get(o, i));
                    putSnapshotEntry(payload);
                    included++;
                }
            } else if (form == Wire.SNAPSHOT_FORM_MAP) {
                final java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
                total = m.size();
                for (final java.util.Map.Entry<?, ?> e : m.entrySet()) {
                    if (included >= cap) {
                        break;
                    }
                    encodeRef(e.getKey());
                    putSnapshotEntry(payload);
                    encodeRef(e.getValue());
                    putSnapshotEntry(payload);
                    included++;
                }
            } else {
                final java.util.Collection<?> c = (java.util.Collection<?>) o;
                total = c.size();
                for (final Object e : c) {
                    if (included >= cap) {
                        break;
                    }
                    encodeRef(e);
                    putSnapshotEntry(payload);
                    included++;
                }
            }
        } catch (Throwable t) {
            // keep the elements collected before the failure
        }
        payload.putInt(5, Math.max(total, included)); // classSym(4) + form(1)
        payload.putShort(9, (short) included); // ... + totalSize(4)
        final long offset = writeBlobEntry(Wire.BLOB_KIND_SNAPSHOT, payload.array(), payload.position());
        if (offset >= 0) {
            emit(Wire.TYPE_SNAPSHOT, Wire.KIND_BLOB << Wire.AFTER_KIND_SHIFT, 0, 0, 0, id, 0, offset);
        }
    }

    /** One 10-byte element entry from the {@code enc*} scratch (§3). */
    private void putSnapshotEntry(ByteBuffer payload) {
        payload.put((byte) encTag);
        payload.put((byte) encKind);
        payload.putLong(encSlot);
    }

    /**
     * Per-class snapshot plan, resolved once: the accessible instance fields (subclass-first,
     * superclasses included, capped at {@link Wire#SNAPSHOT_MAX_FIELDS}). Reflection —
     * {@code getDeclaredFields} allocating fresh {@code Field} copies, {@code trySetAccessible}
     * re-running the module access check — is far too expensive to repeat per <em>object</em>
     * during the post-trigger first-sight burst; caching per class makes emission a plain
     * {@code Field.get} loop. An empty plan (JPMS-closed or field-less class) means "emit no
     * snapshot at all": an empty {@code {}} record carries zero information.
     */
    private static final ClassValue<Field[]> SNAPSHOT_PLANS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> cls) {
            final Field[] fields = new Field[Wire.SNAPSHOT_MAX_FIELDS];
            int n = 0;
            for (Class<?> c = cls; c != null && c != Object.class && n < fields.length; c = c.getSuperclass()) {
                final Field[] declared;
                try {
                    declared = c.getDeclaredFields();
                } catch (Throwable t) {
                    break; // hidden class / hostile SecurityManager: take what we have
                }
                for (final Field f : declared) {
                    if (n >= fields.length) {
                        break;
                    }
                    boolean accessible;
                    try {
                        accessible = !Modifier.isStatic(f.getModifiers()) && f.trySetAccessible();
                    } catch (Throwable t) {
                        accessible = false;
                    }
                    if (accessible) {
                        fields[n++] = f;
                    }
                }
            }
            return java.util.Arrays.copyOf(fields, n);
        }
    };

    private void emitFieldSnapshot(Object o, long id, Class<?> cls) {
        final Field[] plan = SNAPSHOT_PLANS.get(cls);
        if (plan.length == 0) {
            return; // nothing accessible to dump — an empty snapshot carries no information
        }
        final ByteBuffer payload =
                ByteBuffer.allocate(7 + 14 * plan.length).order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(rt.symbols.intern(RuntimeSymbols.Kind.RTCLASS, cls.getName()));
        payload.put((byte) Wire.SNAPSHOT_FORM_FIELDS);
        payload.putShort((short) 0); // fieldCount patched below once skips are known
        int included = 0;
        for (final Field f : plan) {
            final Object v;
            try {
                v = f.get(o);
            } catch (Throwable t) {
                continue;
            }
            final int tag;
            final int kind;
            final long bits;
            final Class<?> ft = f.getType();
            if (ft.isPrimitive()) {
                tag = primitiveTag(ft);
                kind = Wire.KIND_INLINE;
                bits = v == null ? 0 : boxedBits(v, tag);
            } else {
                encodeRef(v); // nested strings become UTF8 entries written before this snapshot
                tag = encTag;
                kind = encKind;
                bits = encSlot;
            }
            payload.putInt(rt.symbols.intern(RuntimeSymbols.Kind.FIELDNAME, f.getName()));
            payload.put((byte) tag);
            payload.put((byte) kind);
            payload.putLong(bits);
            included++;
        }
        payload.putShort(5, (short) included); // classSym(4) + form(1)
        final long offset = writeBlobEntry(Wire.BLOB_KIND_SNAPSHOT, payload.array(), payload.position());
        if (offset >= 0) {
            emit(Wire.TYPE_SNAPSHOT, Wire.KIND_BLOB << Wire.AFTER_KIND_SHIFT, 0, 0, 0, id, 0, offset);
        }
    }

    // =====================================================================================
    // Low-level buffer plumbing
    // =====================================================================================

    /** Assembles one 40-byte record per the §2 layout. Caller must hold {@code this}. */
    private void emit(int type, int flags, int tag, int idx, int location, long target, long before, long after) {
        emitWithSeq(rt.nextSeq(), type, flags, tag, idx, location, target, before, after);
    }

    /** Record assembly with a pre-reserved {@code seq} (first-seen reservations). */
    private void emitWithSeq(
            long seq, int type, int flags, int tag, int idx, int location, long target, long before, long after) {
        if (recordBuf == null || recordBuf.remaining() < Wire.RECORD_SIZE) {
            flushRecords();
            recordBuf = rt.writer.takeRecordBuffer();
        }
        recordBuf.putLong(seq);
        recordBuf.put((byte) type);
        recordBuf.put((byte) flags);
        recordBuf.put((byte) Math.min(idx, Wire.IDX_CAP)); // aux low byte
        recordBuf.put((byte) tag); // aux high byte (little-endian 16-bit [tag][idx])
        recordBuf.putInt(location);
        recordBuf.putLong(target);
        recordBuf.putLong(before);
        recordBuf.putLong(after);
        if ((++recordCount % Wire.TIME_SYNC_INTERVAL) == 0) {
            timeSyncDue = true;
        }
        // Deferred past ENTER/ARG so a sync never splits §2.1's ENTER→ARG adjacency;
        // recursion depth is 1: the nested emit is itself a TIME_SYNC and cannot re-trigger.
        if (timeSyncDue && type != Wire.TYPE_METHOD_ENTER && type != Wire.TYPE_ARG && type != Wire.TYPE_TIME_SYNC) {
            timeSyncDue = false;
            emitTimeSync();
        }
    }

    /** Set when the periodic TIME_SYNC comes due mid-ENTER/ARG sequence; flushed on the next safe record. */
    private boolean timeSyncDue;

    private void emitTimeSync() {
        emit(Wire.TYPE_TIME_SYNC, Wire.KIND_INLINE << Wire.AFTER_KIND_SHIFT, Wire.TAG_LONG, 0,
                0, 0, 0, System.currentTimeMillis());
    }

    /**
     * Appends one §3 blob entry and returns its absolute file offset, or −1 when it cannot be
     * stored (payload larger than an entire chunk — the caller degrades gracefully).
     */
    private long writeBlobEntry(int kind, byte[] payload, int len) {
        final int need = Wire.BLOB_HEADER_SIZE + len;
        if (blobBuf == null || blobBuf.remaining() < need) {
            flushBlob();
            blobBuf = rt.writer.takeBlobBuffer();
            if (blobBuf.remaining() < need) {
                return -1;
            }
        }
        final long offset = blobFileBase + blobBuf.position();
        blobBuf.put((byte) Wire.BLOB_MAGIC);
        blobBuf.put((byte) kind);
        blobBuf.putInt(len);
        blobBuf.put(payload, 0, len);
        return offset;
    }

    private void flushRecords() {
        if (recordBuf != null && recordBuf.position() > 0) {
            recordBuf.flip();
            if (!rt.writer.submit(tid, false, recordBuf)) {
                suspended = true; // records were dropped; this stream is no longer trustworthy
            }
        }
        recordBuf = null;
    }

    private void flushBlob() {
        if (blobBuf != null && blobBuf.position() > 0) {
            final int size = blobBuf.position();
            blobBuf.flip();
            // Advance the base ONLY once the writer has accepted the chunk: a dropped chunk
            // with an advanced base would skew every later blob offset of this thread.
            if (rt.writer.submit(tid, true, blobBuf)) {
                blobFileBase += size;
            } else {
                suspended = true;
            }
        }
        blobBuf = null;
    }

    // =====================================================================================
    // Primitive/boxed helpers
    // =====================================================================================

    /** §2.3 tag of a boxed-primitive wrapper, or −1 when {@code v} is not one. */
    private static int boxedTag(Object v) {
        final Class<?> c = v.getClass();
        if (c == Integer.class) {
            return Wire.TAG_INT;
        }
        if (c == Long.class) {
            return Wire.TAG_LONG;
        }
        if (c == Boolean.class) {
            return Wire.TAG_BOOLEAN;
        }
        if (c == Double.class) {
            return Wire.TAG_DOUBLE;
        }
        if (c == Float.class) {
            return Wire.TAG_FLOAT;
        }
        if (c == Character.class) {
            return Wire.TAG_CHAR;
        }
        if (c == Byte.class) {
            return Wire.TAG_BYTE;
        }
        if (c == Short.class) {
            return Wire.TAG_SHORT;
        }
        return -1;
    }

    /** §2.4 rule 1 bit layout for a boxed value whose tag is already known. */
    private static long boxedBits(Object v, int tag) {
        return switch (tag) {
            case Wire.TAG_BOOLEAN -> ((Boolean) v) ? 1L : 0L;
            case Wire.TAG_CHAR -> ((Character) v).charValue(); // zero-extends
            case Wire.TAG_FLOAT -> Float.floatToRawIntBits((Float) v) & 0xFFFF_FFFFL;
            case Wire.TAG_DOUBLE -> Double.doubleToRawLongBits((Double) v);
            default -> ((Number) v).longValue(); // INT/LONG/BYTE/SHORT sign-extend
        };
    }

    private static int primitiveTag(Class<?> primitive) {
        if (primitive == int.class) {
            return Wire.TAG_INT;
        }
        if (primitive == long.class) {
            return Wire.TAG_LONG;
        }
        if (primitive == boolean.class) {
            return Wire.TAG_BOOLEAN;
        }
        if (primitive == double.class) {
            return Wire.TAG_DOUBLE;
        }
        if (primitive == float.class) {
            return Wire.TAG_FLOAT;
        }
        if (primitive == char.class) {
            return Wire.TAG_CHAR;
        }
        if (primitive == byte.class) {
            return Wire.TAG_BYTE;
        }
        return Wire.TAG_SHORT;
    }
}
