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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.ParameterNode;

/**
 * Buffering prescan that harvests what METHOD site rows need but advice resolution cannot
 * see: the class's SourceFile attribute, each method's first body line number, and — recorder
 * ≥ v1.7 — each method's parameter names as the {@code #p<slot>=<name>[|<slot>=<name>...]}
 * descriptor suffix (FORMAT.md §5).
 *
 * <p>ByteBuddy resolves the {@code @SiteId} constant when it creates a method's advice
 * visitor — i.e. at {@code visitMethod} time, <em>before</em> that method's code (where line
 * numbers and the LocalVariableTable live) has streamed past. The only way to have the
 * numbers ready in a single transform pass is to buffer: this wrapper is registered
 * <em>last</em> on the builder, which makes it the outermost visitor (closest to the
 * {@code ClassReader}); it soaks the whole class into a {@link ClassNode}, parks source file
 * + line map + parameter-name map in the {@link SiteRegistry}'s thread-local scan slot, and
 * only then replays the untouched event stream downstream — where the field/local splicer
 * and the advice visitors run with full knowledge available.
 *
 * <p>Parameter-name coverage: this prescan runs for EVERY instrumented scope — FULL and
 * callsOnly alike ({@code HindsightTransformer#compose} registers it whenever the class is
 * instrumented at all) — so both scopes' METHOD sites carry {@code #p} names whenever the
 * class file has a LocalVariableTable (or, failing that, a MethodParameters attribute).
 * Names are absent only when the class file carries neither (compiled without {@code -g}
 * and without {@code -parameters}) or in the degraded no-ASM-bridge mode where this prescan
 * cannot be installed at all (method line numbers are equally lost there).
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
            final Map<String, String> paramSuffixes = new HashMap<>();
            for (final MethodNode method : methods) {
                final int line = firstLineOf(method);
                if (line != 0) {
                    firstLines.put(method.name + method.desc, line);
                }
                final String params = paramNameSuffix(method);
                if (params != null) {
                    paramSuffixes.put(method.name + method.desc, params);
                }
            }
            registry.beginClassScan(name.replace('/', '.'), sourceFile, firstLines, paramSuffixes);
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

    /**
     * Builds the §5 {@code #p<slot>=<name>[|<slot>=<name>...]} suffix for one method, or
     * {@code null} when there is nothing to carry (no parameters, or no name source at all).
     *
     * <p>Slots are the parameters' first-instruction LVT slots computed from the descriptor:
     * instance methods start at slot 1 ({@code this} owns slot 0), statics at 0, and
     * {@code long}/{@code double} occupy two slots. Names come primarily from the
     * LocalVariableTable entries covering the method entry (start at/before the first real
     * instruction, end after it); any parameter the LVT does not name falls back to the
     * MethodParameters attribute ({@link MethodNode#parameters}, positional). Only valid Java
     * identifiers are emitted (§5: no escaping needed), ascending by slot; parameters that no
     * source names are simply absent from the suffix, and a method with no named parameter at
     * all gets no suffix.
     */
    public static String paramNameSuffix(MethodNode method) {
        final Type[] args = Type.getArgumentTypes(method.desc);
        if (args.length == 0) {
            return null;
        }
        final int[] slots = new int[args.length];
        int slot = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (int i = 0; i < args.length; i++) {
            slots[i] = slot;
            slot += args[i].getSize();
        }
        final String[] names = new String[args.length];
        boolean any = false;
        final int entry = firstRealInsnIndex(method);
        if (entry >= 0 && method.localVariables != null && !method.localVariables.isEmpty()) {
            for (int i = 0; i < args.length; i++) {
                final String name = lvtNameCoveringEntry(method, slots[i], entry);
                if (name != null) {
                    names[i] = name;
                    any = true;
                }
            }
        }
        if (method.parameters != null && method.parameters.size() == args.length) {
            for (int i = 0; i < args.length; i++) {
                if (names[i] != null) {
                    continue;
                }
                final ParameterNode p = method.parameters.get(i);
                if (p != null && isJavaIdentifier(p.name)) {
                    names[i] = p.name;
                    any = true;
                }
            }
        }
        if (!any) {
            return null;
        }
        final StringBuilder sb = new StringBuilder("#p");
        boolean first = true;
        for (int i = 0; i < args.length; i++) {
            if (names[i] == null) {
                continue;
            }
            if (!first) {
                sb.append('|');
            }
            first = false;
            sb.append(slots[i]).append('=').append(names[i]);
        }
        return sb.toString();
    }

    /** Index of the first opcode-bearing instruction, or -1 for a codeless method. */
    private static int firstRealInsnIndex(MethodNode method) {
        if (method.instructions == null) {
            return -1;
        }
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) {
                return method.instructions.indexOf(insn);
            }
        }
        return -1;
    }

    /**
     * The LVT name of {@code slot} whose scope covers the method entry (the parameter's
     * defining entry — a later body variable reusing the slot starts after its own store and
     * never covers the entry), or {@code null}. With several covering entries (malformed LVT)
     * the earliest-starting one wins, deterministically.
     */
    private static String lvtNameCoveringEntry(MethodNode method, int slot, int entryIndex) {
        String best = null;
        int bestStart = Integer.MAX_VALUE;
        for (final LocalVariableNode lv : method.localVariables) {
            if (lv.index != slot || lv.start == null || lv.end == null || !isJavaIdentifier(lv.name)) {
                continue;
            }
            final int start = method.instructions.indexOf(lv.start);
            final int end = method.instructions.indexOf(lv.end);
            if (start <= entryIndex && entryIndex < end && start < bestStart) {
                best = lv.name;
                bestStart = start;
            }
        }
        return best;
    }

    /** §5: emitted names must be Java identifiers — anything else is silently skipped. */
    private static boolean isJavaIdentifier(String name) {
        if (name == null || name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
