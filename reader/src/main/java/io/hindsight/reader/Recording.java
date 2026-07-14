// SPDX-License-Identifier: Apache-2.0
package io.hindsight.reader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An opened Hindsight recording directory (FORMAT.md §1): the manifest, the
 * unified symbol map ({@code symbols.tsv} + {@code rt-symbols.tsv} — one id
 * space per §4), the site table (§5), and lazily-mapped per-thread record and
 * blob files (§2, §3).
 *
 * <p>Robustness posture: a recording from a crashed or size-capped run must
 * still open. Concretely — a trailing partial record (file length not a
 * multiple of 40) is silently excluded from the record count but reported via
 * {@link #hasPartialTail(int)}; a torn trailing TSV line is skipped; the
 * {@code TRUNCATED} marker is surfaced via {@link #isTruncated()}. Only a
 * schema-version mismatch is a hard refusal (§13), because misreading a future
 * format would produce confidently wrong answers.
 *
 * <p>Record files are memory-mapped on first access ({@code MemorySegment}, so
 * multi-GB files work) and unmapped when the recording is closed.
 */
public final class Recording implements AutoCloseable {

    private static final ValueLayout.OfLong LONG_LE =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt INT_LE =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort SHORT_LE =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    private static final Pattern RECORDS_NAME = Pattern.compile("records-(\\d{5})\\.bin");

    /** One entry of the unified symbol map (§4). */
    public record Sym(String kind, String text) {}

    /** One decoded blob entry (§3). */
    public record BlobEntry(int kind, byte[] payload) {}

    /** One field of a decoded SNAPSHOT payload (§3). */
    public record SnapField(int nameSym, int tag, int kind, long value) {}

    /** A decoded SNAPSHOT payload (§3): runtime class + 1-level field values. */
    public record Snapshot(int classSym, List<SnapField> fields) {}

    private final Path dir;
    private final Properties manifest = new Properties();
    private final Map<Integer, Sym> symbols = new HashMap<>();
    private final Map<Integer, Site> sites = new HashMap<>();
    private final TreeMap<Integer, ThreadFiles> threads = new TreeMap<>();
    private final Arena arena = Arena.ofShared();
    private final boolean truncatedMarker;
    private final String truncationReason;
    private Map<Long, Integer> objectClassByIdCache;

    /** Per-thread file pair, mapped lazily so huge recordings open instantly. */
    private final class ThreadFiles {
        final int tid;
        final Path recordsPath;
        final Path blobPath;
        MemorySegment recordsSeg;
        MemorySegment blobSeg;
        long recordCount = -1;
        boolean partialTail;

        ThreadFiles(final int tid) {
            this.tid = tid;
            this.recordsPath = dir.resolve(Format.recordsFileName(tid));
            this.blobPath = dir.resolve(Format.blobFileName(tid));
        }

        synchronized MemorySegment records() throws IOException {
            if (recordsSeg == null) {
                recordsSeg = map(recordsPath);
                long size = recordsSeg.byteSize();
                recordCount = size / Format.RECORD_SIZE;
                partialTail = size % Format.RECORD_SIZE != 0;
            }
            return recordsSeg;
        }

        synchronized MemorySegment blob() throws IOException {
            if (blobSeg == null) {
                blobSeg = Files.exists(blobPath) ? map(blobPath) : MemorySegment.ofArray(new byte[0]);
            }
            return blobSeg;
        }

        private MemorySegment map(final Path p) throws IOException {
            long size = Files.size(p);
            if (size == 0) {
                return MemorySegment.ofArray(new byte[0]);
            }
            try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
                return ch.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
            }
        }
    }

    private Recording(final Path dir) throws IOException {
        this.dir = dir;
        if (!Files.isDirectory(dir)) {
            throw new IOException("not a recording directory: " + dir);
        }
        loadManifest(dir.resolve("manifest.properties"));
        loadSymbols(dir.resolve("symbols.tsv"));
        loadSymbols(dir.resolve("rt-symbols.tsv"));
        loadSites(dir.resolve("sites.tsv"));
        discoverThreads();
        Path marker = dir.resolve("TRUNCATED");
        if (Files.exists(marker)) {
            truncatedMarker = true;
            String reason = "";
            try {
                List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
                reason = lines.isEmpty() ? "" : lines.get(0);
            } catch (IOException e) {
                reason = "(unreadable marker: " + e.getMessage() + ")";
            }
            truncationReason = reason;
        } else {
            truncatedMarker = false;
            truncationReason = null;
        }
    }

    /**
     * Opens a recording directory, refusing only an unknown schema version.
     *
     * @throws IOException if the directory is unusable or declares a schema other than 1
     */
    public static Recording open(final Path dir) throws IOException {
        Recording r = new Recording(dir);
        String schema = r.manifest.getProperty("schema");
        if (schema != null && !"1".equals(schema.trim())) {
            r.close();
            throw new IOException("unsupported schema version '" + schema + "' (reader supports 1)");
        }
        return r;
    }

    private void loadManifest(final Path p) throws IOException {
        if (Files.exists(p)) {
            try (var in = Files.newInputStream(p)) {
                manifest.load(in);
            }
        }
    }

    private void loadSymbols(final Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            // real tabs never occur inside text (they are escaped), so a raw split is safe
            String[] f = line.split("\t", 3);
            if (f.length != 3) {
                continue; // torn trailing line from a crashed writer — tolerated (§4)
            }
            try {
                symbols.put(Integer.parseInt(f[0]), new Sym(f[1], unescape(f[2])));
            } catch (NumberFormatException e) {
                // torn line — skip
            }
        }
    }

    private void loadSites(final Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\t", 8);
            if (f.length != 8) {
                continue;
            }
            try {
                String desc = f[6];
                boolean isStatic = desc.endsWith("#s");
                if (isStatic) {
                    desc = desc.substring(0, desc.length() - 2);
                }
                sites.put(
                        Integer.parseInt(f[0]),
                        new Site(
                                Integer.parseInt(f[0]),
                                f[1],
                                Integer.parseInt(f[2]),
                                Integer.parseInt(f[3]),
                                Integer.parseInt(f[4]),
                                Integer.parseInt(f[5]),
                                desc,
                                Integer.parseInt(f[7]),
                                isStatic));
            } catch (NumberFormatException e) {
                // torn line — skip
            }
        }
    }

    private void discoverThreads() throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "records-*.bin")) {
            for (Path p : ds) {
                Matcher m = RECORDS_NAME.matcher(p.getFileName().toString());
                if (m.matches()) {
                    int tid = Integer.parseInt(m.group(1));
                    threads.put(tid, new ThreadFiles(tid));
                }
            }
        }
    }

    private static String unescape(final String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case '\\' -> b.append('\\');
                    case 't' -> b.append('\t');
                    case 'n' -> b.append('\n');
                    default -> b.append(c).append(n); // unknown escape — keep verbatim
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    // ---- accessors ----

    /** The recording directory this instance reads from. */
    public Path dir() {
        return dir;
    }

    /** Parsed {@code manifest.properties}; empty if the file was missing. */
    public Properties manifest() {
        return manifest;
    }

    /** Dense thread ids present in the directory, ascending. */
    public List<Integer> threadIds() {
        return List.copyOf(threads.keySet());
    }

    /** Unified symbol lookup (§4), or {@code null} for an unknown id. */
    public Sym symbol(final int id) {
        return symbols.get(id);
    }

    /** Symbol text, or {@code null} for an unknown id. */
    public String symbolText(final int id) {
        Sym s = symbols.get(id);
        return s == null ? null : s.text();
    }

    /** Site lookup (§5), or {@code null} for an unknown id. */
    public Site site(final int id) {
        return sites.get(id);
    }

    /** Whether the runtime left a {@code TRUNCATED} marker (§1). */
    public boolean isTruncated() {
        return truncatedMarker;
    }

    /** First line of the {@code TRUNCATED} marker, or {@code null} when absent. */
    public String truncationReason() {
        return truncationReason;
    }

    /** Number of complete records in thread {@code tid}'s file (partial tail excluded). */
    public long recordCount(final int tid) throws IOException {
        ThreadFiles t = thread(tid);
        t.records();
        return t.recordCount;
    }

    /** Whether the thread's record file ends in a partial (torn) record. */
    public boolean hasPartialTail(final int tid) throws IOException {
        ThreadFiles t = thread(tid);
        t.records();
        return t.partialTail;
    }

    /** Size in bytes of the thread's blob file (0 if absent). */
    public long blobSize(final int tid) throws IOException {
        return thread(tid).blob().byteSize();
    }

    private ThreadFiles thread(final int tid) {
        ThreadFiles t = threads.get(tid);
        if (t == null) {
            throw new IllegalArgumentException("no such thread in recording: " + tid);
        }
        return t;
    }

    /** Decodes record {@code index} of thread {@code tid} (§2). */
    public Rec recordAt(final int tid, final long index) throws IOException {
        ThreadFiles t = thread(tid);
        MemorySegment seg = t.records();
        if (index < 0 || index >= t.recordCount) {
            throw new IndexOutOfBoundsException("record " + index + " of thread " + tid);
        }
        long off = index * Format.RECORD_SIZE;
        long seq = seg.get(LONG_LE, off);
        int type = seg.get(BYTE, off + 8) & 0xFF;
        int flags = seg.get(BYTE, off + 9) & 0xFF;
        int aux = seg.get(SHORT_LE, off + 10) & 0xFFFF;
        int location = seg.get(INT_LE, off + 12);
        long target = seg.get(LONG_LE, off + 16);
        long before = seg.get(LONG_LE, off + 24);
        long after = seg.get(LONG_LE, off + 32);
        return new Rec(tid, index, seq, type, flags, (aux >>> 8) & 0xFF, aux & 0xFF, location, target, before, after);
    }

    /** Cursor over one thread's records in file order. */
    public RecordCursor cursor(final int tid) {
        thread(tid); // validate eagerly
        return RecordCursor.single(this, tid);
    }

    /** Merged cursor over all threads, globally ordered by {@code seq}. */
    public RecordCursor cursor() {
        return RecordCursor.merged(this, threadIds());
    }

    // ---- blobs ----

    /**
     * Reads and validates the blob entry at {@code offset} in thread {@code tid}'s
     * blob file. Throws {@link IOException} with a precise message on any framing
     * violation (bad magic, unknown kind, length past end of file) — verification
     * reports that message verbatim.
     */
    public BlobEntry blobAt(final int tid, final long offset) throws IOException {
        MemorySegment seg = thread(tid).blob();
        long size = seg.byteSize();
        if (offset < 0 || offset + Format.BLOB_HEADER_SIZE > size) {
            throw new IOException("blob offset " + offset + " outside " + Format.blobFileName(tid)
                    + " (size " + size + ")");
        }
        int magic = seg.get(BYTE, offset) & 0xFF;
        if (magic != Format.BLOB_MAGIC) {
            throw new IOException("bad blob magic 0x" + Integer.toHexString(magic) + " at offset "
                    + offset + " in " + Format.blobFileName(tid));
        }
        int kind = seg.get(BYTE, offset + 1) & 0xFF;
        if (kind != Format.BLOB_UTF8 && kind != Format.BLOB_SNAPSHOT) {
            throw new IOException("unknown blob kind " + kind + " at offset " + offset + " in "
                    + Format.blobFileName(tid));
        }
        int len = seg.get(INT_LE, offset + 2);
        if (len < 0 || offset + Format.BLOB_HEADER_SIZE + len > size) {
            throw new IOException("blob length " + len + " at offset " + offset + " exceeds "
                    + Format.blobFileName(tid) + " (size " + size + ")");
        }
        byte[] payload = new byte[len];
        MemorySegment.copy(seg, Format.BLOB_HEADER_SIZE + offset, MemorySegment.ofArray(payload), 0, len);
        return new BlobEntry(kind, payload);
    }

    /** Reads a UTF8 blob entry as a string; the entry must have kind UTF8. */
    public String utf8At(final int tid, final long offset) throws IOException {
        BlobEntry e = blobAt(tid, offset);
        if (e.kind() != Format.BLOB_UTF8) {
            throw new IOException("expected UTF8 blob at offset " + offset + " in "
                    + Format.blobFileName(tid) + " but found kind " + e.kind());
        }
        return new String(e.payload(), StandardCharsets.UTF_8);
    }

    /** Reads and decodes a SNAPSHOT blob entry (§3). */
    public Snapshot snapshotAt(final int tid, final long offset) throws IOException {
        BlobEntry e = blobAt(tid, offset);
        if (e.kind() != Format.BLOB_SNAPSHOT) {
            throw new IOException("expected SNAPSHOT blob at offset " + offset + " in "
                    + Format.blobFileName(tid) + " but found kind " + e.kind());
        }
        return parseSnapshot(e.payload());
    }

    /** Decodes a raw SNAPSHOT payload (§3): classSym, fieldCount, then per-field rows. */
    public static Snapshot parseSnapshot(final byte[] payload) throws IOException {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        try {
            int classSym = b.getInt();
            int fieldCount = b.getShort() & 0xFFFF;
            if (fieldCount > 64) {
                throw new IOException("snapshot fieldCount " + fieldCount + " exceeds spec max 64");
            }
            List<SnapField> fields = new ArrayList<>(fieldCount);
            for (int i = 0; i < fieldCount; i++) {
                fields.add(new SnapField(b.getInt(), b.get() & 0xFF, b.get() & 0xFF, b.getLong()));
            }
            return new Snapshot(classSym, List.copyOf(fields));
        } catch (java.nio.BufferUnderflowException e) {
            throw new IOException("snapshot payload truncated (" + payload.length + " bytes)");
        }
    }

    // ---- object identity ----

    /**
     * Runtime class binary name of an object id, resolved from that object's
     * {@code OBJECT_FIRST_SEEN} record, or {@code null} when unknown. Built by a
     * one-time scan of all record files on first use (dump/stats scan everything
     * anyway, so this costs at most one extra pass).
     */
    public String objectClassName(final long objectId) {
        Map<Long, Integer> m = objectClassMap();
        Integer sym = m.get(objectId);
        return sym == null ? null : symbolText(sym);
    }

    private synchronized Map<Long, Integer> objectClassMap() {
        if (objectClassByIdCache == null) {
            Map<Long, Integer> m = new HashMap<>();
            try {
                for (int tid : threadIds()) {
                    long n = recordCount(tid);
                    for (long i = 0; i < n; i++) {
                        Rec r = recordAt(tid, i);
                        if (r.type() == Format.OBJECT_FIRST_SEEN) {
                            m.putIfAbsent(r.target(), r.location());
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            objectClassByIdCache = m;
        }
        return objectClassByIdCache;
    }

    @Override
    public void close() {
        arena.close();
    }
}
