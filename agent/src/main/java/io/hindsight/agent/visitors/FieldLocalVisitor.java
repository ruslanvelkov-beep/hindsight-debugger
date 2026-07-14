// SPDX-License-Identifier: Apache-2.0
package io.hindsight.agent.visitors;

import io.hindsight.agent.SiteRegistry;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.pool.TypePool;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The FORMAT.md §11 locals-and-fields pass for include-scope (FULL) classes: a
 * {@link MethodNode}-based visitor that splices {@code LocalWriteHook} calls after local
 * stores and {@code FieldWriteHook} calls (with old-value capture) before field writes.
 *
 * <p>Everything about this pass is shaped by one constraint: the classes it instruments
 * belong to a production consensus node whose classfiles (v69) carry precomputed stack-map
 * frames, and recomputing frames at transform time ({@code COMPUTE_FRAMES}) would trigger
 * class loading inside the transformer — the classic agent deadlock. So:
 *
 * <ul>
 *   <li>every splice is straight-line and stack-balanced — no new branches, handlers or merge
 *       points — so every existing frame stays literally valid;</li>
 *   <li>scratch locals live above {@code maxLocals} and die before the next frame node, so
 *       frames need not describe them;</li>
 *   <li>the writer only ever gets {@code COMPUTE_MAXS} (see {@link #mergeWriter});</li>
 *   <li>the pass iterates a snapshot of the original instruction list, so it can never see —
 *       let alone re-instrument — its own splices;</li>
 *   <li>local stores are only instrumented when the stored slot has a LocalVariableTable
 *       entry covering the instruction after the store. That is what gives the site a
 *       variable <em>name</em>, and doubles as the safety net that skips compiler temps and
 *       any store an earlier transformer (or Advice, should composition ever change) may have
 *       introduced;</li>
 *   <li>in constructors, {@code PUTFIELD}s before the first {@code INVOKESPECIAL <init>} on
 *       this class or its superclass are skipped: the splice must {@code ASTORE}/{@code
 *       GETFIELD} the owner, which is illegal on an uninitialized {@code this}
 *       (conservative over-skip is accepted by spec).</li>
 * </ul>
 *
 * <p>Any error raised here propagates out of the transform on purpose: ByteBuddy's listener
 * reports it and the class keeps its original bytecode (fail-open per class, §11) — far
 * better than emitting a half-spliced method.
 */
public final class FieldLocalVisitor extends AsmVisitorWrapper.AbstractBase {

    private static final String LOCAL_HOOK = "io/hindsight/hooks/LocalWriteHook";
    private static final String FIELD_HOOK = "io/hindsight/hooks/FieldWriteHook";

    // §2.3 tags, mirrored locally (same precedent as the hook classes).
    private static final int TAG_INT = 1;
    private static final int TAG_BOOLEAN = 5;
    private static final int TAG_BYTE = 6;
    private static final int TAG_CHAR = 7;
    private static final int TAG_SHORT = 8;

    private final SiteRegistry registry;

    /** @param registry allocator for the one-per-instruction FIELD and LOCAL sites (§5) */
    public FieldLocalVisitor(SiteRegistry registry) {
        this.registry = registry;
    }

    /**
     * {@code COMPUTE_MAXS} only — the splices change stack depth and add locals, but frames
     * are kept valid by construction and must never be recomputed at transform time (see
     * class doc).
     */
    @Override
    public int mergeWriter(int flags) {
        return flags | net.bytebuddy.jar.asm.ClassWriter.COMPUTE_MAXS;
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
        return AsmBridge.factory().unwrap(new SpliceClassVisitor(downstream, registry));
    }

    /** Per-class driver: captures names/source file, buffers each method, splices, replays. */
    private static final class SpliceClassVisitor extends org.objectweb.asm.ClassVisitor {

        private final SiteRegistry registry;
        private String classInternalName;
        private String superInternalName;
        private String sourceFile;

        SpliceClassVisitor(org.objectweb.asm.ClassVisitor downstream, SiteRegistry registry) {
            super(Opcodes.ASM9, downstream);
            this.registry = registry;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.classInternalName = name;
            this.superInternalName = superName;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public void visitSource(String source, String debug) {
            this.sourceFile = source;
            super.visitSource(source, debug);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            final MethodVisitor downstream = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (downstream == null || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                return downstream;
            }
            return new MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions) {
                @Override
                public void visitEnd() {
                    super.visitEnd();
                    new MethodSplicer(registry, classInternalName, superInternalName, sourceFile, this).splice();
                    accept(downstream);
                }
            };
        }
    }

    /** The actual per-method splicing pass; one throwaway instance per method. */
    private static final class MethodSplicer {

        private final SiteRegistry registry;
        private final String classInternalName;
        private final String classBinaryName;
        private final String superInternalName;
        private final String sourceFile;
        private final MethodNode method;

        /** Base slot of the field-splice scratch area (== the method's original maxLocals). */
        private final int scratchBase;

        /** Widest scratch area any single field splice used (0 when no field was spliced). */
        private int scratchUsed;

        MethodSplicer(
                SiteRegistry registry,
                String classInternalName,
                String superInternalName,
                String sourceFile,
                MethodNode method) {
            this.registry = registry;
            this.classInternalName = classInternalName;
            this.classBinaryName = classInternalName.replace('/', '.');
            this.superInternalName = superInternalName;
            this.sourceFile = sourceFile;
            this.method = method;
            this.scratchBase = method.maxLocals;
        }

        void splice() {
            if (method.instructions == null || method.instructions.size() == 0) {
                return;
            }
            // Snapshot + position index of the ORIGINAL code: splices only ever insert, so
            // original nodes keep their relative order and these positions stay meaningful —
            // and iterating the snapshot guarantees we never look at our own insertions.
            final AbstractInsnNode[] original = method.instructions.toArray();
            final Map<AbstractInsnNode, Integer> position = new IdentityHashMap<>(original.length * 2);
            for (int i = 0; i < original.length; i++) {
                position.put(original[i], i);
            }
            final boolean isCtor = "<init>".equals(method.name);
            final int initBoundary = isCtor ? findInitBoundary(original) : -1;

            int line = 0;
            for (int i = 0; i < original.length; i++) {
                final AbstractInsnNode insn = original[i];
                if (insn instanceof LineNumberNode lineNode) {
                    line = lineNode.line;
                } else if (insn instanceof VarInsnNode store && isStoreOpcode(store.getOpcode())) {
                    spliceLocalStore(store, i, line, position);
                } else if (insn instanceof IincInsnNode iinc) {
                    spliceIinc(iinc, i, line, position);
                } else if (insn instanceof FieldInsnNode field
                        && (field.getOpcode() == Opcodes.PUTFIELD || field.getOpcode() == Opcodes.PUTSTATIC)) {
                    if (field.getOpcode() == Opcodes.PUTFIELD && isCtor && i < initBoundary) {
                        continue; // uninitialized-this region (see class doc)
                    }
                    spliceFieldWrite(field, line);
                }
            }
            if (scratchUsed > 0) {
                method.maxLocals = Math.max(method.maxLocals, scratchBase + scratchUsed);
            }
        }

        // ---------------------------------------------------------------------------------
        // Local writes (§11): reload after the store, straight-line, before any label
        // ---------------------------------------------------------------------------------

        private void spliceLocalStore(
                VarInsnNode store, int storePos, int line, Map<AbstractInsnNode, Integer> position) {
            final LocalVariableNode variable = liveVariableAfter(store.var, storePos, position);
            if (variable == null || variable.desc == null || variable.desc.isEmpty()) {
                return; // synthetic temp / no -g: skip silently (§11)
            }
            final InsnList seq = new InsnList();
            final int siteId;
            switch (store.getOpcode()) {
                case Opcodes.ISTORE -> {
                    final int tag = intLikeTag(variable.desc);
                    if (tag < 0) {
                        return; // LVT type contradicts the opcode: leave weird bytecode alone
                    }
                    siteId = localSite(variable, line);
                    seq.add(pushInt(siteId));
                    seq.add(pushInt(store.var));
                    seq.add(new VarInsnNode(Opcodes.ILOAD, store.var));
                    seq.add(pushInt(tag));
                    seq.add(invokeLocalHook("writeIntLike", "(IIII)V"));
                }
                case Opcodes.LSTORE -> {
                    if (!"J".equals(variable.desc)) {
                        return;
                    }
                    siteId = localSite(variable, line);
                    seq.add(pushInt(siteId));
                    seq.add(pushInt(store.var));
                    seq.add(new VarInsnNode(Opcodes.LLOAD, store.var));
                    seq.add(invokeLocalHook("writeLong", "(IIJ)V"));
                }
                case Opcodes.FSTORE -> {
                    if (!"F".equals(variable.desc)) {
                        return;
                    }
                    siteId = localSite(variable, line);
                    seq.add(pushInt(siteId));
                    seq.add(pushInt(store.var));
                    seq.add(new VarInsnNode(Opcodes.FLOAD, store.var));
                    seq.add(invokeLocalHook("writeFloat", "(IIF)V"));
                }
                case Opcodes.DSTORE -> {
                    if (!"D".equals(variable.desc)) {
                        return;
                    }
                    siteId = localSite(variable, line);
                    seq.add(pushInt(siteId));
                    seq.add(pushInt(store.var));
                    seq.add(new VarInsnNode(Opcodes.DLOAD, store.var));
                    seq.add(invokeLocalHook("writeDouble", "(IID)V"));
                }
                case Opcodes.ASTORE -> {
                    final char first = variable.desc.charAt(0);
                    if (first != 'L' && first != '[') {
                        return; // e.g. an astore of a JSR return address
                    }
                    siteId = localSite(variable, line);
                    seq.add(pushInt(siteId));
                    seq.add(pushInt(store.var));
                    seq.add(new VarInsnNode(Opcodes.ALOAD, store.var));
                    seq.add(invokeLocalHook("writeObject", "(IILjava/lang/Object;)V"));
                }
                default -> {
                    return;
                }
            }
            // Insert directly after the store — before any following label — so the reload is
            // trivially verifiable straight-line code regardless of frames or merge points.
            method.instructions.insert(store, seq);
        }

        private void spliceIinc(IincInsnNode iinc, int pos, int line, Map<AbstractInsnNode, Integer> position) {
            final LocalVariableNode variable = liveVariableAfter(iinc.var, pos, position);
            if (variable == null || variable.desc == null) {
                return;
            }
            final int tag = intLikeTag(variable.desc);
            if (tag < 0) {
                return; // IINC only ever touches int-category locals
            }
            final int siteId = localSite(variable, line);
            final InsnList seq = new InsnList();
            seq.add(pushInt(siteId));
            seq.add(pushInt(iinc.var));
            seq.add(new VarInsnNode(Opcodes.ILOAD, iinc.var));
            seq.add(pushInt(tag));
            seq.add(invokeLocalHook("writeIntLike", "(IIII)V"));
            method.instructions.insert(iinc, seq);
        }

        private int localSite(LocalVariableNode variable, int line) {
            return registry.localSite(classBinaryName, variable.name, variable.desc, variable.index, sourceFile, line);
        }

        /**
         * The LVT entry for {@code slot} whose scope covers the position right after the store
         * (i.e. {@code storePos + 1}), or {@code null}. Positions are relative to the original
         * snapshot; an entry whose scope labels are somehow not in the code is ignored.
         */
        private LocalVariableNode liveVariableAfter(int slot, int storePos, Map<AbstractInsnNode, Integer> position) {
            final List<LocalVariableNode> table = method.localVariables;
            if (table == null) {
                return null;
            }
            final int after = storePos + 1;
            for (int i = 0; i < table.size(); i++) {
                final LocalVariableNode v = table.get(i);
                if (v.index != slot) {
                    continue;
                }
                final Integer start = position.get(v.start);
                final Integer end = position.get(v.end);
                if (start != null && end != null && start <= after && after < end) {
                    return v;
                }
            }
            return null;
        }

        // ---------------------------------------------------------------------------------
        // Field writes (§11): the exact uniform two-temp sequences
        // ---------------------------------------------------------------------------------

        private void spliceFieldWrite(FieldInsnNode field, int line) {
            final Type fieldType = Type.getType(field.desc);
            final int valueSize = fieldType.getSize();
            final int storeOp = fieldType.getOpcode(Opcodes.ISTORE);
            final int loadOp = fieldType.getOpcode(Opcodes.ILOAD);
            final boolean isStatic = field.getOpcode() == Opcodes.PUTSTATIC;
            final int tempValue = scratchBase;
            final int tempOwner = scratchBase + valueSize; // PUTFIELD only

            final int intLike = intLikeTag(field.desc);
            final String hookName;
            final String hookDesc;
            switch (field.desc) {
                case "J" -> {
                    hookName = "recordLong";
                    hookDesc = "(ILjava/lang/Object;JJ)V";
                }
                case "F" -> {
                    hookName = "recordFloat";
                    hookDesc = "(ILjava/lang/Object;FF)V";
                }
                case "D" -> {
                    hookName = "recordDouble";
                    hookDesc = "(ILjava/lang/Object;DD)V";
                }
                default -> {
                    if (intLike >= 0) {
                        hookName = "recordIntLike";
                        hookDesc = "(ILjava/lang/Object;III)V";
                    } else {
                        hookName = "recordObject";
                        hookDesc = "(ILjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V";
                    }
                }
            }

            final int siteId = registry.fieldSite(
                    field.owner.replace('/', '.'), field.name, field.desc, isStatic, sourceFile, line);

            final InsnList seq = new InsnList();
            if (isStatic) {
                // §11 PUTSTATIC splice, stack [v]:
                seq.add(new VarInsnNode(storeOp, tempValue));
                seq.add(pushInt(siteId));
                seq.add(new InsnNode(Opcodes.ACONST_NULL));
                seq.add(new FieldInsnNode(Opcodes.GETSTATIC, field.owner, field.name, field.desc));
                seq.add(new VarInsnNode(loadOp, tempValue));
                if (intLike >= 0) {
                    seq.add(pushInt(intLike));
                }
                seq.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FIELD_HOOK, hookName, hookDesc, false));
                seq.add(new VarInsnNode(loadOp, tempValue));
                scratchUsed = Math.max(scratchUsed, valueSize);
            } else {
                // §11 PUTFIELD splice, stack [o, v]:
                seq.add(new VarInsnNode(storeOp, tempValue));
                seq.add(new VarInsnNode(Opcodes.ASTORE, tempOwner));
                seq.add(pushInt(siteId));
                seq.add(new VarInsnNode(Opcodes.ALOAD, tempOwner));
                seq.add(new VarInsnNode(Opcodes.ALOAD, tempOwner));
                seq.add(new FieldInsnNode(Opcodes.GETFIELD, field.owner, field.name, field.desc));
                seq.add(new VarInsnNode(loadOp, tempValue));
                if (intLike >= 0) {
                    seq.add(pushInt(intLike));
                }
                seq.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FIELD_HOOK, hookName, hookDesc, false));
                seq.add(new VarInsnNode(Opcodes.ALOAD, tempOwner));
                seq.add(new VarInsnNode(loadOp, tempValue));
                scratchUsed = Math.max(scratchUsed, valueSize + 1);
            }
            method.instructions.insertBefore(field, seq);
        }

        /**
         * Position of the {@code INVOKESPECIAL <init>} that initializes {@code this} — the
         * point after which PUTFIELDs may be instrumented. Matching by owner alone is NOT
         * enough: a constructor prologue may construct a same-class/superclass instance
         * before {@code super()} (legal javac output under JDK 25's flexible constructor
         * bodies, JEP 513), whose {@code <init>} targets the freshly NEWed object, not
         * {@code this}. So NEWs are paired with their {@code <init>} calls: an
         * {@code INVOKESPECIAL <init>} with no pending NEW is the one initializing
         * {@code this}. When absent (hand-crafted bytecode), every PUTFIELD in the
         * constructor is skipped: over-skipping loses a few records, under-skipping breaks
         * verification.
         */
        private int findInitBoundary(AbstractInsnNode[] original) {
            int pendingNews = 0;
            for (int i = 0; i < original.length; i++) {
                final AbstractInsnNode insn = original[i];
                if (insn.getOpcode() == Opcodes.NEW) {
                    pendingNews++;
                } else if (insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && "<init>".equals(call.name)) {
                    if (pendingNews > 0) {
                        pendingNews--; // initializes a nested NEW, not this
                    } else {
                        return i;
                    }
                }
            }
            return Integer.MAX_VALUE;
        }

        // ---------------------------------------------------------------------------------
        // Helpers
        // ---------------------------------------------------------------------------------

        private static MethodInsnNode invokeLocalHook(String name, String descriptor) {
            return new MethodInsnNode(Opcodes.INVOKESTATIC, LOCAL_HOOK, name, descriptor, false);
        }

        private static boolean isStoreOpcode(int opcode) {
            return opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE;
        }

        /** §2.3 tag for an int-category descriptor, or -1 when the descriptor is not one. */
        private static int intLikeTag(String descriptor) {
            if (descriptor.length() != 1) {
                return -1;
            }
            return switch (descriptor.charAt(0)) {
                case 'I' -> TAG_INT;
                case 'Z' -> TAG_BOOLEAN;
                case 'B' -> TAG_BYTE;
                case 'C' -> TAG_CHAR;
                case 'S' -> TAG_SHORT;
                default -> -1;
            };
        }

        /** Smallest instruction that pushes {@code value} (splices run on hot paths). */
        private static AbstractInsnNode pushInt(int value) {
            if (value >= -1 && value <= 5) {
                return new InsnNode(Opcodes.ICONST_0 + value);
            }
            if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                return new IntInsnNode(Opcodes.BIPUSH, value);
            }
            if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                return new IntInsnNode(Opcodes.SIPUSH, value);
            }
            return new LdcInsnNode(value);
        }
    }
}
