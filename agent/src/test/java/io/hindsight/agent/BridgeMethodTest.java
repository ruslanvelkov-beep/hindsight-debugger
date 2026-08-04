// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Regression for the synthetic-bridge phantom-line bug (§7/§11). A javac bridge method (the
 * erasure wrapper emitted for covariant returns / generic-interface impls,
 * {@code ACC_BRIDGE | ACC_SYNTHETIC}) carries a LineNumberTable that points at the
 * class-declaration line. {@link io.hindsight.agent.visitors.FieldLocalVisitor} used to run its
 * line/local/branch pass over such bridges, minting a phantom LINE record on a line with no code
 * — surfaced inside whatever caller invoked the bridge polymorphically. The pass must now skip
 * bridges, mirroring the bracket matcher's {@code not(isBridge())}.
 *
 * <p>{@link io.hindsight.agent.fixtures.BridgeFixture} implements {@code Comparable<BridgeFixture>}
 * so javac compiles a {@code compareTo(Object)} bridge forwarding to {@code compareTo(BridgeFixture)}.
 * Its class declaration is on source line 20 (where the bridge's LineNumberTable points); the real
 * method's body is on lines 30-32.
 */
final class BridgeMethodTest {

    private static final String BRIDGE_FIXTURE = "io.hindsight.agent.fixtures.BridgeFixture";
    private static final String INTERNAL = "io/hindsight/agent/fixtures/BridgeFixture";

    private static final String FLOW_HOOK = "io/hindsight/hooks/FlowHooks";
    private static final String LOCAL_HOOK = "io/hindsight/hooks/LocalWriteHook";
    private static final String FIELD_HOOK = "io/hindsight/hooks/FieldWriteHook";

    /** Source line of {@code public final class BridgeFixture ...} — the bridge's phantom line. */
    private static final int CLASS_DECL_LINE = 20;

    /** First statement line of the real {@code compareTo(BridgeFixture)}. */
    private static final int REAL_METHOD_FIRST_LINE = 30;

    @TempDir
    Path tmp;

    /**
     * Line-number-independent proof: inspect the transformed bytecode directly. The bridge must
     * carry none of the pass's spliced hook calls, while the real overload keeps its LINE hooks.
     */
    @Test
    void bridgeMethodIsNotSplicedWithFlowHooks() throws Exception {
        final Map<String, Set<String>> callsByMethod = new HashMap<>();
        final Set<String> bridgeMethods = new HashSet<>();
        try (TestBed bed = TestBed.start(tmp, AgentConfig.Scope.FULL, BRIDGE_FIXTURE)) {
            final byte[] bytes = bed.transformedBytes(BRIDGE_FIXTURE);
            assertNotNull(bytes, "fixture must have been transformed");
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(
                        int access, String name, String descriptor, String signature, String[] exceptions) {
                    final String key = name + descriptor;
                    if ((access & Opcodes.ACC_BRIDGE) != 0) {
                        bridgeMethods.add(key);
                    }
                    final Set<String> calls = callsByMethod.computeIfAbsent(key, k -> new HashSet<>());
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(
                                int opcode, String owner, String n, String d, boolean isInterface) {
                            calls.add(owner + "." + n);
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }

        assertTrue(bridgeMethods.contains("compareTo(Ljava/lang/Object;)I"),
                "fixture must compile a compareTo(Object) bridge; bridges found: " + bridgeMethods);
        // The bridge must be left completely untouched by the line/local/branch pass.
        for (final String bridge : bridgeMethods) {
            final Set<String> calls = callsByMethod.get(bridge);
            assertFalse(calls.contains(FLOW_HOOK + ".line"),
                    "bridge " + bridge + " must not be spliced with a FlowHooks.line call: " + calls);
            assertTrue(calls.stream().noneMatch(c -> c.startsWith(LOCAL_HOOK) || c.startsWith(FIELD_HOOK)),
                    "bridge " + bridge + " must carry no local/field hooks either: " + calls);
        }
        // The real (typed) overload still gets its LINE hooks — the pass ran, it just skipped the bridge.
        final Set<String> realCalls = callsByMethod.get("compareTo(L" + INTERNAL + ";)I");
        assertNotNull(realCalls, "real compareTo overload must be present in the class");
        assertTrue(realCalls.contains(FLOW_HOOK + ".line"),
                "the real compareTo must still be spliced with FlowHooks.line calls: " + realCalls);
    }

    /**
     * Spec-level proof over the on-disk site table: exercising the bridge polymorphically, no LINE
     * site lands on the class-declaration line, while the real method's body lines do. Also
     * corroborates that the bridge gets no METHOD site (advice already excludes bridges).
     */
    @Test
    void bridgeContributesNoLineSiteWhileRealMethodDoes() throws Exception {
        final Recorded rec;
        try (TestBed bed = TestBed.start(tmp, AgentConfig.Scope.FULL, BRIDGE_FIXTURE)) {
            // Invoke through the bridge exactly as a polymorphic caller would: compareTo(Object)
            // is the synthetic bridge, which erasure-casts and forwards to compareTo(BridgeFixture).
            // Loading + linking also turns any frame/stack damage into a VerifyError here.
            final Class<?> type = bed.load(BRIDGE_FIXTURE);
            final Object one = type.getDeclaredConstructor(int.class).newInstance(1);
            final Object two = type.getDeclaredConstructor(int.class).newInstance(2);
            final int cmp = (Integer) type.getMethod("compareTo", Object.class).invoke(one, two);
            assertTrue(cmp < 0, "1.compareTo(2) should be negative");
            rec = bed.stopAndParse();
        }

        // LINE sites are assigned at transform time; gather every one for the fixture class.
        final List<Recorded.Site> lineSites = new ArrayList<>();
        for (final Recorded.Site s : rec.sites.values()) {
            if ("LINE".equals(s.kind()) && BRIDGE_FIXTURE.equals(rec.symbolText(s.classSym()))) {
                lineSites.add(s);
            }
        }
        assertFalse(lineSites.isEmpty(), "the real method's body lines must still produce LINE sites");

        final Set<Integer> lines = new HashSet<>();
        for (final Recorded.Site s : lineSites) {
            lines.add(s.line());
        }
        // The crux: the bridge's LineNumberTable points at the class-declaration line, so pre-fix
        // the pass minted a phantom LINE site there. Post-fix there is none.
        assertFalse(lines.contains(CLASS_DECL_LINE),
                "no LINE site may sit on the class-declaration line " + CLASS_DECL_LINE
                        + " (the bridge's phantom line); lines seen: " + lines);
        assertTrue(lines.contains(REAL_METHOD_FIRST_LINE),
                "the real compareTo's body must have LINE sites; lines seen: " + lines);

        // Corroboration: the bridge gets no METHOD site either; the only compareTo METHOD site is
        // the typed overload, never the (Object) bridge (the bracket matcher excludes bridges).
        final List<Integer> compareToSites = rec.methodSiteIds(BRIDGE_FIXTURE, "compareTo");
        assertEquals(1, compareToSites.size(),
                "only the real compareTo overload may get a METHOD site; got " + compareToSites);
        assertTrue(rec.sites.get(compareToSites.get(0)).desc().startsWith("(L" + INTERNAL + ";)I"),
                "the sole compareTo METHOD site must be the typed overload, not the (Object) bridge");
    }
}
