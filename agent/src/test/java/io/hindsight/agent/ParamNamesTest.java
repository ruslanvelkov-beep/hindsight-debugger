// SPDX-License-Identifier: Apache-2.0
package io.hindsight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.hindsight.agent.visitors.MethodLinePrescan;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.ParameterNode;

/**
 * FORMAT.md §5 parameter names (recorder ≥ v1.7): the transform pipeline appends
 * {@code #p<slot>=<name>[|<slot>=<name>...]} to METHOD site descriptors — after the
 * {@code #s} static marker — with names from the LocalVariableTable entries covering the
 * method entry and slots computed from the descriptor (instance parameters from slot 1,
 * statics from 0, category-2 types two slots wide). MethodParameters is the fallback name
 * source; a class file carrying neither yields no suffix at all.
 */
class ParamNamesTest {

    private static final String FIXTURE = "io.hindsight.agent.fixtures.ParamFixture";

    @TempDir
    Path dir;

    private TestBed bed;

    @AfterEach
    void cleanup() {
        if (bed != null) {
            bed.close();
        }
    }

    @Test
    void lvtNamesLandInMethodSiteDescBySlot() throws Exception {
        bed = TestBed.start(dir, AgentConfig.Scope.FULL, FIXTURE);
        final Recorded rec = bed.stopAndParse();

        final Recorded.Site pay = rec.sites.get(rec.methodSiteId(FIXTURE, "pay"));
        assertEquals("(JLjava/lang/String;)I#p1=amount|3=memo", pay.desc(),
                "instance: this owns slot 0, the long takes 1-2, the String sits at 3");

        final Recorded.Site payStatic = rec.sites.get(rec.methodSiteId(FIXTURE, "payStatic"));
        assertEquals("(JLjava/lang/String;)I#s#p0=amount|2=memo", payStatic.desc(),
                "static: parameters start at slot 0, and §5 fixes the order #s before #p");

        final Recorded.Site ctor = rec.sites.get(rec.methodSiteId(FIXTURE, "<init>"));
        assertEquals("()V", ctor.desc(), "zero parameters — the whole #p suffix is omitted");
    }

    @Test
    void callsOnlyScopeCarriesParamNamesToo() throws Exception {
        // The prescan is registered for callsOnly classes as well (HindsightTransformer
        // composition), so bracket-only scope loses no name fidelity when an LVT exists.
        bed = TestBed.start(dir, AgentConfig.Scope.CALLS_ONLY, FIXTURE);
        final Recorded rec = bed.stopAndParse();
        assertEquals(
                "(JLjava/lang/String;)I#p1=amount|3=memo",
                rec.sites.get(rec.methodSiteId(FIXTURE, "pay")).desc());
        assertEquals(
                "(JLjava/lang/String;)I#s#p0=amount|2=memo",
                rec.sites.get(rec.methodSiteId(FIXTURE, "payStatic")).desc());
    }

    @Test
    void classFileWithoutLvtOrMethodParametersYieldsNoSuffix() throws Exception {
        // Strip the debug info (LVT, lines, source) from the compiled fixture; javac emitted
        // no MethodParameters either (no -parameters), so no name source remains.
        final String resource = FIXTURE.replace('.', '/') + ".class";
        final byte[] original;
        try (InputStream in = ParamNamesTest.class.getClassLoader().getResourceAsStream(resource)) {
            original = Objects.requireNonNull(in, "fixture class resource").readAllBytes();
        }
        final ClassWriter writer = new ClassWriter(0);
        new ClassReader(original).accept(writer, ClassReader.SKIP_DEBUG);
        final byte[] stripped = writer.toByteArray();

        final AgentLog log = new AgentLog(dir, false);
        try (SiteRegistry registry = new SiteRegistry(dir, null, null, log)) {
            final HindsightTransformer transformer = new HindsightTransformer(
                    AgentConfig.parse("hooks=unused.jar,include=io.hindsight.agent.fixtures."), registry, log);
            final ClassFileLocator locator = new ClassFileLocator.Compound(
                    ClassFileLocator.Simple.of(FIXTURE, stripped),
                    ClassFileLocator.ForClassLoader.of(ParamNamesTest.class.getClassLoader()));
            final TypeDescription type =
                    TypePool.Default.of(locator).describe(FIXTURE).resolve();
            // Site rows are written while make() runs the production composition; the class
            // is never loaded, so the transform-time view is all that is under test here.
            transformer.compose(new ByteBuddy().decorate(type, locator), AgentConfig.Scope.FULL)
                    .make();
        }

        final Recorded rec = Recorded.parse(dir);
        assertEquals("(JLjava/lang/String;)I", rec.sites.get(rec.methodSiteId(FIXTURE, "pay")).desc(),
                "no LVT and no MethodParameters: the #p suffix is omitted entirely");
        assertEquals(
                "(JLjava/lang/String;)I#s",
                rec.sites.get(rec.methodSiteId(FIXTURE, "payStatic")).desc(),
                "the #s marker is structural and survives without debug info");
    }

    @Test
    void methodParametersAttributeIsTheFallbackNameSource() {
        // No code, no LVT — names must come from the MethodParameters attribute, combined
        // with slots computed from the descriptor.
        final MethodNode instance =
                new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, "pay", "(JLjava/lang/String;)I", null, null);
        instance.parameters = List.of(new ParameterNode("amount", 0), new ParameterNode("memo", 0));
        assertEquals("#p1=amount|3=memo", MethodLinePrescan.paramNameSuffix(instance));

        final MethodNode statics = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "pay",
                "(JLjava/lang/String;)I",
                null,
                null);
        statics.parameters = List.of(new ParameterNode("amount", 0), new ParameterNode("memo", 0));
        assertEquals("#p0=amount|2=memo", MethodLinePrescan.paramNameSuffix(statics));

        // MethodParameters present but name-less (access flags only): no names to carry.
        final MethodNode anonymous =
                new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, "pay", "(JLjava/lang/String;)I", null, null);
        anonymous.parameters = List.of(new ParameterNode(null, 0), new ParameterNode(null, 0));
        assertNull(MethodLinePrescan.paramNameSuffix(anonymous));

        // Zero parameters: never a suffix, whatever the attributes say.
        assertNull(MethodLinePrescan.paramNameSuffix(
                new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, "run", "()V", null, null)));
    }

    @Test
    void lvtNamesWinAndMethodParametersFillsTheGaps() {
        final MethodNode m =
                new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, "pay", "(JLjava/lang/String;)I", null, null);
        final LabelNode start = new LabelNode();
        final LabelNode end = new LabelNode();
        m.instructions.add(start);
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        m.instructions.add(end);
        // LVT names only the long at slot 1; the attribute names both.
        m.localVariables = new ArrayList<>(List.of(new LocalVariableNode("fromLvt", "J", null, start, end, 1)));
        m.parameters = List.of(new ParameterNode("ignored", 0), new ParameterNode("fromAttr", 0));
        assertEquals("#p1=fromLvt|3=fromAttr", MethodLinePrescan.paramNameSuffix(m),
                "LVT is the primary source; MethodParameters only fills unnamed slots");
    }
}
