// SPDX-License-Identifier: Apache-2.0
package io.hindsight.reader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The derived visited-line index (FORMAT.md §12.1): for every source file present in a
 * recording, the set of lines the recording touched at least once — executed statement
 * lines, write lines, and the declaration line of every entered method.
 *
 * <p>Part of the recording's <em>calculated</em> info: built by ONE full scan of all
 * record files, either right after taking the recording (the {@code lines} CLI command)
 * or lazily the first time a consumer needs it ({@link #openOrBuild}), then persisted as
 * {@code lines.tsv} in the recording dir so both the CLI and the viewer read it back for
 * free. The file is a pure derivation — deleting it merely costs the next open a rescan;
 * a version- or record-count-mismatched (or corrupt) file is rebuilt and atomically
 * replaced, so a truncated or newer-schema recording never serves a stale index.
 *
 * <p>Keys are the class's package path + the site's file name, {@code /}-joined
 * ({@code com/foo/Bar.java}) — exactly the relative path source resolution probes under
 * each source root, so consumers match on-disk files without further mapping.
 */
public final class LineIndex {

    /** Index file name inside the recording dir (FORMAT.md §1). */
    public static final String FILE_NAME = "lines.tsv";

    private static final String HEADER_PREFIX = "# hindsight lines v1 records=";

    private final Map<String, BitSet> byFile;

    private LineIndex(final Map<String, BitSet> byFile) {
        this.byFile = byFile;
    }

    /**
     * Loads {@code lines.tsv} from {@code recording}'s dir, or — when absent, stale (its
     * header's record count no longer matches) or unparseable — scans all records, writes
     * the file atomically and returns the fresh index. Persisting is BEST-EFFORT: the index
     * is a pure derivation, so an unwritable recording dir (read-only media, foreign
     * ownership) merely costs the next open a rescan — it never blocks the consumer.
     *
     * @param recording the open recording
     * @return the visited-line index, never {@code null}
     * @throws IOException if the recording's record files cannot be read
     */
    public static LineIndex openOrBuild(final Recording recording) throws IOException {
        long total = totalRecords(recording);
        Path file = recording.dir().resolve(FILE_NAME);
        LineIndex loaded = tryLoad(file, total);
        if (loaded != null) {
            return loaded;
        }
        LineIndex built = build(recording);
        try {
            built.save(file, total);
        } catch (IOException e) {
            // Best-effort persist (§12.1): the in-memory index is complete and correct.
        }
        return built;
    }

    /** Whether a valid (non-stale) {@code lines.tsv} already exists for {@code recording}. */
    public static boolean present(final Recording recording) throws IOException {
        return tryLoad(recording.dir().resolve(FILE_NAME), totalRecords(recording)) != null;
    }

    /**
     * Builds the index by scanning every thread's records (no persistence — see
     * {@link #openOrBuild} for the read-through variant).
     *
     * @param recording the open recording
     * @return the freshly-computed index
     * @throws IOException if a record file cannot be read
     */
    public static LineIndex build(final Recording recording) throws IOException {
        Map<String, BitSet> byFile = new TreeMap<>();
        for (int tid : recording.threadIds()) {
            RecordCursor c = recording.cursor(tid);
            while (c.hasNext()) {
                Rec r = c.next();
                if (!siteBearing(r.type())) {
                    continue; // OBJECT_FIRST_SEEN/THREAD_START etc. carry symbol ids, not sites
                }
                Site s = recording.site(r.location());
                if (s == null || s.fileSym() == 0 || s.line() <= 0) {
                    continue;
                }
                String key = fileKey(recording.symbolText(s.classSym()), recording.symbolText(s.fileSym()));
                if (key != null) {
                    byFile.computeIfAbsent(key, k -> new BitSet()).set(s.line());
                }
            }
        }
        return new LineIndex(byFile);
    }

    /** §12.1: the record types whose {@code location} is a site id (never a symbol id). */
    private static boolean siteBearing(final int type) {
        return switch (type) {
            case Format.METHOD_ENTER, Format.METHOD_EXIT, Format.ARG, Format.LINE,
                    Format.BRANCH, Format.FIELD_WRITE, Format.LOCAL_WRITE -> true;
            default -> false;
        };
    }

    /**
     * The §12.1 file key: package path of {@code classBinary} + {@code /} + {@code fileName}
     * (bare file name in the default package), or {@code null} when either part is missing —
     * without the class there is no package, and a bare-name key could false-match a
     * default-package file of the same name.
     */
    public static String fileKey(final String classBinary, final String fileName) {
        if (fileName == null || fileName.isEmpty() || classBinary == null) {
            return null;
        }
        int lastDot = classBinary.lastIndexOf('.');
        return lastDot < 0 ? fileName : classBinary.substring(0, lastDot).replace('.', '/') + "/" + fileName;
    }

    /** The indexed file keys (sorted), each with at least one visited line. */
    public Set<String> files() {
        return byFile.keySet();
    }

    /**
     * The visited lines of {@code fileKey} (bit {@code n} = line {@code n} touched), or an
     * empty set for a file the recording never touched (or a {@code null} key — a frame with
     * no source file). The returned set is live — do not mutate it.
     */
    public BitSet linesOf(final String fileKey) {
        BitSet b = fileKey == null ? null : byFile.get(fileKey);
        return b != null ? b : new BitSet();
    }

    /** Whether the recording touched {@code fileKey} at all ({@code null}-safe). */
    public boolean covers(final String fileKey) {
        return fileKey != null && byFile.containsKey(fileKey);
    }

    /** Total visited-line count across all files. */
    public long lineCount() {
        return byFile.values().stream().mapToLong(BitSet::cardinality).sum();
    }

    private static long totalRecords(final Recording recording) throws IOException {
        long total = 0;
        for (int tid : recording.threadIds()) {
            total += recording.recordCount(tid);
        }
        return total;
    }

    /** Parses {@code file}; {@code null} when absent, header-mismatched or corrupt (→ rebuild). */
    private static LineIndex tryLoad(final Path file, final long expectedRecords) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        if (lines.isEmpty() || !lines.get(0).equals(HEADER_PREFIX + expectedRecords)) {
            return null;
        }
        Map<String, BitSet> byFile = new TreeMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String row = lines.get(i);
            if (row.isEmpty()) {
                continue;
            }
            int tab = row.indexOf('\t');
            if (tab <= 0) {
                return null;
            }
            BitSet set = parseRuns(row.substring(tab + 1));
            if (set == null) {
                return null;
            }
            byFile.put(row.substring(0, tab), set);
        }
        return new LineIndex(byFile);
    }

    private void save(final Path file, final long totalRecords) throws IOException {
        StringBuilder sb = new StringBuilder(HEADER_PREFIX).append(totalRecords).append('\n');
        for (Map.Entry<String, BitSet> e : byFile.entrySet()) {
            sb.append(e.getKey()).append('\t').append(formatRuns(e.getValue())).append('\n');
        }
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp." + ProcessHandle.current().pid());
        try {
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(tmp); // never leak a temp file into the recording dir
            } catch (IOException ignored) {
                // cleanup is best-effort; the original failure (if any) propagates
            }
        }
    }

    /** {@code {14,16,17,18,19,42}} → {@code "14,16-19,42"} (§12.1 runs). */
    static String formatRuns(final BitSet set) {
        StringBuilder sb = new StringBuilder();
        int i = set.nextSetBit(0);
        while (i >= 0) {
            int end = i;
            while (set.get(end + 1)) {
                end++;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(i);
            if (end > i) {
                sb.append('-').append(end);
            }
            i = set.nextSetBit(end + 1);
        }
        return sb.toString();
    }

    /** Source lines are u2 in the class file — nothing above this can be a real line number. */
    private static final int MAX_LINE = 0xFFFF;

    /** Inverse of {@link #formatRuns}; {@code null} on malformed text (→ rebuild). */
    static BitSet parseRuns(final String runs) {
        BitSet set = new BitSet();
        if (runs.isEmpty()) {
            return set;
        }
        try {
            for (String part : runs.split(",")) {
                int dash = part.indexOf('-');
                if (dash < 0) {
                    int line = Integer.parseInt(part);
                    if (line < 0 || line > MAX_LINE) {
                        return null; // corrupt text must trigger a rebuild, not a huge BitSet
                    }
                    set.set(line);
                } else {
                    int from = Integer.parseInt(part.substring(0, dash));
                    int to = Integer.parseInt(part.substring(dash + 1));
                    if (from < 0 || to < from || to > MAX_LINE) {
                        return null;
                    }
                    set.set(from, to + 1);
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return set;
    }

    /**
     * Per-file visited-line counts, sorted by key — the {@code lines} CLI's report rows.
     * Each entry: {@code key<TAB>count}.
     */
    public List<String> report() {
        List<String> out = new ArrayList<>(byFile.size());
        for (Map.Entry<String, BitSet> e : byFile.entrySet()) {
            out.add(e.getKey() + "\t" + e.getValue().cardinality());
        }
        return out;
    }

    /** Wraps {@link #openOrBuild} for callers that cannot throw (viewer draw paths). */
    public static LineIndex openOrBuildUnchecked(final Recording recording) {
        try {
            return openOrBuild(recording);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
