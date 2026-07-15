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
    public static final int LINE = 0x05;
    public static final int BRANCH = 0x06;
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
    public static final int FORM_FIELDS = 0;
    public static final int FORM_MAP = 1;
    public static final int FORM_SET = 2;
    public static final int FORM_LIST = 3;
    public static final int FORM_ARRAY = 4;

    public static final int BLOB_UTF8 = 1;
    public static final int BLOB_SNAPSHOT = 2;

    /** Runtime symbol ids are {@code 0x4000_0000 | n} (§4); agent ids are dense from 1. */
    public static final int RT_SYMBOL_BASE = 0x4000_0000;

    /** Operator text for a JVM conditional-jump opcode (stored in a BRANCH site's slot). */
    public static String branchOp(final int opcode) {
        return switch (opcode) {
            case 0x99 -> "== 0";   // IFEQ
            case 0x9A -> "!= 0";   // IFNE
            case 0x9B -> "< 0";    // IFLT
            case 0x9C -> ">= 0";   // IFGE
            case 0x9D -> "> 0";    // IFGT
            case 0x9E -> "<= 0";   // IFLE
            case 0x9F -> "==";     // IF_ICMPEQ
            case 0xA0 -> "!=";     // IF_ICMPNE
            case 0xA1 -> "<";      // IF_ICMPLT
            case 0xA2 -> ">=";     // IF_ICMPGE
            case 0xA3 -> ">";      // IF_ICMPGT
            case 0xA4 -> "<=";     // IF_ICMPLE
            case 0xA5 -> "== (ref)"; // IF_ACMPEQ
            case 0xA6 -> "!= (ref)"; // IF_ACMPNE
            case 0xC6 -> "== null";  // IFNULL
            case 0xC7 -> "!= null";  // IFNONNULL
            default -> "op" + opcode;
        };
    }

    /**
     * For a two-int-operand comparison opcode, whether {@code a <op> b} holds — lets the reader
     * show the branch's boolean result truthfully. Single-operand and ref opcodes return the
     * result from their recorded value instead (see the reader).
     */
    public static boolean intCmpResult(final int opcode, final long a, final long b) {
        return switch (opcode) {
            case 0x9F -> a == b;  // IF_ICMPEQ
            case 0xA0 -> a != b;  // IF_ICMPNE
            case 0xA1 -> a < b;   // IF_ICMPLT
            case 0xA2 -> a >= b;  // IF_ICMPGE
            case 0xA3 -> a > b;   // IF_ICMPGT
            case 0xA4 -> a <= b;  // IF_ICMPLE
            default -> false;
        };
    }

    /** For a single-int-operand opcode (compare vs 0), whether the condition holds. */
    public static boolean intZeroCmpResult(final int opcode, final long v) {
        return switch (opcode) {
            case 0x99 -> v == 0;  // IFEQ
            case 0x9A -> v != 0;  // IFNE
            case 0x9B -> v < 0;   // IFLT
            case 0x9C -> v >= 0;  // IFGE
            case 0x9D -> v > 0;   // IFGT
            case 0x9E -> v <= 0;  // IFLE
            default -> false;
        };
    }

    /** Whether {@code opcode} is a two-int-operand comparison ({@code IF_ICMP*}). */
    public static boolean isIntCmp(final int opcode) {
        return opcode >= 0x9F && opcode <= 0xA4;
    }

    /** Whether {@code opcode} is a single-int-operand comparison against zero ({@code IF<cond>}). */
    public static boolean isIntZeroCmp(final int opcode) {
        return opcode >= 0x99 && opcode <= 0x9E;
    }

    /**
     * Result of a reference branch, given the equality/null-ness the hook computed. The hook
     * records {@code ref==null} (null tests) or {@code a==b} (identity tests) regardless of
     * polarity; this flips it for the {@code !=} opcodes.
     */
    public static boolean refCmpResult(final int opcode, final boolean equalOrNull) {
        return switch (opcode) {
            case 0xC7, 0xA6 -> !equalOrNull; // IFNONNULL, IF_ACMPNE
            default -> equalOrNull;          // IFNULL, IF_ACMPEQ
        };
    }

    /** Human name of a record type, or {@code UNKNOWN(0xNN)} for out-of-spec bytes. */
    public static String typeName(final int type) {
        return switch (type) {
            case METHOD_ENTER -> "METHOD_ENTER";
            case METHOD_EXIT -> "METHOD_EXIT";
            case ARG -> "ARG";
            case LINE -> "LINE";
            case BRANCH -> "BRANCH";
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
            case METHOD_ENTER, METHOD_EXIT, ARG, LINE, BRANCH, FIELD_WRITE, LOCAL_WRITE,
                    OBJECT_FIRST_SEEN, SNAPSHOT, THREAD_START, TIME_SYNC -> true;
            default -> false;
        };
    }

    /** Whether {@code tag} is a defined §2.3 value tag (note: 11 is a hole). */
    public static boolean tagKnown(final int tag) {
        return (tag >= 0 && tag <= 10) || tag == TAG_VOID;
    }

    /**
     * {@code com.foo.Outer$Inner} → {@code Inner}; JVM array binary names decode to Java
     * syntax ({@code [B} → {@code byte[]}, {@code [Lcom.x.Foo;} → {@code Foo[]}). Used for
     * compact value rendering.
     */
    public static String simpleName(final String binaryName) {
        if (binaryName == null || binaryName.isEmpty()) {
            return "?";
        }
        if (binaryName.charAt(0) == '[') {
            int dims = 0;
            while (dims < binaryName.length() && binaryName.charAt(dims) == '[') {
                dims++;
            }
            if (dims >= binaryName.length()) {
                return binaryName;
            }
            String element = switch (binaryName.charAt(dims)) {
                case 'B' -> "byte";
                case 'C' -> "char";
                case 'D' -> "double";
                case 'F' -> "float";
                case 'I' -> "int";
                case 'J' -> "long";
                case 'S' -> "short";
                case 'Z' -> "boolean";
                case 'L' -> binaryName.endsWith(";")
                        ? simpleName(binaryName.substring(dims + 1, binaryName.length() - 1))
                        : binaryName.substring(dims);
                default -> binaryName.substring(dims);
            };
            return element + "[]".repeat(dims);
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
