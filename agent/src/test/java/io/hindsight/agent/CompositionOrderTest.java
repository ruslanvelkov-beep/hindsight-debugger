// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Empirically locks in the FORMAT.md §11 composition rule: the MethodNode pass must see
 * ORIGINAL bytecode, i.e. Advice must apply downstream of (after) the field/local splicer.
 *
 * <p>ByteBuddy chains visitor wrappers so the last registered sits closest to the reader.
 * Probes are inserted at two points of the production order:
 * <ul>
 *   <li>directly downstream of the splicer (between it and the advice wrappers): this stream
 *       is the splicer's OUTPUT before advice touches it — it must already contain hook calls
 *       from the splicer but no {@code MethodHooks} bracket calls yet;</li>
 *   <li>outermost (upstream of everything): this stream is the raw class — it must contain no
 *       hindsight calls at all.</li>
 * </ul>
 * Should ByteBuddy ever flip its composition order, the first probe would observe
 * {@code MethodHooks} calls (advice output feeding the splicer) and this test fails loudly.
 */
class CompositionOrderTest {

    private static final String FIXTURE = "io.hindsight.agent.fixtures.Fixture";
    private static final String METHOD_HOOKS = "io/hindsight/hooks/MethodHooks";
    private static final String LOCAL_HOOK = "io/hindsight/hooks/LocalWriteHook";
    private static final String FIELD_HOOK = "io/hindsight/hooks/FieldWriteHook";

    @TempDir
    Path dir;

    @Test
    void methodNodePassSeesOriginalBytecode() throws Exception {
        final AgentLog log = new AgentLog(dir, false);
        try (SiteRegistry registry = new SiteRegistry(dir, null, null, log)) {
            final AgentConfig config = AgentConfig.parse("hooks=unused.jar,include=io.hindsight.agent.fixtures.");
            final HindsightTransformer transformer = new HindsightTransformer(config, registry, log);

            final InvocationProbe betweenSplicerAndAdvice = new InvocationProbe();
            final InvocationProbe outermost = new InvocationProbe();

            final ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(getClass().getClassLoader());
            final TypeDescription type = TypePool.Default.of(locator).describe(FIXTURE).resolve();
            DynamicType.Builder<?> builder = new ByteBuddy().decorate(type, locator);
            // Production registration order (see HindsightTransformer.compose) with probes:
            builder = builder.visit(transformer.methodAdvice())
                    .visit(transformer.ctorAdvice())
                    .visit(betweenSplicerAndAdvice)
                    .visit(transformer.fieldLocal())
                    .visit(transformer.prescan())
                    .visit(outermost);
            builder.make();

            assertTrue(outermost.owners.isEmpty()
                            || (!outermost.owners.contains(METHOD_HOOKS)
                                    && !outermost.owners.contains(LOCAL_HOOK)
                                    && !outermost.owners.contains(FIELD_HOOK)),
                    "the raw class must contain no hindsight calls: " + outermost.owners);
            assertTrue(betweenSplicerAndAdvice.owners.contains(LOCAL_HOOK),
                    "the splicer's output must contain local-write hook calls");
            assertTrue(betweenSplicerAndAdvice.owners.contains(FIELD_HOOK),
                    "the splicer's output must contain field-write hook calls (Fixture.consume writes sink)");
            assertFalse(betweenSplicerAndAdvice.owners.contains(METHOD_HOOKS),
                    "advice must apply AFTER the MethodNode pass — the pass saw original bytecode");
        }
    }

    /** Records the owner of every INVOKESTATIC/INVOKEVIRTUAL/... flowing past its position. */
    private static final class InvocationProbe extends AsmVisitorWrapper.AbstractBase {

        final Set<String> owners = ConcurrentHashMap.newKeySet();

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
            return new net.bytebuddy.jar.asm.ClassVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, classVisitor) {
                @Override
                public net.bytebuddy.jar.asm.MethodVisitor visitMethod(
                        int access, String name, String descriptor, String signature, String[] exceptions) {
                    final net.bytebuddy.jar.asm.MethodVisitor downstream =
                            super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (downstream == null) {
                        return null;
                    }
                    return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, downstream) {
                        @Override
                        public void visitMethodInsn(
                                int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            owners.add(owner);
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        }
                    };
                }
            };
        }
    }
}
