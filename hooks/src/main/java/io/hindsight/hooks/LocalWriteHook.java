// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.hooks;

import io.hindsight.runtime.HindsightRuntime;
import io.hindsight.runtime.ThreadWorker;

/**
 * Local-variable-write hooks invoked by instrumented bytecode after each {@code xSTORE} /
 * {@code IINC} whose slot has a live LVT entry (FORMAT.md §8 — signatures are frozen, §11 for
 * the splice). The instrumentation reloads the freshly-stored value and passes it here along
 * with the LVT slot; slots ≥ 255 are capped in the record's idx byte and resolved via the
 * site table.
 *
 * <p>Like every hook: type-specialised to avoid auto-boxing on the hot path, never throws,
 * no-ops when recording is inactive, re-entrancy guarded via {@link ThreadWorker#inHook}.
 *
 * <p>This class lives on the bootstrap classloader at runtime; do not introduce dependencies
 * outside {@code java.base}.
 */
public final class LocalWriteHook {

    private LocalWriteHook() {}

    // §2.3 tag constants mirrored locally so instrumented code paths stay self-contained.
    private static final int TAG_LONG = 2;
    private static final int TAG_FLOAT = 3;
    private static final int TAG_DOUBLE = 4;

    /** Store to an int-like local (INT/BOOLEAN/BYTE/CHAR/SHORT); {@code tag} per §2.3. */
    public static void writeIntLike(int siteId, int slot, int value, int tag) {
        try {
            final ThreadWorker w = activeWorker();
            if (w == null) {
                return;
            }
            w.inHook = true;
            try {
                w.recordLocalWriteInline(siteId, slot, value, tag);
            } finally {
                w.inHook = false;
            }
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError(t);
        }
    }

    /** Store to a {@code long} local. */
    public static void writeLong(int siteId, int slot, long value) {
        try {
            final ThreadWorker w = activeWorker();
            if (w == null) {
                return;
            }
            w.inHook = true;
            try {
                w.recordLocalWriteInline(siteId, slot, value, TAG_LONG);
            } finally {
                w.inHook = false;
            }
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError(t);
        }
    }

    /** Store to a {@code float} local; bits per §2.4 (raw int bits, zero-extended). */
    public static void writeFloat(int siteId, int slot, float value) {
        try {
            final ThreadWorker w = activeWorker();
            if (w == null) {
                return;
            }
            w.inHook = true;
            try {
                w.recordLocalWriteInline(
                        siteId, slot, Float.floatToRawIntBits(value) & 0xFFFF_FFFFL, TAG_FLOAT);
            } finally {
                w.inHook = false;
            }
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError(t);
        }
    }

    /** Store to a {@code double} local; bits per §2.4 (raw long bits). */
    public static void writeDouble(int siteId, int slot, double value) {
        try {
            final ThreadWorker w = activeWorker();
            if (w == null) {
                return;
            }
            w.inHook = true;
            try {
                w.recordLocalWriteInline(siteId, slot, Double.doubleToRawLongBits(value), TAG_DOUBLE);
            } finally {
                w.inHook = false;
            }
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError(t);
        }
    }

    /**
     * Store to a reference-typed local. The value is encoded per §2.4 and the record's tag
     * reflects the runtime encoding decision (STRING for strings, a primitive tag + BOXED for
     * wrappers, NULL, or REF with an object id).
     */
    public static void writeObject(int siteId, int slot, Object value) {
        try {
            final ThreadWorker w = activeWorker();
            if (w == null) {
                return;
            }
            w.inHook = true;
            try {
                w.recordLocalWriteObject(siteId, slot, value);
            } finally {
                w.inHook = false;
            }
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError(t);
        }
    }

    /** Common guard chain: recording active, runtime booted, worker usable (§8). */
    private static ThreadWorker activeWorker() {
        if (!HindsightRuntime.isRecording()) {
            return null;
        }
        final ThreadWorker w = HindsightRuntime.worker();
        return (w == null || w.suspended || w.inHook) ? null : w;
    }
}
