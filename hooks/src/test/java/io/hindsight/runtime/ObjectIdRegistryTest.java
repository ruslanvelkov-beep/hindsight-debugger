// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link ObjectIdRegistry}: uniqueness, stability, by-value exclusions, weak collection (§6). */
class ObjectIdRegistryTest {

    @Test
    void idsAreUniquePositiveAndStable() {
        final Object[] objects = new Object[1_000];
        final Set<Long> ids = new HashSet<>();
        for (int i = 0; i < objects.length; i++) {
            objects[i] = new Object();
            final long id = ObjectIdRegistry.idFor(objects[i], null);
            assertTrue(id > 0, "ids must be positive");
            assertTrue(ids.add(id), "ids must be unique");
        }
        for (int i = 0; i < objects.length; i++) {
            assertTrue(ids.contains(ObjectIdRegistry.idFor(objects[i], null)), "ids must be stable");
        }
        assertEquals(objects.length, ids.size());
    }

    @Test
    void nullIsZero() {
        assertEquals(0L, ObjectIdRegistry.idFor(null, null));
    }

    @Test
    void byValueTypesAreNeverRegistered() {
        assertEquals(0L, ObjectIdRegistry.idFor("some string", null));
        assertEquals(0L, ObjectIdRegistry.idFor(Integer.valueOf(42), null));
        assertEquals(0L, ObjectIdRegistry.idFor(Long.valueOf(42), null));
        assertEquals(0L, ObjectIdRegistry.idFor(Short.valueOf((short) 4), null));
        assertEquals(0L, ObjectIdRegistry.idFor(Byte.valueOf((byte) 4), null));
        assertEquals(0L, ObjectIdRegistry.idFor(Character.valueOf('x'), null));
        assertEquals(0L, ObjectIdRegistry.idFor(Boolean.TRUE, null));
        assertEquals(0L, ObjectIdRegistry.idFor(Float.valueOf(1.5f), null));
        assertEquals(0L, ObjectIdRegistry.idFor(Double.valueOf(1.5), null));
    }

    @Test
    void deadObjectsAreEventuallyReclaimed() throws InterruptedException {
        final int before = ObjectIdRegistry.sizeForTests();
        for (int i = 0; i < 10_000; i++) {
            ObjectIdRegistry.idFor(new Object(), null); // no reference kept
        }
        assertTrue(ObjectIdRegistry.sizeForTests() >= before, "registrations should be visible");
        // Await weak collection: GC, then let the reference queue drain.
        final long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            System.gc();
            ObjectIdRegistry.drainNowForTests();
            if (ObjectIdRegistry.sizeForTests() < before + 1_000) {
                return; // the bulk of the 10k dead entries was reclaimed
            }
            Thread.sleep(50);
        }
        throw new AssertionError("dead entries were not reclaimed; size=" + ObjectIdRegistry.sizeForTests());
    }
}
