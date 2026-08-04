// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.reader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

/**
 * Iterator over decoded records — either a single thread's file in order, or a
 * k-way merge of all thread files by global {@code seq} (FORMAT.md §2: {@code seq}
 * is the cross-thread total order).
 *
 * <p>The merge is a classic heap of per-thread heads keyed on the head record's
 * {@code seq}. It relies only on each <em>individual</em> file being seq-sorted;
 * a corrupted file with a seq regression still terminates (every record is
 * yielded exactly once, order best-effort), which matters because {@code verify}
 * iterates this cursor while diagnosing exactly such corruption.
 *
 * <p>{@link IOException} from lazy file mapping is rethrown as
 * {@link UncheckedIOException} so the cursor can implement {@link Iterator}.
 */
public final class RecordCursor implements Iterator<Rec> {

    /** One thread's read position; comparable by the seq of its next record. */
    private static final class Head {
        final int tid;
        final long count;
        long index;
        Rec next;

        Head(final int tid, final long count) {
            this.tid = tid;
            this.count = count;
        }
    }

    private final Recording recording;
    private final PriorityQueue<Head> heap;

    private RecordCursor(final Recording recording, final List<Integer> tids) {
        this.recording = recording;
        this.heap = new PriorityQueue<>((a, b) -> Long.compare(a.next.seq(), b.next.seq()));
        try {
            for (int tid : tids) {
                Head h = new Head(tid, recording.recordCount(tid));
                if (advance(h)) {
                    heap.add(h);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Cursor over a single thread's records in file order. */
    static RecordCursor single(final Recording recording, final int tid) {
        return new RecordCursor(recording, List.of(tid));
    }

    /** Merged cursor over the given threads, ordered by {@code seq}. */
    static RecordCursor merged(final Recording recording, final List<Integer> tids) {
        return new RecordCursor(recording, tids);
    }

    private boolean advance(final Head h) throws IOException {
        if (h.index >= h.count) {
            h.next = null;
            return false;
        }
        h.next = recording.recordAt(h.tid, h.index++);
        return true;
    }

    @Override
    public boolean hasNext() {
        return !heap.isEmpty();
    }

    @Override
    public Rec next() {
        Head h = heap.poll();
        if (h == null) {
            throw new NoSuchElementException();
        }
        Rec r = h.next;
        try {
            if (advance(h)) {
                heap.add(h);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return r;
    }
}
