// SPDX-License-Identifier: Apache-2.0
package io.hindsight.hooks;

import io.hindsight.runtime.HindsightRuntime;
import io.hindsight.runtime.ThreadWorker;

/**
 * Execution-flow hooks invoked by instrumented bytecode to turn a recording from a set of
 * value-change events into a readable control flow (FORMAT.md §7, §11):
 *
 * <ul>
 *   <li>{@link #line} — emitted after every source-line marker, so the trace shows which
 *       lines actually ran and in what order (including loop-body repeats);</li>
 *   <li>the {@code branch*} hooks — emitted at each conditional jump, capturing the operand
 *       values (int compares) or the computed identity/null result (ref compares) so the
 *       {@code if}/{@code for}/{@code while} condition value is visible and the taken path
 *       is unambiguous.</li>
 * </ul>
 *
 * <p>Argument order puts the captured operand(s) first and {@code siteId} last: the splice
 * DUPs the live operand already on the stack, then pushes {@code siteId}, so the hook consumes
 * {@code (…operands, siteId)} top-down and leaves the original operand for the real jump. See
 * {@code FieldLocalVisitor} for the exact stack-neutral sequences.
 *
 * <p>Like every hook: never throws, no-ops when recording is inactive, re-entrancy guarded via
 * {@link ThreadWorker#inHook}. Lives on the bootstrap classloader — {@code java.base} only.
 */
public final class FlowHooks {

    private FlowHooks() {}

    /** A source line was entered (§7). */
    public static void line(int siteId) {
        try {
            final ThreadWorker w = activeWorker();
            if (w == null) {
                return;
            }
            w.inHook = true;
            try {
                w.recordLine(siteId);
            } finally {
                w.inHook = false;
            }
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError(t);
        }
    }

    /** Conditional jump on one int operand (comparison against 0). */
    public static void branchInt1(int value, int siteId) {
        try {
            final ThreadWorker w = activeWorker();
            if (w == null) {
                return;
            }
            w.inHook = true;
            try {
                w.recordBranchInt1(siteId, value);
            } finally {
                w.inHook = false;
            }
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError(t);
        }
    }

    /** Conditional jump comparing two int operands. */
    public static void branchInt2(int a, int b, int siteId) {
        try {
            final ThreadWorker w = activeWorker();
            if (w == null) {
                return;
            }
            w.inHook = true;
            try {
                w.recordBranchInt2(siteId, a, b);
            } finally {
                w.inHook = false;
            }
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError(t);
        }
    }

    /**
     * Conditional jump on one reference operand ({@code IFNULL}/{@code IFNONNULL}). The
     * null-ness is computed here rather than resolving an object id — the branch only cares
     * whether the reference is null, and resolving transient operands would be pure overhead.
     */
    public static void branchRefNull(Object ref, int siteId) {
        try {
            final ThreadWorker w = activeWorker();
            if (w == null) {
                return;
            }
            w.inHook = true;
            try {
                w.recordBranchRefNull(siteId, ref == null);
            } finally {
                w.inHook = false;
            }
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError(t);
        }
    }

    /**
     * Conditional jump comparing two reference operands by identity
     * ({@code IF_ACMPEQ}/{@code IF_ACMPNE}). Records only the identity-equality result.
     */
    public static void branchRefCmp(Object a, Object b, int siteId) {
        try {
            final ThreadWorker w = activeWorker();
            if (w == null) {
                return;
            }
            w.inHook = true;
            try {
                w.recordBranchRefCmp(siteId, a == b);
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
