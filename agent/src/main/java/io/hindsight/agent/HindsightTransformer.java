// SPDX-License-Identifier: Apache-2.0
package io.hindsight.agent;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.not;

import io.hindsight.agent.visitors.AsmBridge;
import io.hindsight.agent.visitors.FieldLocalVisitor;
import io.hindsight.agent.visitors.MethodLinePrescan;
import java.security.ProtectionDomain;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

/**
 * Builds and composes the per-class instrumentation stack (FORMAT.md §11): method/constructor
 * brackets via inlined {@link Advice}, plus — for FULL-scope classes — the
 * {@link FieldLocalVisitor} tree pass, all fed by the {@link MethodLinePrescan}.
 *
 * <p>Composition order is load-bearing. ByteBuddy chains visitor wrappers so that the
 * <em>last</em> one registered sits closest to the {@code ClassReader} — it sees the class
 * first, and its output flows toward earlier registrations. The order used here is therefore:
 *
 * <pre>
 *   reader → MethodLinePrescan → FieldLocalVisitor → ctor advice → method advice → writer
 * </pre>
 *
 * so the prescan reads the pristine class (and parks line info before any advice resolves a
 * site id), the MethodNode pass splices into ORIGINAL bytecode — never into advice-generated
 * stores — and the advice brackets finally wrap the spliced method whole. A test locks this
 * order in empirically; the LVT-only rule in the splicer is the safety net should it ever
 * drift.
 *
 * <p>One transformer instance serves every class: the wrappers are stateless across classes
 * (all per-class state lives in visitor instances created per {@code wrap} call), which
 * matters because transformation runs concurrently on class-loading threads.
 */
public final class HindsightTransformer implements AgentBuilder.Transformer {

    private final AgentConfig config;
    private final AsmVisitorWrapper methodAdvice;
    private final AsmVisitorWrapper ctorAdvice;
    private final AsmVisitorWrapper fieldLocal; // null when the ASM bridge is unavailable
    private final AsmVisitorWrapper prescan; // null when the ASM bridge is unavailable

    /**
     * Prepares the advice and visitor wrappers once. If ByteBuddy's shaded-ASM translator
     * cannot be generated on this JVM, the tree-based passes are disabled and logged — method
     * brackets (the recorder's backbone) still work, with line numbers degraded to 0.
     */
    public HindsightTransformer(AgentConfig config, SiteRegistry registry, AgentLog log) {
        this.config = config;
        final Advice.WithCustomMapping mapping = Advice.withCustomMapping()
                .bind(new SiteMappings.SiteIdFactory(registry))
                .bind(new SiteMappings.ReturnTagFactory());
        this.methodAdvice = mapping.to(MethodBracketAdvice.class)
                .on(isMethod().and(not(isAbstract())).and(not(isNative())).and(not(isBridge())));
        this.ctorAdvice = mapping.to(CtorBracketAdvice.class).on(isConstructor());

        AsmVisitorWrapper fieldLocalWrapper = null;
        AsmVisitorWrapper prescanWrapper = null;
        try {
            AsmBridge.factory(); // fail here, once, not per class
            fieldLocalWrapper = new FieldLocalVisitor(registry, config.lines, config.branches);
            prescanWrapper = new MethodLinePrescan(registry);
        } catch (Throwable t) {
            if (log != null) {
                log.error("ASM bridge unavailable; locals/fields and method line numbers are disabled", t);
            }
        }
        this.fieldLocal = fieldLocalWrapper;
        this.prescan = prescanWrapper;
    }

    @Override
    public DynamicType.Builder<?> transform(
            DynamicType.Builder<?> builder,
            TypeDescription typeDescription,
            ClassLoader classLoader,
            JavaModule module,
            ProtectionDomain protectionDomain) {
        return compose(builder, config.scopeFor(typeDescription.getName()));
    }

    /**
     * Applies the instrumentation stack for the given scope; see the class doc for why the
     * registration order is what it is. Exposed (rather than folded into
     * {@link #transform}) so tests can run the exact production composition over fixture
     * bytes without an installed agent.
     */
    public DynamicType.Builder<?> compose(DynamicType.Builder<?> builder, AgentConfig.Scope scope) {
        if (scope == AgentConfig.Scope.NONE) {
            return builder;
        }
        builder = builder.visit(methodAdvice).visit(ctorAdvice);
        if (scope == AgentConfig.Scope.FULL && fieldLocal != null) {
            builder = builder.visit(fieldLocal);
        }
        if (prescan != null) {
            builder = builder.visit(prescan);
        }
        return builder;
    }

    // Package-private accessors so tests can recompose the stack with probes in between.

    AsmVisitorWrapper methodAdvice() {
        return methodAdvice;
    }

    AsmVisitorWrapper ctorAdvice() {
        return ctorAdvice;
    }

    AsmVisitorWrapper fieldLocal() {
        return fieldLocal;
    }

    AsmVisitorWrapper prescan() {
        return prescan;
    }
}
