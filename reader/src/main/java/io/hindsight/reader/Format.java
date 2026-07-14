// SPDX-License-Identifier: Apache-2.0
package io.hindsight.reader;

/**
 * Constants and tiny pure helpers for the v1 on-disk format (FORMAT.md §2–§3):
 * record types, value kinds, value tags, flag bits, blob framing, file naming.
 *
 * <p>Kept as plain {@code int} constants rather than enums on purpose: the reader
 * must tolerate out-of-spec bytes in a corrupted or hand-damaged recording, and an
 * unknown byte still needs to flow through verification (to be reported with file
 * and record index) instead of failing an enum lookup deep inside iteration.
 */
public final class Format {

    private Format() {}

    /** Fixed size of one event record in {@code records-<tid>.bin} (§2). */
    public static final int RECORD_SIZE = 40;

    /** First byte of every blob entry (§3). */
    public static final int BLOB_MAGIC = 0xB5;

    /** magic(1) + kind(1) + len(4) preceding each blob payload (§3). */
    public static final int BLOB_HEADER_SIZE = 6;

    // ---- record types (§2.1) ----
    public static final int METHOD_ENTER = 0x02;
    public static final int METHOD_EXIT = 0x03;
    public static final int ARG = 0x04;
    public static final int FIELD_WRITE = 0x10;
    public static final int LOCAL_WRITE = 0x20;
    public static final int OBJECT_FIRST_SEEN = 0x60;
    public static final int SNAPSHOT = 0x61;
    public static final int THREAD_START = 0x70;
    public static final int TIME_SYNC = 0x72;

    // ---- value kinds (§2.2, 2-bit fields) ----
    public static final int KIND_NONE = 0;
    public static final int KIND_INLINE = 1;
    public static final int KIND_OBJECT_ID = 2;
    public static final int KIND_BLOB = 3;

    // ---- flag bits (§2.2) ----
    public static final int FLAG_EXCEPTIONAL = 1 << 4;
    public static final int FLAG_STATIC = 1 << 5;
    public static final int FLAG_TRUNCATED = 1 << 6;
    public static final int FLAG_BOXED = 1 << 7;

    // ---- value type tags (§2.3) ----
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

    // ---- blob entry kinds (§3) ----
    public static final int BLOB_UTF8 = 1;
    public static final int BLOB_SNAPSHOT = 2;

    /** Runtime symbol ids are {@code 0x4000_0000 | n} (§4); agent ids are dense from 1. */
    public static final int RT_SYMBOL_BASE = 0x4000_0000;

    /** Human name of a record type, or {@code UNKNOWN(0xNN)} for out-of-spec bytes. */
    public static String typeName(final int type) {
        return switch (type) {
            case METHOD_ENTER -> "METHOD_ENTER";
            case METHOD_EXIT -> "METHOD_EXIT";
            case ARG -> "ARG";
            case FIELD_WRITE -> "FIELD_WRITE";
            case LOCAL_WRITE -> "LOCAL_WRITE";
            case OBJECT_FIRST_SEEN -> "OBJECT_FIRST_SEEN";
            case SNAPSHOT -> "SNAPSHOT";
            case THREAD_START -> "THREAD_START";
            case TIME_SYNC -> "TIME_SYNC";
            default -> String.format("UNKNOWN(0x%02X)", type);
        };
    }

    /** Whether {@code type} is one of the nine v1 record types. */
    public static boolean typeKnown(final int type) {
        return switch (type) {
            case METHOD_ENTER, METHOD_EXIT, ARG, FIELD_WRITE, LOCAL_WRITE,
                    OBJECT_FIRST_SEEN, SNAPSHOT, THREAD_START, TIME_SYNC -> true;
            default -> false;
        };
    }

    /** Whether {@code tag} is a defined §2.3 value tag (note: 11 is a hole). */
    public static boolean tagKnown(final int tag) {
        return (tag >= 0 && tag <= 10) || tag == TAG_VOID;
    }

    /** {@code com.foo.Outer$Inner} → {@code Inner}; used for compact value rendering. */
    public static String simpleName(final String binaryName) {
        if (binaryName == null || binaryName.isEmpty()) {
            return "?";
        }
        int cut = Math.max(binaryName.lastIndexOf('.'), binaryName.lastIndexOf('$'));
        return cut >= 0 && cut < binaryName.length() - 1 ? binaryName.substring(cut + 1) : binaryName;
    }

    /** {@code records-00003.bin} for tid 3 (§1: 5-digit zero-padded). */
    public static String recordsFileName(final int tid) {
        return String.format("records-%05d.bin", tid);
    }

    /** {@code blob-00003.bin} for tid 3. */
    public static String blobFileName(final int tid) {
        return String.format("blob-%05d.bin", tid);
    }
}
