// SPDX-License-Identifier: Apache-2.0
package io.hindsight.runtime;

/**
 * On-disk format constants for the fixed 40-byte record stream and the blob stream, verbatim
 * from FORMAT.md §2–§3. Kept in one place so the writer ({@link ThreadWorker}) and any
 * in-process consumer (tests) cannot drift apart on a magic number.
 *
 * <p>Public on purpose: the reader module has its own copy (it must not depend on the
 * bootstrap jar), but tests and diagnostic tooling running inside the recorded JVM use these.
 */
public final class Wire {

    private Wire() {}

    /** Fixed size of one record in {@code records-<tid>.bin} (§2). */
    public static final int RECORD_SIZE = 40;

    // ---------- record types (§2.1) ----------

    public static final byte TYPE_METHOD_ENTER = 0x02;
    public static final byte TYPE_METHOD_EXIT = 0x03;
    public static final byte TYPE_ARG = 0x04;
    public static final byte TYPE_LINE = 0x05;
    public static final byte TYPE_BRANCH = 0x06;
    public static final byte TYPE_FIELD_WRITE = 0x10;
    public static final byte TYPE_LOCAL_WRITE = 0x20;
    public static final byte TYPE_OBJECT_FIRST_SEEN = 0x60;
    public static final byte TYPE_SNAPSHOT = 0x61;
    public static final byte TYPE_THREAD_START = 0x70;
    public static final byte TYPE_TIME_SYNC = 0x72;

    // ---------- value kinds, 2 bits each in the flags byte (§2.2) ----------

    public static final int KIND_NONE = 0;
    public static final int KIND_INLINE = 1;
    public static final int KIND_OBJECT_ID = 2;
    public static final int KIND_BLOB = 3;

    // ---------- flag bits (§2.2) ----------

    /** {@code flags & BEFORE_KIND_MASK} = KIND of the {@code before} slot. */
    public static final int BEFORE_KIND_MASK = 0b0000_0011;

    /** {@code (flags & AFTER_KIND_MASK) >> AFTER_KIND_SHIFT} = KIND of the {@code after} slot. */
    public static final int AFTER_KIND_MASK = 0b0000_1100;

    public static final int AFTER_KIND_SHIFT = 2;

    public static final int FLAG_EXCEPTIONAL = 0b0001_0000;
    public static final int FLAG_STATIC = 0b0010_0000;
    public static final int FLAG_TRUNCATED = 0b0100_0000;
    public static final int FLAG_BOXED = 0b1000_0000;

    // ---------- value type tags, the aux high byte (§2.3) ----------

    public static final int TAG_REF = 0;
    public static final int TAG_INT = 1;
    public static final int TAG_LONG = 2;
    public static final int TAG_FLOAT = 3;
    public static final int TAG_DOUBLE = 4;
    public static final int TAG_BOOLEAN = 5;
    public static final int TAG_BYTE = 6;
    public static final int TAG_CHAR = 7;
    public static final int TAG_SHORT = 8;
    public static final int TAG_STRING = 9;
    public static final int TAG_NULL = 10;
    public static final int TAG_VOID = 12;

    // ---------- blob stream (§3) ----------

    public static final int BLOB_MAGIC = 0xB5;
    public static final int BLOB_KIND_UTF8 = 1;
    public static final int BLOB_KIND_SNAPSHOT = 2;
    public static final int BLOB_HEADER_SIZE = 6; // magic(1) + kind(1) + len(4)

    /** Max instance fields / elements / map entries captured by one snapshot payload (§3). */
    public static final int SNAPSHOT_MAX_FIELDS = 64;

    /** Max container-valued fields re-dumped by one sampling refresh (§7, recorder ≥ v1.3). */
    public static final int SNAPSHOT_MAX_CONTAINER_REDUMPS = 16;

    // ---------- snapshot payload forms (§3, schema 2) ----------

    public static final int SNAPSHOT_FORM_FIELDS = 0;
    public static final int SNAPSHOT_FORM_MAP = 1;
    public static final int SNAPSHOT_FORM_SET = 2;
    public static final int SNAPSHOT_FORM_LIST = 3;
    public static final int SNAPSHOT_FORM_ARRAY = 4;

    /** A further TIME_SYNC is emitted every this many records of a thread (§2.1). */
    public static final int TIME_SYNC_INTERVAL = 65_536;

    /** {@code aux} idx values are capped here; 255 means "≥ 255, see site table" (§2). */
    public static final int IDX_CAP = 255;
}
