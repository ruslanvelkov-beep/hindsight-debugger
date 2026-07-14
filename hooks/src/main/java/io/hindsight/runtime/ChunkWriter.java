// SPDX-License-Identifier: Apache-2.0
package io.hindsight.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single {@code hindsight-writer} daemon (FORMAT.md §9): the only thread in the process
 * that touches recording {@link FileChannel}s.
 *
 * <p>Workers hand over full (or stolen partial) buffer chunks through a bounded queue.
 * Producers block when the queue is full — deliberate backpressure instead of data loss —
 * but only up to a hard timeout, because a hook must never be able to hang an application
 * thread forever (e.g. if the writer thread has died). On timeout the chunk is dropped and
 * the incident reported once.
 *
 * <p>Channels are opened lazily per {@code (tid, stream)} so short-lived threads that never
 * record don't litter the directory. The writer also enforces the {@code maxTotalMB} cap:
 * crossing it stops recording and drops the {@code TRUNCATED} marker, but chunks already
 * queued are still written out (they were recorded before the cap hit).
 */
final class ChunkWriter implements Runnable {

    /** A buffer handed from a worker to the writer; {@code buf} is flipped, ready to drain. */
    static final class Chunk {
        final int tid;
        final boolean blob;
        final ByteBuffer buf;

        Chunk(int tid, boolean blob, ByteBuffer buf) {
            this.tid = tid;
            this.blob = blob;
            this.buf = buf;
        }
    }

    private static final Chunk POISON = new Chunk(-1, false, ByteBuffer.allocate(0));

    /** How long a producer may block on a full queue before dropping the chunk. */
    private static final long SUBMIT_TIMEOUT_SECONDS = 10;

    private final Path dir;
    private final long capBytes;
    private final ArrayBlockingQueue<Chunk> queue = new ArrayBlockingQueue<>(256);
    private final Map<Long, FileChannel> channels = new HashMap<>(); // writer thread only
    private final ConcurrentLinkedQueue<ByteBuffer> recordFree = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ByteBuffer> blobFree = new ConcurrentLinkedQueue<>();
    private final int recordBufBytes;
    private final int blobBufBytes;
    private volatile Thread thread;
    private final AtomicBoolean truncatedMarkerWritten = new AtomicBoolean();
    private volatile boolean shuttingDown;
    private long totalBytes; // writer thread only

    ChunkWriter(Path dir, long maxTotalMB, int recordBufKB, int blobBufKB) {
        this.dir = dir;
        this.capBytes = maxTotalMB <= 0 ? Long.MAX_VALUE : maxTotalMB * 1024L * 1024L;
        this.recordBufBytes = Math.max(4, recordBufKB) * 1024;
        this.blobBufBytes = Math.max(4, blobBufKB) * 1024;
    }

    /** Starts the daemon; separate from the constructor so {@code this} never escapes early. */
    void start() {
        final Thread t = new Thread(this, "hindsight-writer");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /** True when {@code t} is the writer's own thread (its worker is permanently suspended). */
    boolean isWriterThread(Thread t) {
        return t == thread;
    }

    /** Fresh (or recycled) record buffer, little-endian, ready for a worker to fill. */
    ByteBuffer takeRecordBuffer() {
        final ByteBuffer b = recordFree.poll();
        return b != null ? b : ByteBuffer.allocateDirect(recordBufBytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Fresh (or recycled) blob buffer, little-endian, ready for a worker to fill. */
    ByteBuffer takeBlobBuffer() {
        final ByteBuffer b = blobFree.poll();
        return b != null ? b : ByteBuffer.allocateDirect(blobBufBytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Hands a filled (already flipped) chunk to the writer. Blocks briefly for backpressure;
     * never blocks unboundedly and never throws. Returns whether the chunk was accepted.
     *
     * <p>Two hard-won properties: (1) a caller's <em>pending interrupt</em> must not discard
     * recorded data — {@code ArrayBlockingQueue.offer} throws immediately when the interrupt
     * flag is already set (Hedera threads carry it routinely), so the wait is uninterruptible
     * with the flag restored on exit; (2) a dropped chunk permanently corrupts the thread's
     * stream (offsets already assume the bytes exist), so on a real timeout the whole
     * recording is stopped loudly (TRUNCATED marker) instead of silently continuing.
     */
    boolean submit(int tid, boolean blob, ByteBuffer flipped) {
        final Chunk chunk = new Chunk(tid, blob, flipped);
        boolean interrupted = false;
        try {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SUBMIT_TIMEOUT_SECONDS);
            while (true) {
                final long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    markTruncated("writer queue full for " + SUBMIT_TIMEOUT_SECONDS
                            + "s; chunk dropped, recording stopped to preserve integrity");
                    HindsightRuntime.stopRecording();
                    HindsightRuntime.reportInternalError(
                            "writer-queue-timeout",
                            new IllegalStateException("hindsight-writer queue full for "
                                    + SUBMIT_TIMEOUT_SECONDS + "s; recording stopped"));
                    return false;
                }
                try {
                    if (queue.offer(chunk, remainingNanos, TimeUnit.NANOSECONDS)) {
                        return true;
                    }
                } catch (InterruptedException ie) {
                    interrupted = true; // remember, restore below; never drop data over it
                }
            }
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError("writer-submit", t);
            return false;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void run() {
        try {
            while (true) {
                final Chunk c = queue.take();
                if (c == POISON) {
                    break;
                }
                write(c);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError("writer-loop", t);
            markTruncated("internal writer error: " + t);
            HindsightRuntime.stopRecording();
        }
    }

    private void write(Chunk c) {
        try {
            final FileChannel ch = channelFor(c.tid, c.blob);
            final int size = c.buf.remaining();
            while (c.buf.hasRemaining()) {
                ch.write(c.buf);
            }
            totalBytes += size;
            if (totalBytes > capBytes && HindsightRuntime.isRecording()) {
                markTruncated("size cap exceeded: " + totalBytes + " bytes > maxTotalMB cap");
                HindsightRuntime.stopRecording();
            }
            recycle(c);
        } catch (Throwable t) {
            // A failed write (disk full, revoked volume) leaves a hole at offsets that
            // later records already reference — integrity is gone; stop loudly.
            HindsightRuntime.reportInternalError("writer-write", t);
            markTruncated("write failed: " + t);
            HindsightRuntime.stopRecording();
        }
    }

    private void recycle(Chunk c) {
        final ConcurrentLinkedQueue<ByteBuffer> free = c.blob ? blobFree : recordFree;
        if (free.size() < 64) { // bound the pool; beyond that let the GC have them
            c.buf.clear();
            free.offer(c.buf);
        }
    }

    private FileChannel channelFor(int tid, boolean blob) throws IOException {
        final long key = ((long) tid << 1) | (blob ? 1 : 0);
        FileChannel ch = channels.get(key);
        if (ch == null) {
            final String name = String.format("%s-%05d.bin", blob ? "blob" : "records", tid);
            ch = FileChannel.open(
                    dir.resolve(name),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            channels.put(key, ch);
        }
        return ch;
    }

    /** Creates the {@code TRUNCATED} marker (§1) at most once; first line is the reason. */
    void markTruncated(String reason) {
        if (!truncatedMarkerWritten.compareAndSet(false, true)) {
            return;
        }
        try {
            Files.write(dir.resolve("TRUNCATED"), (reason + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            HindsightRuntime.reportInternalError("truncated-marker", t);
        }
    }

    /**
     * Shutdown-hook path: stops the loop, then drains whatever is left on the caller's thread
     * (covers a dead writer thread too), and force+closes every channel. Idempotent enough to
     * be called once from the runtime's own idempotent shutdown.
     */
    void shutdownAndDrain() {
        shuttingDown = true;
        final Thread t = thread;
        if (t != null) {
            try {
                // Nudge the loop to exit; if the queue is jammed, the join timeout below saves us.
                if (!queue.offer(POISON, 2, TimeUnit.SECONDS)) {
                    t.interrupt();
                }
                t.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        // Only touch the (non-thread-safe) channels map when the writer thread is confirmed
        // dead — draining concurrently with a merely-wedged writer would interleave torn
        // chunk writes and corrupt the HashMap. If it is still alive we leave everything to
        // it (a wedged daemon at JVM exit loses its tail either way) and just mark the loss.
        if (t != null && t.isAlive()) {
            markTruncated("shutdown while writer thread still alive (wedged); tail not drained");
            HindsightRuntime.reportInternalError(
                    "writer-wedged",
                    new IllegalStateException("hindsight-writer did not exit within 10s at shutdown"));
            return;
        }
        Chunk c;
        while ((c = queue.poll()) != null) {
            if (c != POISON) {
                write(c);
            }
        }
        for (final FileChannel ch : channels.values()) {
            try {
                ch.force(false);
                ch.close();
            } catch (Throwable closeError) {
                HindsightRuntime.reportInternalError("writer-close", closeError);
            }
        }
        channels.clear();
    }

    boolean isShuttingDown() {
        return shuttingDown;
    }
}
