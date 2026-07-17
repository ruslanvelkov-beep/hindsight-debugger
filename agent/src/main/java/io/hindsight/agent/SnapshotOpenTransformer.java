// SPDX-License-Identifier: Apache-2.0
package io.hindsight.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opens the packages of §10 {@code snapshotOpen}-matched classes to the hooks module WITHOUT
 * instrumenting them. The §7 snapshot dumps read fields reflectively from the
 * bootstrap-unnamed hooks module; data/model classes (PBJ records and the like) are
 * deliberately kept out of the instrumentation scope — bracketing them would drown the
 * recording in noise — yet their contents are exactly the account IDs and state entities a
 * recording is inspected for. Without an open, {@code trySetAccessible} fails for every
 * class in a non-open named module and their snapshots silently degrade to nothing.
 *
 * <p>This transformer is a pure side-effect hook on class load: it NEVER modifies bytecode
 * (always returns {@code null}), it just applies the runtime equivalent of
 * {@code --add-opens pkg=<hooks>} the first time a class from a matching package loads —
 * the same open {@code FailOpenListener#openForSnapshots} performs for instrumented classes.
 * Registered only when the {@code snapshotOpen} list is non-empty; the per-load cost is a
 * few string prefix tests.
 */
final class SnapshotOpenTransformer implements ClassFileTransformer {

    private final List<String> prefixes;
    private final Instrumentation inst;
    private final Module hooksModule;
    private final AgentLog log;
    private final Set<String> openedPackages = ConcurrentHashMap.newKeySet();

    SnapshotOpenTransformer(List<String> prefixes, Instrumentation inst, Module hooksModule, AgentLog log) {
        this.prefixes = prefixes;
        this.inst = inst;
        this.hooksModule = hooksModule;
        this.log = log;
    }

    /** Whether a dotted binary name falls under one of the {@code snapshotOpen} prefixes. */
    static boolean matches(List<String> prefixes, String dottedName) {
        for (final String p : prefixes) {
            if (dottedName.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public byte[] transform(
            Module module,
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        try {
            // className is null for hidden classes; unnamed modules are open by definition.
            if (className == null || module == null || !module.isNamed()) {
                return null;
            }
            final int slash = className.lastIndexOf('/');
            if (slash < 0) {
                return null;
            }
            final String dotted = className.replace('/', '.');
            if (!matches(prefixes, dotted)) {
                return null;
            }
            final String pkg = dotted.substring(0, slash); // same index: '/' and '.' counts match
            if (!openedPackages.add(module.getName() + "/" + pkg)) {
                return null;
            }
            if (!inst.isModifiableModule(module) || module.isOpen(pkg, hooksModule)) {
                return null;
            }
            inst.redefineModule(module, Set.of(), Map.of(), Map.of(pkg, Set.of(hooksModule)), Set.of(), Map.of());
            log.debug("opened " + pkg + " (module " + module.getName() + ") for snapshots");
        } catch (Throwable t) {
            // Never break a class load: the open is best-effort; snapshots degrade to empty.
        }
        return null;
    }
}
