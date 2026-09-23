// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.hooks.MethodHooks;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * §9 worker registry: a live thread whose ThreadLocal was erased (what
 * {@code ForkJoinPool.commonPool()} workers do after every top-level task) must get its
 * registered worker back instead of a second one — otherwise the first worker's partial
 * chunks are orphaned and the thread's stream is split across tids.
 */
class WorkerReuseTest {

    @TempDir
    Path dir;

    @AfterEach
    void shutdown() {
        HindsightRuntime.shutdownForTests();
    }

    @Test
    void erasedThreadLocalReturnsTheRegisteredWorker() {
        HindsightRuntime.boot(Recordings.config(dir));
        final ThreadWorker first = HindsightRuntime.worker();
        HindsightRuntime.instanceForTests().workerTL.remove(); // what eraseThreadLocals does
        final ThreadWorker again = HindsightRuntime.worker();
        assertSame(first, again, "same live thread must map to the same worker");
        assertEquals(1, HindsightRuntime.instanceForTests().workers.size());
    }

    @Test
    void commonPoolTasksKeepEveryRecordAndOneThreadStartPerWorker() throws IOException {
        HindsightRuntime.boot(Recordings.config(dir));
        final int tasks = 400;
        // Each top-level task records one ENTER/EXIT pair; between tasks the common pool
        // erases the worker thread's ThreadLocals.
        IntStream.range(0, tasks).forEach(i -> ForkJoinPool.commonPool()
                .submit(() -> {
                    MethodHooks.enter(7, null, new Object[0]);
                    MethodHooks.exit(7, null, Wire.TAG_VOID);
                })
                .join());
        HindsightRuntime.shutdownForTests();

        final List<Recordings.Rec> recs = Recordings.parseAll(dir);
        final long enters = recs.stream().filter(r -> r.type() == Wire.TYPE_METHOD_ENTER).count();
        final long exits = recs.stream().filter(r -> r.type() == Wire.TYPE_METHOD_EXIT).count();
        final long threadStarts = recs.stream().filter(r -> r.type() == Wire.TYPE_THREAD_START).count();
        assertEquals(tasks, enters, "every task's ENTER must survive");
        assertEquals(tasks, exits, "every task's EXIT must survive");
        // One THREAD_START per real thread (pool workers + possibly this thread), never per task.
        assertTrue(threadStarts <= ForkJoinPool.getCommonPoolParallelism() + 2,
                "thread starts must count threads, not tasks: " + threadStarts);
    }
}
