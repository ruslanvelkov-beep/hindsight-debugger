// SPDX-License-Identifier: Apache-2.0
package io.hindsight.agent;

import io.hindsight.hooks.MethodHooks;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Enter/exit brackets for constructors, FORMAT.md §11 — a separate advice pair because the
 * JVM's initialization rules make constructors special twice over:
 *
 * <ul>
 *   <li>The enter advice runs before {@code super(...)} completes, while {@code this} is
 *       still uninitialized and must not escape to the recorder — so there is no
 *       {@code @Advice.This} on enter and the hook is {@code enterCtor} (ENTER target 0,
 *       §2.1).</li>
 *   <li>There is no {@code onThrowable}: wrapping a constructor in a handler would need a
 *       protected region spanning the uninitialized-this phase, which JVMS §4.10.2.4 forbids.
 *       A throwing constructor therefore has no METHOD_EXIT; the reader already tolerates
 *       unbalanced frames (truncated-tail rule, §2.1).</li>
 * </ul>
 *
 * <p>Like {@link MethodBracketAdvice}, this class is inlined, never executed, and suppresses
 * advice-glue failures so instrumentation can never take down object construction.
 */
final class CtorBracketAdvice {

    private CtorBracketAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter(
            @SiteId int siteId,
            @Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) Object[] args) {
        MethodHooks.enterCtor(siteId, args);
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void exit(@SiteId int siteId, @Advice.This Object self) {
        MethodHooks.exitCtor(siteId, self);
    }
}
