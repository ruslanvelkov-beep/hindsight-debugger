// SPDX-License-Identifier: Apache-2.0
package io.hindsight.hooks;

import io.hindsight.runtime.HindsightRuntime;
import io.hindsight.runtime.ThreadWorker;

/**
 * Method-bracket hooks invoked by instrumented bytecode on entry to and exit from every
 * instrumented method (FORMAT.md §8 — signatures are frozen).
 *
 * <p>All hooks obey the same contract: never throw into application code (every path is
 * wrapped in {@code catch Throwable → reportInternalError}), no-op when recording is
 * inactive or the runtime is unbooted, and guard against re-entrancy via
 * {@link ThreadWorker#inHook} — anything the recorder itself causes to execute (class
 * initialisers loaded during reflection, for instance) re-enters these methods on the same
 * thread and must be dropped, not recorded and not allowed to recurse.
 *
 * <p>{@link #enter} additionally implements the §8 trigger: an ENTER at the registered
 * trigger site while recording is off opens the gate and records that very invocation as the
 * first business record.
 *
 * <p>This class lives on the bootstrap classloader at runtime (the agent appends the hooks
 * jar to the bootstrap search path), so instrumented classes from any classloader can resolve
 * it. Do not introduce dependencies outside {@code java.base}.
 */
public final class MethodHooks {

    private MethodHooks() {}

    /**
     * Method entry: records METHOD_ENTER followed immediately by one ARG record per argument
     * (§2.1). {@code self} is {@code null} for static methods.
     */
    public static void enter(int siteId, Object self, Object[] args) {
        try {
            if (!HindsightRuntime.isRecording() && !HindsightRuntime.startIfTriggerSite(siteId)) {
                return;
            }
            final ThreadWorker w = HindsightRuntime.worker();
            if (w == null || w.suspended || w.inHook) {
                return;
            }
            w.inHook = true;
            try {
                w.recordMethodEnter(siteId, self, args);
            } finally {
                w.inHook = false;
            }
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError(t);
        }
    }

    /**
     * Constructor entry, called before {@code super()} completes — the uninitialised
     * {@code this} must not escape to the recorder, so there is no self parameter and the
     * ENTER's target is 0 (§2.1).
     */
    public static void enterCtor(int siteId, Object[] args) {
        try {
            if (!HindsightRuntime.isRecording() && !HindsightRuntime.startIfTriggerSite(siteId)) {
                return;
            }
            final ThreadWorker w = HindsightRuntime.worker();
            if (w == null || w.suspended || w.inHook) {
                return;
            }
            w.inHook = true;
            try {
                w.recordMethodEnter(siteId, null, args);
            } finally {
                w.inHook = false;
            }
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError(t);
        }
    }

    /**
     * Normal method exit. {@code returnTag} per §2.3: VOID (12) for void methods, REF (0) for
     * reference returns, or the primitive tag when the instrumentation boxed a primitive
     * return into {@code boxedReturnOrNull}.
     */
    public static void exit(int siteId, Object boxedReturnOrNull, int returnTag) {
        try {
            final ThreadWorker w = activeWorker();
            if (w == null) {
                return;
            }
            w.inHook = true;
            try {
                w.recordMethodExit(siteId, boxedReturnOrNull, returnTag);
            } finally {
                w.inHook = false;
            }
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError(t);
        }
    }

    /** Normal constructor completion; {@code self} is the now fully-initialised instance. */
    public static void exitCtor(int siteId, Object self) {
        try {
            final ThreadWorker w = activeWorker();
            if (w == null) {
                return;
            }
            w.inHook = true;
            try {
                w.recordCtorExit(siteId, self);
            } finally {
                w.inHook = false;
            }
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError(t);
        }
    }

    /** Exceptional method unwind (non-constructor only, §11). */
    public static void exitExceptional(int siteId, Throwable thrown) {
        try {
            final ThreadWorker w = activeWorker();
            if (w == null) {
                return;
            }
            w.inHook = true;
            try {
                w.recordMethodExitExceptional(siteId, thrown);
            } finally {
                w.inHook = false;
            }
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError(t);
        }
    }

    /** Common guard chain of every non-trigger hook: recording, booted, usable worker. */
    private static ThreadWorker activeWorker() {
        if (!HindsightRuntime.isRecording()) {
            return null;
        }
        final ThreadWorker w = HindsightRuntime.worker();
        return (w == null || w.suspended || w.inHook) ? null : w;
    }
}
