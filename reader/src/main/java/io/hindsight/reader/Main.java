// SPDX-License-Identifier: Apache-2.0
package io.hindsight.reader;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * CLI entry point (FORMAT.md §12): {@code dump}, {@code stats}, {@code verify},
 * {@code find}. Exit codes: 0 = success, 1 = verify found violations, 2 = usage
 * error (also used for an unreadable/unsupported recording — the invocation, not
 * the data contents, was unusable).
 *
 * <p>All work happens in {@link #run}, which never calls {@code System.exit}
 * and writes to injected streams — tests drive the real CLI surface without
 * forking a JVM.
 */
public final class Main {

    private Main() {}

    /** Process entry point; delegates to {@link #run} and exits with its code. */
    public static void main(final String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs one CLI invocation.
     *
     * @return 0 on success, 1 on verify violations, 2 on usage or I/O error
     */
    public static int run(final String[] args, final PrintStream out, final PrintStream err) {
        if (args.length == 0) {
            return usage(err, null);
        }
        String cmd = args[0];
        final Map<String, String> opts;
        try {
            opts = parseOpts(args, switch (cmd) {
                case "dump" -> List.of("--dir", "--thread", "--limit", "--from-seq", "--method", "--depth-max");
                case "find" -> List.of("--dir", "--object", "--limit");
                case "stats", "verify" -> List.of("--dir");
                default -> null;
            });
        } catch (IllegalArgumentException e) {
            return usage(err, e.getMessage());
        }
        if (opts == null) {
            return usage(err, "unknown subcommand: " + cmd);
        }
        String dirOpt = opts.get("--dir");
        if (dirOpt == null) {
            return usage(err, cmd + " requires --dir");
        }
        try (Recording rec = Recording.open(Path.of(dirOpt))) {
            return switch (cmd) {
                case "dump" -> dump(rec, opts, out, err);
                case "find" -> find(rec, opts, out, err);
                case "stats" -> stats(rec, out);
                case "verify" -> verify(rec, out);
                default -> usage(err, null); // unreachable
            };
        } catch (IOException | UncheckedIOException e) {
            err.println("error: " + e.getMessage());
            return 2;
        } catch (IllegalArgumentException e) {
            return usage(err, e.getMessage());
        }
    }

    private static Map<String, String> parseOpts(final String[] args, final List<String> allowed) {
        if (allowed == null) {
            return null;
        }
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            String key = args[i];
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("unknown option: " + key);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + key);
            }
            opts.put(key, args[i + 1]);
        }
        return opts;
    }

    private static long longOpt(final Map<String, String> opts, final String key, final long dflt) {
        String v = opts.get(key);
        if (v == null) {
            return dflt;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad numeric value for " + key + ": " + v);
        }
    }

    private static int usage(final PrintStream err, final String problem) {
        if (problem != null) {
            err.println("error: " + problem);
        }
        err.println("""
                usage: hindsight-reader <subcommand> [options]
                  dump   --dir D [--thread N] [--limit N] [--from-seq S] [--method SUBSTR] [--depth-max N]
                  find   --dir D --object ID [--limit N]   (ID: bare number or Class#id)
                  stats  --dir D
                  verify --dir D
                exit codes: 0 ok, 1 verify violations, 2 usage""");
        return 2;
    }

    private static void surfaceTruncation(final Recording rec, final PrintStream out) {
        if (rec.isTruncated()) {
            String reason = rec.truncationReason();
            out.println("# recording TRUNCATED" + (reason == null || reason.isEmpty() ? "" : ": " + reason));
        }
    }

    // ---- dump ----

    private static int dump(final Recording rec, final Map<String, String> opts,
            final PrintStream out, final PrintStream err) throws IOException {
        long limit = longOpt(opts, "--limit", Long.MAX_VALUE);
        long fromSeq = longOpt(opts, "--from-seq", Long.MIN_VALUE);
        long depthMax = longOpt(opts, "--depth-max", Long.MAX_VALUE);
        String method = opts.get("--method");
        String threadOpt = opts.get("--thread");
        Integer thread = null;
        if (threadOpt != null) {
            thread = (int) longOpt(opts, "--thread", 0);
            if (!rec.threadIds().contains(thread)) {
                err.println("error: no such thread in recording: " + thread);
                return 2;
            }
        }
        surfaceTruncation(rec, out);
        ValueRenderer vr = new ValueRenderer(rec);
        RecordCursor cur = thread == null ? rec.cursor() : rec.cursor(thread);
        Map<Integer, Integer> depthByTid = new HashMap<>();
        long printed = 0;
        while (cur.hasNext() && printed < limit) {
            Rec r = cur.next();
            int depth = depthByTid.getOrDefault(r.tid(), 0);
            int printDepth;
            switch (r.type()) {
                case Format.METHOD_ENTER -> {
                    printDepth = depth;
                    depthByTid.put(r.tid(), depth + 1);
                }
                case Format.METHOD_EXIT -> {
                    printDepth = Math.max(0, depth - 1);
                    depthByTid.put(r.tid(), printDepth);
                }
                default -> printDepth = depth;
            }
            if (r.seq() < fromSeq || printDepth > depthMax || !methodFilterMatches(rec, r, method)) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            line.append("[seq ").append(r.seq());
            if (thread == null) {
                line.append(" t").append(r.tid());
            }
            line.append("] ");
            line.append("  ".repeat(printDepth));
            line.append(describe(rec, vr, r));
            out.println(line);
            if (r.type() == Format.STACK_SNAPSHOT) {
                printStackFrames(rec, r, printDepth, out);
            }
            printed++;
        }
        return 0;
    }

    /**
     * §12 schema 4: the {@code stack-snapshot (<n> frames)} header line is followed by one
     * {@code at Class.method (File:line)} line per frame, innermost first, indented one step
     * past the header's call-tree depth. A damaged payload prints nothing extra — the header
     * (from {@link #describe}) already carries the diagnostic.
     */
    private static void printStackFrames(final Recording rec, final Rec r, final int printDepth,
            final PrintStream out) {
        final List<Recording.CapturedFrame> frames;
        try {
            frames = rec.stackAt(r.tid(), r.after());
        } catch (IOException e) {
            return;
        }
        String indent = "    " + "  ".repeat(printDepth + 1);
        for (Recording.CapturedFrame f : frames) {
            out.println(indent + "at " + renderFrame(f));
        }
    }

    /** {@code Class.method (File:line)} — {@code (Unknown Source)} when the frame has no file. */
    private static String renderFrame(final Recording.CapturedFrame f) {
        String cls = f.classBinary() == null ? "?" : f.classBinary();
        String method = f.methodName() == null ? "?" : f.methodName();
        String where = f.fileName() == null
                ? "Unknown Source"
                : (f.line() > 0 ? f.fileName() + ":" + f.line() : f.fileName());
        return cls + "." + method + " (" + where + ")";
    }

    private static boolean methodFilterMatches(final Recording rec, final Rec r, final String method) {
        if (method == null) {
            return true;
        }
        if (r.type() != Format.METHOD_ENTER && r.type() != Format.METHOD_EXIT && r.type() != Format.ARG) {
            return false;
        }
        Site s = rec.site(r.location());
        return s != null && qualifiedMethod(rec, s).contains(method);
    }

    private static String qualifiedMethod(final Recording rec, final Site s) {
        String cls = rec.symbolText(s.classSym());
        String m = rec.symbolText(s.memberSym());
        return (cls == null ? "?" : Format.simpleName(cls)) + "." + (m == null ? "?" : m);
    }

    private static String sourceRef(final Recording rec, final Site s) {
        String file = s.fileSym() == 0 ? null : rec.symbolText(s.fileSym());
        return " (" + (file == null ? "?" : file) + ":" + s.line() + ")";
    }

    /** One display line per record type; format anchored to the §12 example. */
    private static String describe(final Recording rec, final ValueRenderer vr, final Rec r) {
        Site s = rec.site(r.location());
        return switch (r.type()) {
            case Format.METHOD_ENTER -> {
                StringBuilder b = new StringBuilder();
                b.append(s == null ? "?site" + r.location() : qualifiedMethod(rec, s));
                b.append('(').append(r.idx()).append(r.idx() == 1 ? " arg)" : " args)");
                if (r.target() != 0) {
                    b.append(" this=").append(vr.renderObjectId(r.target()));
                }
                if (s != null) {
                    b.append(sourceRef(rec, s));
                }
                yield b.toString();
            }
            case Format.ARG -> "arg" + r.idx() + " = " + vr.render(r, false);
            case Format.LINE -> {
                int ln = s == null ? 0 : s.line();
                String file = (s == null || s.fileSym() == 0) ? null : rec.symbolText(s.fileSym());
                yield "line " + ln + (file == null ? "" : " (" + file + ":" + ln + ")");
            }
            case Format.BRANCH -> describeBranch(rec, vr, r, s);
            case Format.METHOD_EXIT -> {
                StringBuilder b = new StringBuilder();
                if (r.isExceptional()) {
                    b.append("throw ").append(vr.renderObjectId(r.after()));
                } else {
                    b.append("return ").append(vr.render(r, false));
                }
                if (r.target() != 0) {
                    b.append(" this=").append(vr.renderObjectId(r.target()));
                }
                b.append(" [").append(s == null ? "?site" + r.location() : qualifiedMethod(rec, s)).append(']');
                yield b.toString();
            }
            case Format.FIELD_WRITE -> {
                String owner = r.target() != 0
                        ? vr.renderObjectId(r.target())
                        : (s == null ? "?" : Format.simpleName(orUnknown(rec.symbolText(s.classSym()))));
                String field = s == null ? "?site" + r.location() : orUnknown(rec.symbolText(s.memberSym()));
                yield owner + "." + field + ": " + vr.render(r, true) + " -> " + vr.render(r, false)
                        + (s == null ? "" : sourceRef(rec, s));
            }
            case Format.LOCAL_WRITE -> {
                String var = s == null ? "?site" + r.location() : orUnknown(rec.symbolText(s.memberSym()));
                yield "local " + var + " = " + vr.render(r, false) + (s == null ? "" : sourceRef(rec, s));
            }
            case Format.OBJECT_FIRST_SEEN -> "first-seen " + vr.renderObjectId(r.target());
            case Format.SNAPSHOT -> "snapshot " + vr.renderSnapshot(r);
            case Format.THREAD_START ->
                    "thread start \"" + orUnknown(rec.symbolText(r.location())) + "\" javaTid=" + r.after();
            case Format.TIME_SYNC -> "time-sync " + r.after();
            case Format.STACK_SNAPSHOT -> {
                try {
                    yield "stack-snapshot (" + rec.stackAt(r.tid(), r.after()).size() + " frames)";
                } catch (IOException e) {
                    yield "stack-snapshot <bad payload: " + e.getMessage() + ">";
                }
            }
            default -> Format.typeName(r.type());
        };
    }

    private static String orUnknown(final String s) {
        return s == null ? "?" : s;
    }

    /**
     * Renders a BRANCH record as {@code condition: <operands> <op> -> <result>}: the operand
     * values (int compares) or the derived boolean (ref compares), plus the taken result the
     * reader computes from the site's stored opcode. The following LINE records show which
     * body actually ran.
     */
    private static String describeBranch(final Recording rec, final ValueRenderer vr, final Rec r, final Site s) {
        final int op = s == null ? -1 : s.slot();
        final StringBuilder b = new StringBuilder("condition: ");
        final boolean result;
        if (Format.isIntCmp(op)) {
            final long a = r.before();
            final long bb = r.after();
            result = Format.intCmpResult(op, a, bb);
            b.append(a).append(' ').append(Format.branchOp(op)).append(' ').append(bb);
        } else if (Format.isIntZeroCmp(op)) {
            final long v = r.after();
            result = Format.intZeroCmpResult(op, v);
            b.append(v).append(' ').append(Format.branchOp(op));
        } else {
            result = Format.refCmpResult(op, r.after() != 0);
            b.append(Format.branchOp(op));
        }
        b.append(" -> ").append(result);
        if (s != null && s.fileSym() != 0) {
            String file = rec.symbolText(s.fileSym());
            b.append(" (").append(file == null ? "?" : file).append(':').append(s.line()).append(')');
        }
        return b.toString();
    }

    // ---- find ----

    /**
     * Locates everything about one object id in a single raw pass: its
     * {@code OBJECT_FIRST_SEEN}, any {@code SNAPSHOT}s (rendered — this is the
     * direct answer to "does a snapshot exist and what's in it"), and a count +
     * capped sample of the records that reference the id (as {@code this}/owner,
     * or as a before/after value slot). Only the object's own snapshots and the
     * sample lines are rendered, so the scan stays a field-comparison sweep —
     * sub-second even on multi-million-record recordings.
     *
     * <p>Record-level references only: an id that appears solely as a nested
     * value inside another object's snapshot payload (a blob) is not counted,
     * since that would require decoding every snapshot.
     */
    private static int find(final Recording rec, final Map<String, String> opts,
            final PrintStream out, final PrintStream err) throws IOException {
        String objOpt = opts.get("--object");
        if (objOpt == null) {
            return usage(err, "find requires --object ID");
        }
        long objectId;
        try {
            objectId = parseObjectId(objOpt);
        } catch (NumberFormatException e) {
            return usage(err, "bad --object ID: '" + objOpt + "' (want a number or Class#id)");
        }
        if (objectId <= 0) {
            return usage(err, "--object ID must be a positive object id, got " + objectId);
        }
        long showLimit = longOpt(opts, "--limit", 20);
        surfaceTruncation(rec, out);
        ValueRenderer vr = new ValueRenderer(rec);

        Rec firstSeen = null;
        List<Rec> snapshots = new ArrayList<>();
        long asTarget = 0;
        long asBefore = 0;
        long asAfter = 0;
        long firstRefSeq = Long.MAX_VALUE;
        long lastRefSeq = Long.MIN_VALUE;
        List<Rec> sample = new ArrayList<>();

        RecordCursor cur = rec.cursor();
        while (cur.hasNext()) {
            Rec r = cur.next();
            if (r.type() == Format.OBJECT_FIRST_SEEN && r.target() == objectId) {
                if (firstSeen == null) {
                    firstSeen = r;
                }
                continue;
            }
            if (r.type() == Format.SNAPSHOT && r.target() == objectId) {
                snapshots.add(r);
                continue;
            }
            boolean t = r.target() == objectId;
            boolean b = r.beforeKind() == Format.KIND_OBJECT_ID && r.before() == objectId;
            boolean a = r.afterKind() == Format.KIND_OBJECT_ID && r.after() == objectId;
            if (!t && !b && !a) {
                continue;
            }
            if (t) {
                asTarget++;
            }
            if (b) {
                asBefore++;
            }
            if (a) {
                asAfter++;
            }
            firstRefSeq = Math.min(firstRefSeq, r.seq());
            lastRefSeq = Math.max(lastRefSeq, r.seq());
            if (sample.size() < showLimit) {
                sample.add(r);
            }
        }

        // Derive the class/enum-name from the first-seen record we already have, so a pure
        // existence check needn't trigger the objectClassName cache's separate full scan.
        String cls = null;
        String display = null;
        if (firstSeen != null) {
            cls = rec.symbolText(firstSeen.location());
            if (firstSeen.afterKind() == Format.KIND_BLOB) {
                try {
                    display = rec.utf8At(firstSeen.tid(), firstSeen.after());
                } catch (IOException e) {
                    display = null; // dangling display-name blob (truncated tail)
                }
            }
        }
        out.println("object #" + objectId
                + (cls == null ? "" : "  (" + cls + (display == null ? "" : "." + display) + ")"));
        if (firstSeen == null) {
            out.println("  first-seen: none (id never registered — check for a truncated tail)");
        } else {
            out.println("  first-seen: seq " + firstSeen.seq() + " t" + firstSeen.tid());
        }
        if (snapshots.isEmpty()) {
            out.println("  snapshots:  none");
        } else {
            out.println("  snapshots:  " + snapshots.size());
            for (Rec s : snapshots) {
                out.println("    [seq " + s.seq() + " t" + s.tid() + "] snapshot " + vr.renderSnapshot(s));
            }
        }
        long refs = asTarget + asBefore + asAfter;
        if (refs == 0) {
            out.println("  references: none");
        } else {
            out.println("  references: " + refs + "  (this/owner " + asTarget
                    + ", value-before " + asBefore + ", value-after " + asAfter + ")"
                    + "  seq " + firstRefSeq + ".." + lastRefSeq);
            if (!sample.isEmpty()) {
                out.println("  first " + sample.size() + " reference record(s):");
                for (Rec r : sample) {
                    out.println("    [seq " + r.seq() + " t" + r.tid() + "] " + describe(rec, vr, r));
                }
            }
        }
        return 0;
    }

    /** Accepts a bare object id ({@code 27080}) or the {@code Class#id} form dump prints. */
    private static long parseObjectId(final String raw) {
        int hash = raw.lastIndexOf('#');
        String num = hash >= 0 ? raw.substring(hash + 1) : raw;
        return Long.parseLong(num.trim());
    }

    // ---- stats ----

    private static int stats(final Recording rec, final PrintStream out) throws IOException {
        surfaceTruncation(rec, out);
        Map<Integer, Long> byType = new TreeMap<>();
        Map<Integer, Long> byThread = new TreeMap<>();
        Map<Integer, Long> enterBySite = new HashMap<>();
        long objects = 0;
        long minSync = Long.MAX_VALUE;
        long maxSync = Long.MIN_VALUE;
        long total = 0;
        RecordCursor cur = rec.cursor();
        while (cur.hasNext()) {
            Rec r = cur.next();
            total++;
            byType.merge(r.type(), 1L, Long::sum);
            byThread.merge(r.tid(), 1L, Long::sum);
            switch (r.type()) {
                case Format.METHOD_ENTER -> enterBySite.merge(r.location(), 1L, Long::sum);
                case Format.OBJECT_FIRST_SEEN -> objects++;
                case Format.TIME_SYNC -> {
                    minSync = Math.min(minSync, r.after());
                    maxSync = Math.max(maxSync, r.after());
                }
                default -> {
                    // counted above
                }
            }
        }
        out.println("records: " + total);
        out.println("per type:");
        for (Map.Entry<Integer, Long> e : byType.entrySet()) {
            out.printf("  %-18s %d%n", Format.typeName(e.getKey()), e.getValue());
        }
        out.println("per thread:");
        long blobTotal = 0;
        for (int tid : rec.threadIds()) {
            long blob = rec.blobSize(tid);
            blobTotal += blob;
            out.printf("  t%-3d %d records, %d blob bytes%s%n",
                    tid, byThread.getOrDefault(tid, 0L), blob,
                    rec.hasPartialTail(tid) ? " (partial tail)" : "");
        }
        out.println("blob bytes total: " + blobTotal);
        out.println("top methods by ENTER count:");
        List<Map.Entry<Integer, Long>> top = new ArrayList<>(enterBySite.entrySet());
        top.sort(Map.Entry.<Integer, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        int shown = 0;
        for (Map.Entry<Integer, Long> e : top) {
            if (shown++ >= 20) {
                break;
            }
            Site s = rec.site(e.getKey());
            String name = s == null ? "?site" + e.getKey() : qualifiedMethod(rec, s);
            out.printf("  %-40s %d%n", name, e.getValue());
        }
        out.println("distinct objects: " + objects);
        if (minSync <= maxSync) {
            out.println("wall-clock span: " + (maxSync - minSync) + " ms (" + minSync + " .. " + maxSync + ")");
        } else {
            out.println("wall-clock span: unknown (no TIME_SYNC records)");
        }
        return 0;
    }

    // ---- verify ----

    private static int verify(final Recording rec, final PrintStream out) throws IOException {
        List<String> violations = Verifier.verify(rec);
        for (String v : violations) {
            out.println(v);
        }
        if (rec.isTruncated()) {
            surfaceTruncation(rec, out);
        }
        if (violations.isEmpty()) {
            out.println("OK");
            return 0;
        }
        out.println(violations.size() + " violation(s)");
        return 1;
    }
}
