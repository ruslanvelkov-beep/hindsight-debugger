// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.engine;

import io.hindsight.reader.Format;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Test-only builder that assembles a schema-3 (default) or schema-4 recording
 * directory byte-by-byte from FORMAT.md, tailored for engine tests: it exposes
 * per-thread record indices (so a test can assert exact frame/step positions) and
 * writes snapshot payloads with the schema-2/3 {@code form} byte, LINE records
 * and — for schema 4 — STACK_SNAPSHOT captures over §3 STACK blobs. It
 * deliberately has no dependency on the writer — if the engine reconstructs from
 * this, it reconstructs from the spec.
 */
final class SyntheticRecording {

    private final Path dir;
    private final int schema;
    private final StringBuilder symbols = new StringBuilder();
    private final StringBuilder rtSymbols = new StringBuilder();
    private final StringBuilder sites = new StringBuilder();
    private final Map<Integer, ByteArrayOutputStream> records = new TreeMap<>();
    private final Map<Integer, ByteArrayOutputStream> blobs = new TreeMap<>();
    private int nextSym = 1;
    private int nextRtSym = 1;
    private int nextSite = 1;
    private long nextSeq = 1;

    SyntheticRecording(final Path dir) {
        this(dir, 3);
    }

    /** Builder for a specific manifest schema — pass 4 for recordings with stack captures. */
    SyntheticRecording(final Path dir, final int schema) {
        this.dir = dir;
        this.schema = schema;
    }

    // ---- symbols (§4) ----

    int sym(final String kind, final String text) {
        int id = nextSym++;
        symbols.append(id).append('\t').append(kind).append('\t').append(escape(text)).append('\n');
        return id;
    }

    int rtSym(final String kind, final String text) {
        int id = Format.RT_SYMBOL_BASE | nextRtSym++;
        rtSymbols.append(id).append('\t').append(kind).append('\t').append(escape(text)).append('\n');
        return id;
    }

    private static String escape(final String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    // ---- sites (§5) ----

    int methodSite(final int classSym, final int fileSym, final int line, final int methodSym,
            final String desc, final boolean isStatic) {
        return site("METHOD", classSym, fileSym, line, methodSym, desc + (isStatic ? "#s" : ""), -1);
    }

    int fieldSite(final int ownerClassSym, final int fileSym, final int line, final int fieldSym,
            final String desc, final boolean isStatic) {
        return site("FIELD", ownerClassSym, fileSym, line, fieldSym, desc + (isStatic ? "#s" : ""), -1);
    }

    int localSite(final int classSym, final int fileSym, final int line, final int varSym,
            final String desc, final int slot) {
        return site("LOCAL", classSym, fileSym, line, varSym, desc, slot);
    }

    int lineSite(final int classSym, final int fileSym, final int line) {
        return site("LINE", classSym, fileSym, line, 0, "", -1);
    }

    int branchSite(final int classSym, final int fileSym, final int line, final int opcode) {
        return site("BRANCH", classSym, fileSym, line, 0, "", opcode);
    }

    private int site(final String kind, final int classSym, final int fileSym, final int line,
            final int memberSym, final String desc, final int slot) {
        int id = nextSite++;
        sites.append(id).append('\t').append(kind).append('\t').append(classSym).append('\t')
                .append(fileSym).append('\t').append(line).append('\t').append(memberSym).append('\t')
                .append(desc).append('\t').append(slot).append('\n');
        return id;
    }

    // ---- records (§2) ----

    /** Appends a record with the next global seq; returns its per-thread record index. */
    int rec(final int tid, final int type, final int flags, final int tag, final int idx,
            final int location, final long target, final long before, final long after) {
        ByteBuffer b = ByteBuffer.allocate(Format.RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(nextSeq++);
        b.put((byte) type);
        b.put((byte) flags);
        b.putShort((short) (((tag & 0xFF) << 8) | (idx & 0xFF)));
        b.putInt(location);
        b.putLong(target);
        b.putLong(before);
        b.putLong(after);
        ByteArrayOutputStream out = records.computeIfAbsent(tid, t -> new ByteArrayOutputStream());
        int index = out.size() / Format.RECORD_SIZE;
        out.writeBytes(b.array());
        return index;
    }

    /** Combines the two 2-bit KIND fields with extra flag bits (§2.2). */
    static int flags(final int beforeKind, final int afterKind, final int extra) {
        return (beforeKind & 0b11) | ((afterKind & 0b11) << 2) | extra;
    }

    /** Standard thread prologue (§2 ordering): THREAD_START then TIME_SYNC. */
    void threadStart(final int tid, final String threadName, final long javaTid, final long millis) {
        int nameSym = rtSym("THREAD", threadName);
        rec(tid, Format.THREAD_START, 0, 0, 0, nameSym, 0, 0, javaTid);
        rec(tid, Format.TIME_SYNC, 0, 0, 0, 0, 0, 0, millis);
    }

    /** OBJECT_FIRST_SEEN for {@code objectId} of runtime class {@code binaryName}. */
    void firstSeen(final int tid, final long objectId, final String binaryName) {
        int classSym = rtSym("RTCLASS", binaryName);
        rec(tid, Format.OBJECT_FIRST_SEEN, 0, 0, 0, classSym, objectId, 0, 0);
    }

    // ---- blobs (§3) ----

    long blobUtf8(final int tid, final String s) {
        return blobRaw(tid, s.getBytes(StandardCharsets.UTF_8), Format.BLOB_UTF8);
    }

    private long blobRaw(final int tid, final byte[] payload, final int kind) {
        ByteArrayOutputStream blob = blobs.computeIfAbsent(tid, t -> new ByteArrayOutputStream());
        long offset = blob.size();
        blob.write(Format.BLOB_MAGIC);
        blob.write(kind);
        ByteBuffer len = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length);
        blob.writeBytes(len.array());
        blob.writeBytes(payload);
        return offset;
    }

    /** One field row for a FIELDS-form snapshot payload. */
    record Field(int nameSym, int tag, int kind, long value) {}

    /** One element (or key/value) entry for a container-form snapshot payload. */
    record Entry(int tag, int kind, long value) {}

    /** Writes a FIELDS snapshot blob (schema-3 layout) and returns its offset. */
    long snapshotFields(final int tid, final int classSym, final List<Field> fields) {
        ByteBuffer b = ByteBuffer.allocate(4 + 1 + 2 + fields.size() * 14).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(classSym);
        b.put((byte) Format.FORM_FIELDS);
        b.putShort((short) fields.size());
        for (Field f : fields) {
            b.putInt(f.nameSym());
            b.put((byte) f.tag());
            b.put((byte) f.kind());
            b.putLong(f.value());
        }
        return blobRaw(tid, b.array(), Format.BLOB_SNAPSHOT);
    }

    /**
     * One frame of a §3 STACK payload (schema 4), innermost-first. {@code classSym}/
     * {@code methodSym}/{@code fileSym} are rt-symbol ids (kinds RTCLASS/METHODNAME/FILENAME);
     * {@code fileSym} 0 = unknown source file; {@code line} -1/0 = unknown.
     */
    record CapFrame(int classSym, int methodSym, int fileSym, int line) {}

    /** Writes a §3 STACK blob (schema 4: 2-byte count, then 16-byte frames) and returns its offset. */
    long blobStack(final int tid, final List<CapFrame> frames) {
        ByteBuffer b = ByteBuffer.allocate(2 + frames.size() * Format.STACK_FRAME_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) frames.size());
        for (CapFrame f : frames) {
            b.putInt(f.classSym());
            b.putInt(f.methodSym());
            b.putInt(f.fileSym());
            b.putInt(f.line());
        }
        return blobRaw(tid, b.array(), Format.BLOB_STACK);
    }

    /**
     * Emits a §2.1 STACK_SNAPSHOT record (schema 4) whose {@code after} points at a fresh
     * STACK blob holding {@code frames} (innermost-first); returns the record index.
     */
    int stackSnapshot(final int tid, final List<CapFrame> frames) {
        long offset = blobStack(tid, frames);
        return rec(tid, Format.STACK_SNAPSHOT, flags(Format.KIND_NONE, Format.KIND_BLOB, 0),
                0, 0, 0, 0, 0, offset);
    }

    /** Writes a container snapshot blob (MAP/SET/LIST/ARRAY) and returns its offset. */
    long snapshotContainer(final int tid, final int classSym, final int form, final long totalSize,
            final int logicalCount, final List<Entry> entries) {
        ByteBuffer b = ByteBuffer.allocate(4 + 1 + 4 + 2 + entries.size() * 10).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(classSym);
        b.put((byte) form);
        b.putInt((int) totalSize);
        b.putShort((short) logicalCount);
        for (Entry e : entries) {
            b.put((byte) e.tag());
            b.put((byte) e.kind());
            b.putLong(e.value());
        }
        return blobRaw(tid, b.array(), Format.BLOB_SNAPSHOT);
    }

    // ---- output ----

    Path write() throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.properties"),
                "schema=" + schema + "\nrecorder=synthetic-test\njvm=test\npid=0\nstartEpochMillis=0\nargs=test\n");
        Files.writeString(dir.resolve("symbols.tsv"), symbols.toString());
        Files.writeString(dir.resolve("rt-symbols.tsv"), rtSymbols.toString());
        Files.writeString(dir.resolve("sites.tsv"), sites.toString());
        for (Map.Entry<Integer, ByteArrayOutputStream> e : records.entrySet()) {
            Files.write(dir.resolve(Format.recordsFileName(e.getKey())), e.getValue().toByteArray());
        }
        for (Map.Entry<Integer, ByteArrayOutputStream> e : blobs.entrySet()) {
            Files.write(dir.resolve(Format.blobFileName(e.getKey())), e.getValue().toByteArray());
        }
        return dir;
    }

    /** Convenience for building a small field list without repeating {@code List.of}. */
    static List<Field> fieldList(final Field... f) {
        return new ArrayList<>(List.of(f));
    }
}
