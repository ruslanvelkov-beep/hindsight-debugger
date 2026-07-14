// SPDX-License-Identifier: Apache-2.0
package io.hindsight.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-side parser for recording directories: reads {@code records-*.bin}, {@code blob-*.bin}
 * and {@code rt-symbols.tsv} strictly per FORMAT.md §2–§4 so the tests verify the bytes on
 * disk, not the writer's opinion of them.
 */
final class Recordings {

    private Recordings() {}

    /** One decoded 40-byte record (§2). */
    record Rec(long seq, int type, int flags, int idx, int tag, int location, long target, long before, long after) {

        int beforeKind() {
            return flags & Wire.BEFORE_KIND_MASK;
        }

        int afterKind() {
            return (flags & Wire.AFTER_KIND_MASK) >> Wire.AFTER_KIND_SHIFT;
        }
    }

    /** One decoded blob entry (§3). */
    record Blob(int kind, byte[] payload) {}

    static List<Path> recordFiles(Path dir) throws IOException {
        final List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "records-*.bin")) {
            for (final Path p : ds) {
                files.add(p);
            }
        }
        files.sort(null);
        return files;
    }

    static List<Rec> parse(Path recordsFile) throws IOException {
        final byte[] bytes = Files.readAllBytes(recordsFile);
        final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        final List<Rec> out = new ArrayList<>(bytes.length / Wire.RECORD_SIZE);
        while (buf.remaining() >= Wire.RECORD_SIZE) {
            final long seq = buf.getLong();
            final int type = buf.get() & 0xFF;
            final int flags = buf.get() & 0xFF;
            final int idx = buf.get() & 0xFF;
            final int tag = buf.get() & 0xFF;
            final int location = buf.getInt();
            final long target = buf.getLong();
            final long before = buf.getLong();
            final long after = buf.getLong();
            out.add(new Rec(seq, type, flags, idx, tag, location, target, before, after));
        }
        return out;
    }

    static List<Rec> parseAll(Path dir) throws IOException {
        final List<Rec> all = new ArrayList<>();
        for (final Path f : recordFiles(dir)) {
            all.addAll(parse(f));
        }
        return all;
    }

    static Blob blobAt(Path blobFile, long offset) throws IOException {
        final byte[] bytes = Files.readAllBytes(blobFile);
        final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(Math.toIntExact(offset));
        final int magic = buf.get() & 0xFF;
        if (magic != Wire.BLOB_MAGIC) {
            throw new AssertionError("blob offset " + offset + " does not start with 0xB5, got 0x"
                    + Integer.toHexString(magic));
        }
        final int kind = buf.get() & 0xFF;
        final int len = buf.getInt();
        final byte[] payload = new byte[len];
        buf.get(payload);
        return new Blob(kind, payload);
    }

    /** {@code rt-symbols.tsv} as id → [kind, unescaped text] (§4). */
    static Map<Integer, String[]> rtSymbols(Path dir) throws IOException {
        final Map<Integer, String[]> out = new HashMap<>();
        final Path file = dir.resolve("rt-symbols.tsv");
        if (!Files.exists(file)) {
            return out;
        }
        for (final String line : Files.readAllLines(file)) {
            if (line.isEmpty()) {
                continue;
            }
            final String[] parts = line.split("\t", 3);
            out.put(Integer.parseInt(parts[0]), new String[] {parts[1], unescape(parts[2])});
        }
        return out;
    }

    static String unescape(String s) {
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                final char n = s.charAt(++i);
                switch (n) {
                    case '\\' -> sb.append('\\');
                    case 't' -> sb.append('\t');
                    case 'n' -> sb.append('\n');
                    default -> sb.append(c).append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** A config pointing at {@code dir} with test-friendly (tiny-buffer) defaults. */
    static RuntimeConfig config(Path dir) {
        final RuntimeConfig cfg = new RuntimeConfig();
        cfg.outDir = dir;
        cfg.snapshotEvery = 0; // deterministic by default; snapshot tests opt in
        return cfg;
    }
}
