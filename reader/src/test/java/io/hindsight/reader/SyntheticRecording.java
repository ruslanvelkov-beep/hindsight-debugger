// SPDX-License-Identifier: Apache-2.0
package io.hindsight.reader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Test-only builder that assembles a recording directory byte-by-byte from
 * FORMAT.md — records (§2), blob entries (§3), TSVs (§4, §5), manifest (§13) —
 * with no dependency whatsoever on the writer implementation. These synthetic
 * recordings are the spec cross-check: if the reader accepts them and the real
 * writer's output equally, both sides implement the same document.
 */
final class SyntheticRecording {

    private final Path dir;
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
        this.dir = dir;
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

    private int site(final String kind, final int classSym, final int fileSym, final int line,
            final int memberSym, final String desc, final int slot) {
        int id = nextSite++;
        sites.append(id).append('\t').append(kind).append('\t').append(classSym).append('\t')
                .append(fileSym).append('\t').append(line).append('\t').append(memberSym).append('\t')
                .append(desc).append('\t').append(slot).append('\n');
        return id;
    }

    // ---- records (§2) ----

    /** Appends a record with the next global seq; returns the seq used. */
    long rec(final int tid, final int type, final int flags, final int tag, final int idx,
            final int location, final long target, final long before, final long after) {
        long seq = nextSeq++;
        recSeq(seq, tid, type, flags, tag, idx, location, target, before, after);
        return seq;
    }

    /** Appends a record with an explicit seq (for seq-regression scenarios). */
    void recSeq(final long seq, final int tid, final int type, final int flags, final int tag,
            final int idx, final int location, final long target, final long before, final long after) {
        ByteBuffer b = ByteBuffer.allocate(Format.RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(seq);
        b.put((byte) type);
        b.put((byte) flags);
        b.putShort((short) (((tag & 0xFF) << 8) | (idx & 0xFF)));
        b.putInt(location);
        b.putLong(target);
        b.putLong(before);
        b.putLong(after);
        records.computeIfAbsent(tid, t -> new ByteArrayOutputStream()).writeBytes(b.array());
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

    /** Appends raw junk bytes to a record file — a torn trailing record. */
    void appendRawRecordBytes(final int tid, final byte[] junk) {
        records.computeIfAbsent(tid, t -> new ByteArrayOutputStream()).writeBytes(junk);
    }

    // ---- blobs (§3) ----

    /** Writes a UTF8 blob entry; returns its offset in the thread's blob file. */
    long blobUtf8(final int tid, final String s) {
        return blobRaw(tid, Format.BLOB_MAGIC, Format.BLOB_UTF8, s.getBytes(StandardCharsets.UTF_8));
    }

    /** Writes a blob entry with an arbitrary magic byte (corruption scenarios). */
    long blobRaw(final int tid, final int magic, final int kind, final byte[] payload) {
        ByteArrayOutputStream blob = blobs.computeIfAbsent(tid, t -> new ByteArrayOutputStream());
        long offset = blob.size();
        blob.write(magic);
        blob.write(kind);
        ByteBuffer len = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length);
        blob.writeBytes(len.array());
        blob.writeBytes(payload);
        return offset;
    }

    /** Encodes a SNAPSHOT payload (§3): classSym, fieldCount, then 18-byte field rows. */
    static byte[] snapshotPayload(final int classSym, final int[][] fields) {
        // each row: {nameSym, tag, kind, valueLow32, valueHigh32}
        ByteBuffer b = ByteBuffer.allocate(6 + fields.length * 18).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(classSym);
        b.putShort((short) fields.length);
        for (int[] f : fields) {
            b.putInt(f[0]);
            b.put((byte) f[1]);
            b.put((byte) f[2]);
            b.putLong((f[3] & 0xFFFF_FFFFL) | ((long) f[4] << 32));
        }
        return b.array();
    }

    // ---- output ----

    /** Writes every file of the recording directory; returns {@code dir}. */
    Path write() throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.properties"),
                "schema=1\nrecorder=synthetic-test\njvm=test\npid=0\nstartEpochMillis=0\nargs=test\n");
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

    /** Creates the TRUNCATED marker with the given first-line reason (§1). */
    void markTruncated(final String reason) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("TRUNCATED"), reason + "\n");
    }
}
