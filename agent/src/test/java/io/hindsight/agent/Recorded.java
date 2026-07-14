// SPDX-License-Identifier: Apache-2.0
package io.hindsight.agent;

import io.hindsight.runtime.Wire;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-side parser of a recording directory, strictly per FORMAT.md: 40-byte little-endian
 * records (§2), §3 blob entries, and the two agent TSVs (§4–§5). Parsed independently of the
 * production writers so the tests verify the bytes on disk, not the writers' opinion of them.
 */
final class Recorded {

    /** One decoded §2 record, remembering which thread file it came from (blob resolution). */
    record Rec(int tid, long seq, int type, int flags, int idx, int tag, int location, long target, long before, long after) {

        int beforeKind() {
            return flags & Wire.BEFORE_KIND_MASK;
        }

        int afterKind() {
            return (flags & Wire.AFTER_KIND_MASK) >> Wire.AFTER_KIND_SHIFT;
        }

        boolean exceptional() {
            return (flags & Wire.FLAG_EXCEPTIONAL) != 0;
        }

        boolean staticFlag() {
            return (flags & Wire.FLAG_STATIC) != 0;
        }
    }

    /** One decoded §5 site row. */
    record Site(int id, String kind, int classSym, int fileSym, int line, int memberSym, String desc, int slot) {}

    /** One decoded §4 symbol row. */
    record Symbol(String kind, String text) {}

    final Path dir;
    final List<Rec> records; // all threads, merged, ordered by seq
    final Map<Integer, Site> sites;
    final Map<Integer, Symbol> symbols;

    private Recorded(Path dir, List<Rec> records, Map<Integer, Site> sites, Map<Integer, Symbol> symbols) {
        this.dir = dir;
        this.records = records;
        this.sites = sites;
        this.symbols = symbols;
    }

    static Recorded parse(Path dir) throws IOException {
        final List<Rec> records = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "records-*.bin")) {
            for (final Path file : ds) {
                final String name = file.getFileName().toString();
                final int tid = Integer.parseInt(name.substring("records-".length(), name.length() - ".bin".length()));
                final byte[] bytes = Files.readAllBytes(file);
                if (bytes.length % Wire.RECORD_SIZE != 0) {
                    throw new AssertionError(name + " is not whole 40-byte records: " + bytes.length);
                }
                final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                while (buf.remaining() >= Wire.RECORD_SIZE) {
                    records.add(new Rec(
                            tid,
                            buf.getLong(),
                            buf.get() & 0xFF,
                            buf.get() & 0xFF,
                            buf.get() & 0xFF,
                            buf.get() & 0xFF,
                            buf.getInt(),
                            buf.getLong(),
                            buf.getLong(),
                            buf.getLong()));
                }
            }
        }
        records.sort(Comparator.comparingLong(Rec::seq));
        return new Recorded(dir, records, parseSites(dir), parseSymbols(dir));
    }

    // =====================================================================================
    // Lookups
    // =====================================================================================

    List<Rec> ofType(int type) {
        final List<Rec> out = new ArrayList<>();
        for (final Rec r : records) {
            if (r.type() == type) {
                out.add(r);
            }
        }
        return out;
    }

    String symbolText(int id) {
        final Symbol s = symbols.get(id);
        if (s == null) {
            throw new AssertionError("unresolvable symbol id " + id);
        }
        return s.text();
    }

    /** The unique METHOD site of {@code class#method}; fails when absent or ambiguous. */
    int methodSiteId(String classBinaryName, String methodName) {
        final List<Integer> ids = methodSiteIds(classBinaryName, methodName);
        if (ids.size() != 1) {
            throw new AssertionError("expected exactly one METHOD site for " + classBinaryName + "#"
                    + methodName + ", found " + ids);
        }
        return ids.get(0);
    }

    List<Integer> methodSiteIds(String classBinaryName, String methodName) {
        final List<Integer> ids = new ArrayList<>();
        for (final Site s : sites.values()) {
            if ("METHOD".equals(s.kind())
                    && classBinaryName.equals(symbolText(s.classSym()))
                    && methodName.equals(symbolText(s.memberSym()))) {
                ids.add(s.id());
            }
        }
        return ids;
    }

    /** All LOCAL sites naming the given variable. */
    List<Site> localSites(String varName) {
        final List<Site> out = new ArrayList<>();
        for (final Site s : sites.values()) {
            if ("LOCAL".equals(s.kind()) && varName.equals(symbolText(s.memberSym()))) {
                out.add(s);
            }
        }
        return out;
    }

    /** All FIELD sites naming the given field. */
    List<Site> fieldSites(String fieldName) {
        final List<Site> out = new ArrayList<>();
        for (final Site s : sites.values()) {
            if ("FIELD".equals(s.kind()) && fieldName.equals(symbolText(s.memberSym()))) {
                out.add(s);
            }
        }
        return out;
    }

    /** Records at any of the given sites, in seq order. */
    List<Rec> at(List<Site> siteRows, int type) {
        final List<Rec> out = new ArrayList<>();
        for (final Rec r : records) {
            if (r.type() != type) {
                continue;
            }
            for (final Site s : siteRows) {
                if (s.id() == r.location()) {
                    out.add(r);
                    break;
                }
            }
        }
        return out;
    }

    /** Resolves a §3 UTF8 blob entry from the given thread's blob file. */
    String blobString(int tid, long offset) throws IOException {
        final byte[] bytes = Files.readAllBytes(dir.resolve(String.format("blob-%05d.bin", tid)));
        final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(Math.toIntExact(offset));
        final int magic = buf.get() & 0xFF;
        if (magic != Wire.BLOB_MAGIC) {
            throw new AssertionError("blob offset " + offset + " does not start with 0xB5: 0x"
                    + Integer.toHexString(magic));
        }
        final int kind = buf.get() & 0xFF;
        if (kind != Wire.BLOB_KIND_UTF8) {
            throw new AssertionError("expected UTF8 blob entry, got kind " + kind);
        }
        final byte[] payload = new byte[buf.getInt()];
        buf.get(payload);
        return new String(payload, StandardCharsets.UTF_8);
    }

    // =====================================================================================
    // TSV parsing (§4 escaping)
    // =====================================================================================

    private static Map<Integer, Site> parseSites(Path dir) throws IOException {
        final Map<Integer, Site> out = new HashMap<>();
        final Path file = dir.resolve("sites.tsv");
        if (!Files.exists(file)) {
            return out;
        }
        for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            final String[] f = line.split("\t", -1);
            if (f.length != 8) {
                throw new AssertionError("bad sites.tsv row (" + f.length + " fields): " + line);
            }
            final Site site = new Site(
                    Integer.parseInt(f[0]),
                    f[1],
                    Integer.parseInt(f[2]),
                    Integer.parseInt(f[3]),
                    Integer.parseInt(f[4]),
                    Integer.parseInt(f[5]),
                    unescape(f[6]),
                    Integer.parseInt(f[7]));
            out.put(site.id(), site);
        }
        return out;
    }

    private static Map<Integer, Symbol> parseSymbols(Path dir) throws IOException {
        final Map<Integer, Symbol> out = new HashMap<>();
        final Path file = dir.resolve("symbols.tsv");
        if (!Files.exists(file)) {
            return out;
        }
        for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            final String[] f = line.split("\t", 3);
            if (f.length != 3) {
                throw new AssertionError("bad symbols.tsv row: " + line);
            }
            out.put(Integer.parseInt(f[0]), new Symbol(f[1], unescape(f[2])));
        }
        return out;
    }

    static String unescape(String s) {
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                final char next = s.charAt(++i);
                switch (next) {
                    case '\\' -> sb.append('\\');
                    case 't' -> sb.append('\t');
                    case 'n' -> sb.append('\n');
                    default -> sb.append(c).append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
