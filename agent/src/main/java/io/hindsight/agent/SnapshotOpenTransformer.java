// SPDX-License-Identifier: GPL-3.0-or-later
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
 * bootstrap-unnamed hooks module; data/model classes (generated data records and the like) are
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
 *
 * <p>Recorder ≥ v1.5 (§10): classes in boot-layer modules — {@code java.base} above all —
 * may be loaded long before premain ({@code java.time.Instant} and friends), so their
 * packages never see a class-load event here and their snapshots silently came out empty.
 * {@link #openBootLayerPackages()} therefore opens every matching package of every
 * boot-layer module EAGERLY at premain; this lazy transformer stays installed for modules
 * defined later (app layers, plugin loaders).
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

    /**
     * Whether package {@code pkg} is covered by one of the {@code snapshotOpen} prefixes for
     * the §10 eager boot-layer opens: either the package lies under the prefix
     * ({@code (pkg + ".").startsWith(prefix)} — so {@code java.time.} covers both
     * {@code java.time} itself and {@code java.time.format}), or the prefix names a class or
     * subpackage inside it ({@code prefix.startsWith(pkg + ".")} — so a class prefix like
     * {@code com.example.proto.runtime.OneOf} covers {@code com.example.proto.runtime}).
     */
    static boolean packageMatches(List<String> prefixes, String pkg) {
        final String pkgDot = pkg + ".";
        for (final String p : prefixes) {
            if (pkgDot.startsWith(p) || p.startsWith(pkgDot)) {
                return true;
            }
        }
        return false;
    }

    /**
     * §10 eager opens (recorder ≥ v1.5): opens every {@code snapshotOpen}-matched package of
     * every named boot-layer module to the hooks module at premain — classes there may have
     * loaded before the agent, so the lazy class-load path never fires for them. Failures are
     * best-effort per package (debug-logged); returns how many packages were actually opened.
     */
    int openBootLayerPackages() {
        int opened = 0;
        for (final Module module : ModuleLayer.boot().modules()) {
            if (!module.isNamed()) {
                continue;
            }
            for (final String pkg : module.getPackages()) {
                if (!packageMatches(prefixes, pkg)) {
                    continue;
                }
                try {
                    if (openPackage(module, pkg)) {
                        opened++;
                    }
                } catch (Throwable t) {
                    log.debug("could not eagerly open " + pkg + " (module " + module.getName()
                            + ") for snapshots: " + t);
                }
            }
        }
        return opened;
    }

    /**
     * Opens one package to the hooks module, deduplicated per {@code module/pkg}; returns true
     * iff this call performed the {@code redefineModule}. Shared by the eager boot-layer pass
     * and the lazy class-load path; any throw is the caller's to swallow.
     */
    private boolean openPackage(Module module, String pkg) {
        if (!openedPackages.add(module.getName() + "/" + pkg)) {
            return false;
        }
        if (!inst.isModifiableModule(module) || module.isOpen(pkg, hooksModule)) {
            return false;
        }
        inst.redefineModule(module, Set.of(), Map.of(), Map.of(pkg, Set.of(hooksModule)), Set.of(), Map.of());
        log.debug("opened " + pkg + " (module " + module.getName() + ") for snapshots");
        return true;
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
            openPackage(module, pkg);
        } catch (Throwable t) {
            // Never break a class load: the open is best-effort; snapshots degrade to empty.
        }
        return null;
    }
}
