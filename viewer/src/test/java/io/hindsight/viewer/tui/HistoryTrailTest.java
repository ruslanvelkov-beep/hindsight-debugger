// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.viewer.tui.HistoryTrail.HistoryEntry;
import org.junit.jupiter.api.Test;

/** Tests the navigable history trail model of VIEWER.md §11.5 (add/dedup, cap, rendering). */
final class HistoryTrailTest {

    private static HistoryEntry entry(final int tid, final long index, final long seq) {
        return new HistoryEntry(tid, index, seq, (int) seq, "com.foo.Bar", "run", "hedera-app", "com.foo");
    }

    @Test
    void appendsInOrderNewestLast() {
        HistoryTrail h = new HistoryTrail();
        assertTrue(h.add(entry(1, 10, 100)));
        assertTrue(h.add(entry(1, 12, 101)));
        assertEquals(2, h.size());
        assertEquals(100, h.entries().get(0).seq());
        assertEquals(101, h.entries().get(1).seq());
    }

    @Test
    void skipsConsecutiveDuplicate() {
        HistoryTrail h = new HistoryTrail();
        assertTrue(h.add(entry(1, 10, 100)));
        assertFalse(h.add(entry(1, 10, 100)));   // exactly equals the newest → skipped
        assertEquals(1, h.size());
        // A different position appends; the same position again after that is allowed (not consecutive).
        assertTrue(h.add(entry(1, 12, 101)));
        assertTrue(h.add(entry(1, 10, 100)));
        assertEquals(3, h.size());
    }

    @Test
    void ringDropsOldestBeyondCapacity() {
        HistoryTrail h = new HistoryTrail();
        for (int i = 0; i < HistoryTrail.CAPACITY + 5; i++) {
            h.add(entry(1, i, i));
        }
        assertEquals(HistoryTrail.CAPACITY, h.size());
        // Oldest five (indices/seqs 0..4) were evicted; the trail now starts at seq 5.
        assertEquals(5, h.entries().get(0).seq());
        assertEquals(HistoryTrail.CAPACITY + 4, h.entries().get(h.size() - 1).seq());
    }

    @Test
    void rendersWithMiddleDotAndSimpleClass() {
        HistoryEntry e = new HistoryEntry(3, 42, 777, 88, "com.hedera.node.app.Ingest", "submit",
                "hedera-app", "com.hedera.node.app");
        assertEquals(
                "seq 777 · t3 · line 88 · Ingest.submit · hedera-app · com.hedera.node.app",
                HistoryTrail.render(e));
    }

    @Test
    void renderToleratesUnknowns() {
        HistoryEntry e = new HistoryEntry(1, 0, 5, 0, null, null, "?", "(default)");
        assertEquals("seq 5 · t1 · line 0 · ?.? · ? · (default)", HistoryTrail.render(e));
    }

    @Test
    void packageOfExtractsBinaryPrefixOrDefault() {
        assertEquals("com.foo", HistoryTrail.packageOf("com.foo.Bar"));
        assertEquals("(default)", HistoryTrail.packageOf("Bar"));
        assertEquals("(default)", HistoryTrail.packageOf(null));
        assertEquals("(default)", HistoryTrail.packageOf(""));
    }
}
