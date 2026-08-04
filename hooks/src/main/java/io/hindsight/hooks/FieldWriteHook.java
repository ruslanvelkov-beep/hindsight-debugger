// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.hooks;

import io.hindsight.runtime.HindsightRuntime;
import io.hindsight.runtime.ThreadWorker;

/**
 * Field-write hooks invoked by instrumented bytecode immediately before each
 * {@code PUTFIELD} / {@code PUTSTATIC} (FORMAT.md §8 — signatures are frozen). The splice
 * captures the old value with an injected {@code GETFIELD}/{@code GETSTATIC}, so every call
 * carries both the before and the after value of the write.
 *
 * <p>Specialisation matters: passing a primitive through {@code Object before, Object after}
 * would auto-box every write and add allocation + GC pressure to the hottest path in the
 * recorder. Each primitive variant widens its value into the record's 64-bit inline slot with
 * zero allocation. All int-like types (int, boolean, byte, char, short) share one variant and
 * pass the declared-type tag explicitly — char arrives already zero-extended and the sign-y
 * types already sign-extended by the JVM's int calling convention.
 *
 * <p>{@code owner} is {@code null} for static fields — and for a {@code PUTFIELD} about to
 * NPE, which is recorded as static-looking and then the app throws anyway. All hooks follow
 * the §8 contract: never throw, no-op when recording is inactive, re-entrancy guarded.
 *
 * <p>This class lives on the bootstrap classloader at runtime; do not introduce dependencies
 * outside {@code java.base}.
 */
public final class FieldWriteHook {

    private FieldWriteHook() {}

    // §2.3 tag constants mirrored locally so instrumented code paths stay self-contained.
    private static final int TAG_LONG = 2;
    private static final int TAG_FLOAT = 3;
    private static final int TAG_DOUBLE = 4;

    /** Write to an int-like field (INT/BOOLEAN/BYTE/CHAR/SHORT); {@code tag} per §2.3. */
    public static void recordIntLike(int siteId, Object owner, int before, int after, int tag) {
        try {
            final ThreadWorker w = activeWorker();
            if (w == null) {
                return;
            }
            w.inHook = true;
            try {
                w.recordFieldWriteInline(siteId, owner, before, after, tag);
            } finally {
                w.inHook = false;
            }
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError(t);
        }
    }

    /** Write to a {@code long} field. */
    public static void recordLong(int siteId, Object owner, long before, long after) {
        try {
            final ThreadWorker w = activeWorker();
            if (w == null) {
                return;
            }
            w.inHook = true;
            try {
                w.recordFieldWriteInline(siteId, owner, before, after, TAG_LONG);
            } finally {
                w.inHook = false;
            }
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError(t);
        }
    }

    /** Write to a {@code float} field; bits per §2.4 (raw int bits, zero-extended). */
    public static void recordFloat(int siteId, Object owner, float before, float after) {
        try {
            final ThreadWorker w = activeWorker();
            if (w == null) {
                return;
            }
            w.inHook = true;
            try {
                w.recordFieldWriteInline(
                        siteId,
                        owner,
                        Float.floatToRawIntBits(before) & 0xFFFF_FFFFL,
                        Float.floatToRawIntBits(after) & 0xFFFF_FFFFL,
                        TAG_FLOAT);
            } finally {
                w.inHook = false;
            }
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError(t);
        }
    }

    /** Write to a {@code double} field; bits per §2.4 (raw long bits). */
    public static void recordDouble(int siteId, Object owner, double before, double after) {
        try {
            final ThreadWorker w = activeWorker();
            if (w == null) {
                return;
            }
            w.inHook = true;
            try {
                w.recordFieldWriteInline(
                        siteId,
                        owner,
                        Double.doubleToRawLongBits(before),
                        Double.doubleToRawLongBits(after),
                        TAG_DOUBLE);
            } finally {
                w.inHook = false;
            }
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError(t);
        }
    }

    /**
     * Write to a reference-typed field. The record's shared tag is REF (§2.3); the before and
     * after values are each independently encoded per §2.4 (string → blob, boxed → inline,
     * other → object id) with their own KIND bits.
     */
    public static void recordObject(int siteId, Object owner, Object before, Object after) {
        try {
            final ThreadWorker w = activeWorker();
            if (w == null) {
                return;
            }
            w.inHook = true;
            try {
                w.recordFieldWriteObject(siteId, owner, before, after);
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
