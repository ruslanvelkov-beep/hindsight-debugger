// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.agent;

import io.hindsight.hooks.MethodHooks;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Enter/exit brackets for ordinary (non-constructor) methods, FORMAT.md §11.
 *
 * <p>Never executed as a class: ByteBuddy <em>inlines</em> the bytecode of these methods into
 * every instrumented method, so the {@code MethodHooks} references resolve from the
 * instrumented class's own context — where the bootstrap-appended hooks jar is visible — and
 * not from the agent's classloader (whose fat jar deliberately excludes the hooks).
 *
 * <p>The exit advice declares {@code onThrowable = Throwable.class}, which makes ByteBuddy
 * wrap the whole method body in a synthetic handler so an unwinding frame still produces its
 * METHOD_EXIT (with the throwable in hand). {@code backupArguments = false} skips the copy of
 * all arguments into fresh locals that ByteBuddy would otherwise make for the handler's
 * benefit — the exit advice never reads arguments, and on a hot production node that copy is
 * pure overhead. {@code suppress = Throwable.class} is defense in depth: the hooks guarantee
 * never to throw, but the advice-side glue (argument boxing) could conceivably fail and must
 * never take the application down with it.
 */
final class MethodBracketAdvice {

    private MethodBracketAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter(
            @SiteId int siteId,
            @Advice.This(optional = true) Object self,
            @Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) Object[] args) {
        MethodHooks.enter(siteId, self, args);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, backupArguments = false, suppress = Throwable.class)
    static void exit(
            @SiteId int siteId,
            @ReturnTag int returnTag,
            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object boxedReturn,
            @Advice.Thrown Throwable thrown) {
        if (thrown != null) {
            MethodHooks.exitExceptional(siteId, thrown);
        } else {
            MethodHooks.exit(siteId, boxedReturn, returnTag);
        }
    }
}
