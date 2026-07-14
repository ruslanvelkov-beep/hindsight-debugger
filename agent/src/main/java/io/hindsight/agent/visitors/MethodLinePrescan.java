// SPDX-License-Identifier: Apache-2.0
package io.hindsight.agent.visitors;

import io.hindsight.agent.SiteRegistry;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.pool.TypePool;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Buffering prescan that harvests what METHOD site rows need but advice resolution cannot
 * see: the class's SourceFile attribute and each method's first body line number (FORMAT.md
 * §5).
 *
 * <p>ByteBuddy resolves the {@code @SiteId} constant when it creates a method's advice
 * visitor — i.e. at {@code visitMethod} time, <em>before</em> that method's code (where line
 * numbers live) has streamed past. The only way to have the numbers ready in a single
 * transform pass is to buffer: this wrapper is registered <em>last</em> on the builder, which
 * makes it the outermost visitor (closest to the {@code ClassReader}); it soaks the whole
 * class into a {@link ClassNode}, parks source file + line map in the {@link SiteRegistry}'s
 * thread-local scan slot, and only then replays the untouched event stream downstream —
 * where the field/local splicer and the advice visitors run with full knowledge available.
 *
 * <p>The replay is byte-identical pass-through (frames, attributes and all); this wrapper
 * never modifies anything, so it needs no writer flags and cannot invalidate stack maps.
 */
public final class MethodLinePrescan extends AsmVisitorWrapper.AbstractBase {

    private final SiteRegistry registry;

    /** @param registry sink for the per-class scan info (consumed by {@code methodSite}) */
    public MethodLinePrescan(SiteRegistry registry) {
        this.registry = registry;
    }

    @Override
    public net.bytebuddy.jar.asm.ClassVisitor wrap(
            TypeDescription instrumentedType,
            net.bytebuddy.jar.asm.ClassVisitor classVisitor,
            Implementation.Context implementationContext,
            TypePool typePool,
            FieldList<FieldDescription.InDefinedShape> fieldList,
            MethodList<?> methodList,
            int writerFlags,
            int readerFlags) {
        final org.objectweb.asm.ClassVisitor downstream = AsmBridge.factory().wrap(classVisitor);
        return AsmBridge.factory().unwrap(new ScanningBuffer(downstream, registry));
    }

    /** ClassNode that scans itself on visitEnd, parks the results, then replays downstream. */
    private static final class ScanningBuffer extends ClassNode {

        private final org.objectweb.asm.ClassVisitor downstream;
        private final SiteRegistry registry;

        ScanningBuffer(org.objectweb.asm.ClassVisitor downstream, SiteRegistry registry) {
            super(Opcodes.ASM9);
            this.downstream = downstream;
            this.registry = registry;
        }

        @Override
        public void visitEnd() {
            super.visitEnd();
            final Map<String, Integer> firstLines = new HashMap<>();
            for (final MethodNode method : methods) {
                final int line = firstLineOf(method);
                if (line != 0) {
                    firstLines.put(method.name + method.desc, line);
                }
            }
            registry.beginClassScan(name.replace('/', '.'), sourceFile, firstLines);
            try {
                accept(downstream);
            } finally {
                registry.endClassScan();
            }
        }

        /** First LineNumberTable entry in the body; 0 when the method has none (§5). */
        private static int firstLineOf(MethodNode method) {
            if (method.instructions == null) {
                return 0;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LineNumberNode line) {
                    return line.line;
                }
            }
            return 0;
        }
    }
}
