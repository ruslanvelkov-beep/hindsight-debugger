// SPDX-License-Identifier: Apache-2.0
package io.hindsight.agent;

import io.hindsight.runtime.HindsightRuntime;
import io.hindsight.runtime.RuntimeConfig;
import io.hindsight.runtime.TestRuntime;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.pool.TypePool;

/**
 * Transformer-level test harness: boots the REAL hooks runtime into a temp dir, runs fixture
 * classes through the exact production composition ({@link HindsightTransformer#compose}),
 * loads the result into a throwaway classloader (so a {@code VerifyError} fails the test at
 * first use), and hands back the parsed on-disk recording.
 *
 * <p>No premain, no {@code Instrumentation}: the point is to test the bytecode passes and the
 * on-disk format against the frozen spec, not ByteBuddy's installer plumbing.
 */
final class TestBed implements AutoCloseable {

    final Path recordingDir;
    final SiteRegistry registry;
    final HindsightTransformer transformer;
    final ClassLoader loader;

    private TestBed(Path recordingDir, SiteRegistry registry, HindsightTransformer transformer, ClassLoader loader) {
        this.recordingDir = recordingDir;
        this.registry = registry;
        this.transformer = transformer;
        this.loader = loader;
    }

    /**
     * Boots the runtime under {@code tempDir}, instruments the given classes at {@code scope}
     * and prepares a fresh loader that defines the instrumented bytes.
     */
    static TestBed start(Path tempDir, AgentConfig.Scope scope, String... classNames) throws IOException {
        final RuntimeConfig runtimeConfig = new RuntimeConfig();
        runtimeConfig.outDir = tempDir.resolve("recording");
        runtimeConfig.snapshotEvery = 0; // deterministic records
        runtimeConfig.autostart = true;
        HindsightRuntime.boot(runtimeConfig);
        final Path recordingDir = Objects.requireNonNull(HindsightRuntime.recordingDir(), "runtime failed to boot");

        final AgentLog log = new AgentLog(recordingDir, false);
        final SiteRegistry registry = new SiteRegistry(recordingDir, null, null, log);
        final AgentConfig config = AgentConfig.parse("hooks=unused.jar,include=io.hindsight.agent.fixtures.");
        final HindsightTransformer transformer = new HindsightTransformer(config, registry, log);

        final ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(TestBed.class.getClassLoader());
        final TypePool pool = TypePool.Default.of(locator);
        final Map<String, byte[]> transformed = new HashMap<>();
        for (final String className : classNames) {
            final TypeDescription type = pool.describe(className).resolve();
            final DynamicType.Unloaded<?> made =
                    transformer.compose(new ByteBuddy().decorate(type, locator), scope).make();
            transformed.put(className, made.getBytes());
        }
        final ClassLoader loader = new FixtureLoader(TestBed.class.getClassLoader(), transformed);
        return new TestBed(recordingDir, registry, transformer, loader);
    }

    /** Loads (and links, surfacing any VerifyError) an instrumented class. */
    Class<?> load(String className) throws ClassNotFoundException {
        return Class.forName(className, true, loader);
    }

    /** Invokes a static fixture method by name (unique names only). */
    Object invokeStatic(String className, String methodName, Object... args) throws Exception {
        final Class<?> type = load(className);
        for (final Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                m.setAccessible(true);
                return m.invoke(null, args);
            }
        }
        throw new NoSuchMethodException(className + "#" + methodName);
    }

    /** Stops the runtime (flushing all buffers to disk) and parses the recording. */
    Recorded stopAndParse() throws IOException {
        registry.close();
        TestRuntime.shutdown();
        return Recorded.parse(recordingDir);
    }

    /** Idempotent cleanup for tests that fail before {@link #stopAndParse()}. */
    @Override
    public void close() {
        TestRuntime.shutdown();
    }

    /** Child-first only for the transformed classes; everything else delegates to the parent. */
    private static final class FixtureLoader extends ClassLoader {

        private final Map<String, byte[]> classes;

        FixtureLoader(ClassLoader parent, Map<String, byte[]> classes) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    final byte[] bytes = classes.get(name);
                    if (bytes != null) {
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } else {
                        return super.loadClass(name, resolve);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
