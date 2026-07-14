// SPDX-License-Identifier: Apache-2.0
package io.hindsight.reader;

/**
 * One decoded 40-byte event record (FORMAT.md §2), plus its provenance
 * ({@code tid} = the dense thread id from the file name, {@code index} = the
 * record's position within that file). Provenance travels with the record so
 * that verification failures and blob lookups can always be attributed to an
 * exact byte range without threading extra state through every consumer.
 *
 * <p>All raw fields are kept exactly as read; interpretation helpers below
 * decode the packed {@code flags}/{@code aux} bits. The reader materialises one
 * small immutable object per visited record — a deliberate simplicity/throughput
 * trade-off that is fine for an offline analysis tool.
 *
 * @param tid dense per-process thread id (from {@code records-<tid>.bin})
 * @param index zero-based record index within the thread file
 * @param seq global monotonic sequence number (cross-thread total order)
 * @param type record type byte (§2.1)
 * @param flags flag bits (§2.2)
 * @param tag value type tag — high byte of {@code aux} (§2.3)
 * @param idx arg index / local slot / ENTER arg count — low byte of {@code aux}
 * @param location site id, or a symbol id for {@code OBJECT_FIRST_SEEN}/{@code THREAD_START}
 * @param target object id column (§2.1); 0 = null / not applicable
 * @param before encoded "before" value slot (§2.4)
 * @param after encoded "after" value slot (§2.4)
 */
public record Rec(
        int tid,
        long index,
        long seq,
        int type,
        int flags,
        int tag,
        int idx,
        int location,
        long target,
        long before,
        long after) {

    /** 2-bit encoding kind of the {@code before} slot (§2.2 bits 0–1). */
    public int beforeKind() {
        return flags & 0b11;
    }

    /** 2-bit encoding kind of the {@code after} slot (§2.2 bits 2–3). */
    public int afterKind() {
        return (flags >> 2) & 0b11;
    }

    /** {@code METHOD_EXIT} unwound via throw (§2.2 bit 4). */
    public boolean isExceptional() {
        return (flags & Format.FLAG_EXCEPTIONAL) != 0;
    }

    /** {@code FIELD_WRITE} on a static field (§2.2 bit 5). */
    public boolean isStatic() {
        return (flags & Format.FLAG_STATIC) != 0;
    }

    /** A string in this record's blob payload was cut at {@code maxString} (§2.2 bit 6). */
    public boolean isTruncated() {
        return (flags & Format.FLAG_TRUNCATED) != 0;
    }

    /** Value was a boxed primitive recorded INLINE with its primitive tag (§2.2 bit 7). */
    public boolean isBoxed() {
        return (flags & Format.FLAG_BOXED) != 0;
    }

    /** {@code records-<tid>.bin} — the file this record was read from. */
    public String fileName() {
        return Format.recordsFileName(tid);
    }
}
