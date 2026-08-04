// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.agent.visitors;

import net.bytebuddy.asm.ClassVisitorFactory;
import org.objectweb.asm.ClassVisitor;

/**
 * Bridge between ByteBuddy's shaded ASM ({@code net.bytebuddy.jar.asm}) and the standalone
 * ASM this agent's bytecode passes are written against ({@code org.objectweb.asm}).
 *
 * <p>ByteBuddy's {@code AsmVisitorWrapper} chain speaks the shaded ASM only, and the shaded
 * copy deliberately omits the tree API — but FORMAT.md §11 mandates a {@code MethodNode}-based
 * pass for locals and fields (the LVT and line numbers must be fully known before splicing).
 * ByteBuddy ships {@link ClassVisitorFactory} for exactly this situation: it generates
 * translator visitors at runtime that re-emit one namespace's events into the other, mapping
 * labels, handles and constants along the way.
 *
 * <p>The factory is built lazily and cached: generation costs a few dozen classes once, and
 * deferring it lets the agent degrade gracefully (advice brackets still work) if translator
 * generation ever fails on an exotic JVM — the caller decides that policy, this class just
 * propagates the failure.
 */
public final class AsmBridge {

    private static volatile ClassVisitorFactory<ClassVisitor> factory;

    private AsmBridge() {}

    /**
     * The cached translator factory. First call generates the translators; any failure
     * propagates so the caller can disable the tree-based passes rather than fail classes
     * one by one at transform time.
     */
    public static ClassVisitorFactory<ClassVisitor> factory() {
        ClassVisitorFactory<ClassVisitor> f = factory;
        if (f == null) {
            synchronized (AsmBridge.class) {
                f = factory;
                if (f == null) {
                    f = ClassVisitorFactory.of(ClassVisitor.class);
                    factory = f;
                }
            }
        }
        return f;
    }
}
