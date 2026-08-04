// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import io.hindsight.reader.Format;
import io.hindsight.viewer.engine.ClassSite;
import io.hindsight.viewer.engine.Instance;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The pure filter behind the "browse instances" mode of the {@code /} dialog
 * (VIEWER.md §13.2/§14.1/§15.1), mirroring {@link MethodBrowse}: a case-insensitive
 * regex is matched ({@code find()}) against every {@link ClassSite}'s runtime class
 * name, and the matching classes' instances are flattened into one list of rows
 * ascending by {@code seq}, capped. A query of the form {@code <regex>#<digits>}
 * (or bare {@code #<digits>}) additionally restricts the rows to the one exact
 * object id (§14.1).
 *
 * <p>What the regex is matched against depends on the query itself (§15.1): a class
 * part containing no {@code .} and no {@code $} is matched against each class's
 * SIMPLE name — the binary name's segment after the last {@code .} and after the
 * last {@code $} — so {@code VirtualMapStateImpl} no longer drags in its inner
 * class {@code …VirtualMapStateImpl$MerkleReadableStates}; a class part containing
 * {@code .} or {@code $} declares qualification intent and is matched against the
 * FULL binary name as before.
 *
 * <p>Kept terminal-free and engine-free (the per-class instances arrive through a
 * {@link Function} the caller wires to {@code Engine.instancesOf}) so the regex
 * matching, the id parsing, the cross-class seq merge, and the cap-with-total
 * bookkeeping are all unit-testable against synthetic {@link ClassSite}s.
 */
public final class InstanceBrowse {

    /** Maximum number of instance rows the dialog shows (§13.2). */
    public static final int CAP = 500;

    /**
     * One flattened instance row: the {@code OBJECT_FIRST_SEEN} position, the object id whose
     * tree the row expands into (§14.2), the runtime class binary name (for the §14.4 info
     * line; may be {@code null} for an unknown class), and the {@code SimpleClass#id} label.
     */
    public record Row(long seq, int tid, long index, long objectId, String classBinary, String display) {}

    /**
     * A parsed instances-mode query (§14.1): the class-regex part plus the exact object id the
     * query demands, or {@code null} when the query is a plain regex.
     */
    public record Query(String regex, Long objectId) {}

    /**
     * A completed filter.
     *
     * @param rows the instance rows to show, ascending by seq, at most {@code cap} of them
     * @param totalMatches the true total number of instances across all matching classes
     * @param truncated whether {@code totalMatches} exceeded the cap (more rows exist than shown)
     */
    public record Result(List<Row> rows, long totalMatches, boolean truncated) {}

    private InstanceBrowse() {}

    /** The regex-match target for a class site: its runtime class binary name ({@code null} as empty). */
    public static String matchString(final ClassSite site) {
        return site.classBinary() == null ? "" : site.classBinary();
    }

    /**
     * Whether a class-regex part declares qualification intent (§15.1): it does exactly when it
     * contains a {@code .} or a {@code $}. A qualified query is matched against the full binary
     * name; an unqualified one against the {@link #simpleSegment} only.
     */
    public static boolean isQualified(final String classRegex) {
        return classRegex.indexOf('.') >= 0 || classRegex.indexOf('$') >= 0;
    }

    /**
     * The §15.1 simple-name segment of a binary name: everything after the last {@code .} AND
     * after the last {@code $} ({@code com.foo.Outer$Inner} → {@code Inner}). Deliberately the
     * spec's literal segment rule — no array-name decoding — because it defines what an
     * unqualified query is matched against, not how a value is displayed.
     */
    public static String simpleSegment(final String binaryName) {
        int cut = Math.max(binaryName.lastIndexOf('.'), binaryName.lastIndexOf('$'));
        return cut >= 0 ? binaryName.substring(cut + 1) : binaryName;
    }

    /**
     * What a class site's name is matched against (§15.1): the full binary name when the query
     * was {@linkplain #isQualified qualified}, its {@linkplain #simpleSegment simple segment}
     * otherwise.
     */
    public static String matchTarget(final ClassSite site, final boolean qualified) {
        String full = matchString(site);
        return qualified ? full : simpleSegment(full);
    }

    /** The row label for an instance of a class: {@code SimpleClass#id} (unknown class as {@code ?}). */
    public static String display(final ClassSite site, final Instance instance) {
        String cls = site.classBinary() == null ? "?" : Format.simpleName(site.classBinary());
        return cls + "#" + instance.objectId();
    }

    /**
     * Parses an instances-mode query into its class-regex and optional exact-id parts (§14.1):
     * when the text after the LAST {@code #} is non-empty and digits-only, that suffix is the
     * demanded object id and everything before the {@code #} is the class regex (empty for the
     * bare {@code #<digits>} form — it matches any class). A {@code #} not followed by
     * digits-only text stays part of the regex.
     */
    public static Query parseQuery(final String query) {
        int hash = query.lastIndexOf('#');
        if (hash >= 0) {
            String digits = query.substring(hash + 1);
            if (!digits.isEmpty() && digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
                try {
                    return new Query(query.substring(0, hash), Long.valueOf(digits));
                } catch (NumberFormatException e) {
                    // Overflows a long — no object id can match; treat the whole text as regex.
                }
            }
        }
        return new Query(query, null);
    }

    /**
     * Filters {@code sites} by a case-insensitive regex over each class's {@link #matchTarget} —
     * the simple name for an unqualified query, the full binary name for one containing {@code .}
     * or {@code $} (§15.1) — and flattens the matching classes' instances (fetched via
     * {@code instancesProvider}) into one list ascending by {@code (seq, tid, index)}, keeping at
     * most {@code cap} rows (§13.2). When the query carries an exact object id (§14.1, see
     * {@link #parseQuery}), only instances with that id are listed and {@code totalMatches}
     * counts exactly those; the id suffix composes with the §15.1 rule (the class part before
     * {@code #} chooses simple-vs-full matching by its own content).
     *
     * <p>Otherwise {@code totalMatches} is the exact sum of matching classes'
     * {@link ClassSite#instanceCount()} so the caller can render a "showing first {@code cap} of N"
     * note even though only {@code cap} rows are materialised. For the global first {@code cap}
     * rows to be correct, {@code instancesProvider} must return at least the first {@code cap}
     * instances of each class (as {@code Engine.instancesOf} does when asked for {@code cap});
     * for an id query it should return ALL instances of each class, or a late instance is missed.
     *
     * @param sites the distinct first-seen runtime classes (typically {@code Engine.classSites()})
     * @param instancesProvider maps a class binary name to its instances, ascending by seq
     * @param query the user's query: a case-insensitive regex, optionally suffixed {@code #<id>}
     * @param cap the maximum rows to return (a non-positive cap yields no rows)
     * @return the filter result
     * @throws PatternSyntaxException if the query's regex part is not a valid regular expression
     */
    public static Result filter(
            final List<ClassSite> sites,
            final Function<String, List<Instance>> instancesProvider,
            final String query,
            final int cap) {
        return filter(sites, instancesProvider, query, cap, OptionalInt.empty());
    }

    /**
     * As {@link #filter(List, Function, String, int)} but with the §21.6 thread filter: when
     * {@code tidFilter} is present, only instances whose {@code tid} equals it are listed and
     * {@code totalMatches} counts exactly those (so the §15.3 count line stays accurate); an empty
     * {@code tidFilter} means all threads and reproduces the four-argument behaviour exactly. The
     * §21.6 dialog passes {@code cap == Integer.MAX_VALUE} (uncapped) so no truncation occurs; the
     * thread filter composes with the {@code #<id>} form the same way the class regex does.
     *
     * @param sites the distinct first-seen runtime classes (typically {@code Engine.classSites()})
     * @param instancesProvider maps a class binary name to its instances, ascending by seq
     * @param query the user's query: a case-insensitive regex, optionally suffixed {@code #<id>}
     * @param cap the maximum rows to return (a non-positive cap yields no rows)
     * @param tidFilter the thread id to restrict rows to, or empty for all threads (§21.6)
     * @return the filter result
     * @throws PatternSyntaxException if the query's regex part is not a valid regular expression
     */
    public static Result filter(
            final List<ClassSite> sites,
            final Function<String, List<Instance>> instancesProvider,
            final String query,
            final int cap,
            final OptionalInt tidFilter) {
        Query q = parseQuery(query);
        boolean qualified = isQualified(q.regex());
        Pattern pattern = Pattern.compile(q.regex(), Pattern.CASE_INSENSITIVE);
        boolean byThread = tidFilter.isPresent();
        int wantTid = tidFilter.orElse(0);
        long total = 0;
        List<Row> all = new ArrayList<>();
        for (ClassSite site : sites) {
            if (!pattern.matcher(matchTarget(site, qualified)).find()) {
                continue;
            }
            if (q.objectId() == null && !byThread) {
                total += site.instanceCount();
            }
            List<Instance> instances = instancesProvider.apply(site.classBinary());
            if (instances != null) {
                for (Instance i : instances) {
                    if (q.objectId() != null && i.objectId() != q.objectId()) {
                        continue;
                    }
                    if (byThread && i.tid() != wantTid) {
                        continue;
                    }
                    all.add(new Row(i.seq(), i.tid(), i.index(), i.objectId(), site.classBinary(), display(site, i)));
                }
            }
        }
        if (q.objectId() != null || byThread) {
            total = all.size();
        }
        all.sort(Comparator.comparingLong(Row::seq)
                .thenComparingInt(Row::tid)
                .thenComparingLong(Row::index));
        int keep = Math.max(0, cap);
        boolean truncated = total > keep || all.size() > keep;
        List<Row> rows = all.size() > keep ? List.copyOf(all.subList(0, keep)) : List.copyOf(all);
        return new Result(rows, total, truncated);
    }
}
