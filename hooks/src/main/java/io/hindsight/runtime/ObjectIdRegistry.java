// SPDX-License-Identifier: Apache-2.0
package io.hindsight.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Assigns and looks up stable 64-bit IDs for arbitrary object references (FORMAT.md §6).
 *
 * <p>{@code System.identityHashCode} is not unique — two distinct objects can hash to the
 * same value. The recorder needs strictly-unique IDs so snapshots and field-write events can
 * be correlated to the same object across the run. IDs are process-global, positive, strictly
 * increasing, and never reused (0 is reserved for {@code null}); the weak-keyed map slot of a
 * GC'd object is reclaimed, its ID is not.
 *
 * <p>Per §2.1/§6 the {@code OBJECT_FIRST_SEEN} record must carry a {@code seq} strictly lower
 * than any record referencing the id. That ordering is enforced structurally, but WITHOUT
 * doing I/O under the map bin lock: inside the {@code computeIfAbsent} mapping function only
 * the id and its {@code seq} are <em>reserved</em> (two atomic increments); the record's bytes
 * are written right after the function returns, on the same thread. A racing thread resolving
 * the same object blocks until the mapping function returns, so any {@code seq} it later takes
 * for a referencing record is necessarily greater than the reserved one — merge order stays
 * correct even if the reserving thread hasn't materialised the bytes yet. Emitting inside the
 * lock (the previous design) let a full writer queue park a thread for seconds while holding a
 * bin lock, stalling every other thread registering into that bin or a concurrent resize.
 *
 * <p>{@code String} and boxed primitives are never registered (§6 — they are recorded by
 * value); the check here is a defensive backstop for the encoders, returning the null id.
 *
 * <p>Dead-reference reclamation is amortized off the hot path: the reference queue is drained
 * only every {@link #DRAIN_INTERVAL} registrations.
 */
public final class ObjectIdRegistry {

    private static final AtomicLong NEXT_ID = new AtomicLong(1); // 0 reserved for null

    private static final ConcurrentHashMap<IdentityKey, Long> MAP = new ConcurrentHashMap<>();

    private static final ReferenceQueue<Object> DEAD = new ReferenceQueue<>();

    private static final int DRAIN_INTERVAL = 1024;

    private static final AtomicInteger CALLS = new AtomicInteger();

    private ObjectIdRegistry() {}

    /**
     * Returns the stable ID for {@code o}, allocating a new one on first sight. When a new id
     * is allocated and {@code emitter} is non-null, the emitter writes the
     * {@code OBJECT_FIRST_SEEN} record before the id escapes this method (see class doc).
     * Returns 0 for {@code null} and for by-value types that must never be registered.
     */
    public static long idFor(Object o, ThreadWorker emitter) {
        if (o == null || isByValue(o)) {
            return 0L;
        }
        if ((CALLS.incrementAndGet() & (DRAIN_INTERVAL - 1)) == 0) {
            drainDead();
        }
        final Long cached = MAP.get(new LookupKey(o));
        if (cached != null) {
            return cached;
        }
        final Long id = MAP.computeIfAbsent(new IdentityKey(o), k -> {
            final long newId = NEXT_ID.getAndIncrement();
            if (emitter != null) {
                emitter.notePendingFirstSeen(newId, o); // reserves the seq; no I/O
            }
            return newId;
        });
        if (emitter != null) {
            emitter.drainPendingFirstSeen(); // materialise the reserved record outside the bin lock
        }
        return id;
    }

    /** Types recorded by value per §2.4 — never given an identity (§6). */
    private static boolean isByValue(Object o) {
        final Class<?> c = o.getClass();
        return c == String.class
                || c == Integer.class
                || c == Long.class
                || c == Short.class
                || c == Byte.class
                || c == Character.class
                || c == Boolean.class
                || c == Float.class
                || c == Double.class;
    }

    private static void drainDead() {
        Object k;
        while ((k = DEAD.poll()) != null) {
            if (k instanceof IdentityKey ik) {
                MAP.remove(ik);
            }
        }
    }

    /** Test hook: forces a reference-queue drain regardless of the amortization counter. */
    static void drainNowForTests() {
        drainDead();
    }

    /** Test hook: current number of live (not yet reclaimed) registrations. */
    static int sizeForTests() {
        return MAP.size();
    }

    /**
     * Wraps a {@code WeakReference} with {@code System.identityHashCode}-based hash and
     * reference equality for use as a {@link ConcurrentHashMap} key.
     */
    private static final class IdentityKey extends WeakReference<Object> {
        final int hash;

        IdentityKey(Object referent) {
            super(referent, DEAD);
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            final Object mine = get();
            if (o instanceof IdentityKey other) {
                final Object theirs = other.get();
                return mine != null && mine == theirs;
            }
            if (o instanceof LookupKey other) {
                return mine != null && mine == other.referent;
            }
            return false;
        }
    }

    /**
     * Strong-reference probe used for the read path so a mere lookup doesn't allocate a
     * {@code WeakReference} enqueued on {@link #DEAD}. Symmetric with {@link IdentityKey}'s
     * equals/hashCode so {@code ConcurrentHashMap.get} matches stored keys.
     */
    private static final class LookupKey {
        final Object referent;
        final int hash;

        LookupKey(Object referent) {
            this.referent = referent;
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o instanceof IdentityKey other) {
                return other.get() == referent;
            }
            return o instanceof LookupKey other && other.referent == referent;
        }
    }
}
