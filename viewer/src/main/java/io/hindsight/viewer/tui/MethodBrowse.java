// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import io.hindsight.reader.Format;
import io.hindsight.viewer.engine.Call;
import io.hindsight.viewer.engine.MethodSite;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The pure filter behind the regex "browse method calls" dialog (VIEWER.md §11.7):
 * a case-insensitive regex is matched against every {@link MethodSite}'s
 * {@code Class.method} string, and the matching sites' invocations are flattened
 * into one list of individual call rows ascending by {@code seq}, capped.
 *
 * <p>Kept terminal-free and engine-free (the per-site invocations arrive through an
 * {@link IntFunction} the caller wires to {@code Engine.callsOf}) so the regex
 * matching, the cross-site seq merge, and the cap-with-total bookkeeping are all
 * unit-testable against synthetic {@link MethodSite}s.
 */
public final class MethodBrowse {

    /** Maximum number of invocation rows the dialog shows (§11.7). */
    public static final int CAP = 500;

    /** One flattened invocation row: where to jump plus its {@code Class.method} label. */
    public record Row(long seq, int tid, long index, String display) {}

    /**
     * A completed filter.
     *
     * @param rows the invocation rows to show, ascending by seq, at most {@code cap} of them
     * @param totalMatches the true total number of matching invocations across all matching sites
     * @param truncated whether {@code totalMatches} exceeded the cap (more rows exist than shown)
     */
    public record Result(List<Row> rows, long totalMatches, boolean truncated) {}

    private MethodBrowse() {}

    /** The regex-match target for a site: {@code classBinary.methodName} (nulls as empty). */
    public static String matchString(final MethodSite site) {
        String cls = site.classBinary() == null ? "" : site.classBinary();
        String method = site.methodName() == null ? "" : site.methodName();
        return cls + "." + method;
    }

    /** The row label for a site: {@code SimpleClass.method} (unknowns as {@code ?}). */
    public static String display(final MethodSite site) {
        String cls = site.classBinary() == null ? "?" : Format.simpleName(site.classBinary());
        String method = site.methodName() != null ? site.methodName() : "?";
        return cls + "." + method;
    }

    /**
     * Filters {@code sites} by a case-insensitive regex over each site's {@link #matchString} and
     * flattens the matching sites' invocations (fetched via {@code callsProvider}) into one list
     * ascending by {@code (seq, tid, index)}, keeping at most {@code cap} rows (§11.7).
     *
     * <p>{@code totalMatches} is the exact sum of matching sites' {@link MethodSite#invocationCount()}
     * so the caller can render a "showing first {@code cap} of N" note even though only {@code cap}
     * rows are materialised. For the global first {@code cap} rows to be correct, {@code callsProvider}
     * must return at least the first {@code cap} invocations of each site (as {@code Engine.callsOf}
     * does when asked for {@code cap}).
     *
     * @param sites the distinct entered method sites (typically {@code Engine.methodSites()})
     * @param callsProvider maps a site id to that site's invocations, ascending by seq
     * @param regex the user's query, interpreted case-insensitively; matched with {@code find()}
     * @param cap the maximum rows to return (a non-positive cap yields no rows)
     * @return the filter result
     * @throws PatternSyntaxException if {@code regex} is not a valid regular expression
     */
    public static Result filter(
            final List<MethodSite> sites,
            final IntFunction<List<Call>> callsProvider,
            final String regex,
            final int cap) {
        return filter(sites, callsProvider, regex, cap, OptionalInt.empty());
    }

    /**
     * As {@link #filter(List, IntFunction, String, int)} but with the §21.6 thread filter: when
     * {@code tidFilter} is present, only invocations whose {@code tid} equals it are kept and
     * {@code totalMatches} counts exactly those (so the §15.3 count line stays accurate); an empty
     * {@code tidFilter} means all threads and reproduces the four-argument behaviour exactly. The
     * §21.6 dialog passes {@code cap == Integer.MAX_VALUE} (uncapped) so no truncation occurs.
     *
     * @param sites the distinct entered method sites (typically {@code Engine.methodSites()})
     * @param callsProvider maps a site id to that site's invocations, ascending by seq
     * @param regex the user's query, interpreted case-insensitively; matched with {@code find()}
     * @param cap the maximum rows to return (a non-positive cap yields no rows)
     * @param tidFilter the thread id to restrict rows to, or empty for all threads (§21.6)
     * @return the filter result
     * @throws PatternSyntaxException if {@code regex} is not a valid regular expression
     */
    public static Result filter(
            final List<MethodSite> sites,
            final IntFunction<List<Call>> callsProvider,
            final String regex,
            final int cap,
            final OptionalInt tidFilter) {
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        boolean byThread = tidFilter.isPresent();
        int wantTid = tidFilter.orElse(0);
        long total = 0;
        List<Row> all = new ArrayList<>();
        for (MethodSite site : sites) {
            if (!pattern.matcher(matchString(site)).find()) {
                continue;
            }
            if (!byThread) {
                total += site.invocationCount();
            }
            String label = display(site);
            List<Call> calls = callsProvider.apply(site.siteId());
            if (calls != null) {
                for (Call c : calls) {
                    if (byThread && c.tid() != wantTid) {
                        continue;
                    }
                    all.add(new Row(c.seq(), c.tid(), c.index(), label));
                }
            }
        }
        if (byThread) {
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
