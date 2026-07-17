// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import io.hindsight.reader.Format;
import io.hindsight.viewer.engine.ClassSite;
import io.hindsight.viewer.engine.Instance;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The pure filter behind the "browse instances" mode of the {@code /} dialog
 * (VIEWER.md §13.2), mirroring {@link MethodBrowse}: a case-insensitive regex is
 * matched ({@code find()}) against every {@link ClassSite}'s runtime class binary
 * name, and the matching classes' instances are flattened into one list of rows
 * ascending by {@code seq}, capped.
 *
 * <p>Kept terminal-free and engine-free (the per-class instances arrive through a
 * {@link Function} the caller wires to {@code Engine.instancesOf}) so the regex
 * matching, the cross-class seq merge, and the cap-with-total bookkeeping are all
 * unit-testable against synthetic {@link ClassSite}s.
 */
public final class InstanceBrowse {

    /** Maximum number of instance rows the dialog shows (§13.2). */
    public static final int CAP = 500;

    /**
     * One flattened instance row: the {@code OBJECT_FIRST_SEEN} position, the object id the
     * explore window opens on, and the {@code SimpleClass#id} label.
     */
    public record Row(long seq, int tid, long index, long objectId, String display) {}

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

    /** The row label for an instance of a class: {@code SimpleClass#id} (unknown class as {@code ?}). */
    public static String display(final ClassSite site, final Instance instance) {
        String cls = site.classBinary() == null ? "?" : Format.simpleName(site.classBinary());
        return cls + "#" + instance.objectId();
    }

    /**
     * Filters {@code sites} by a case-insensitive regex over each class's {@link #matchString} and
     * flattens the matching classes' instances (fetched via {@code instancesProvider}) into one
     * list ascending by {@code (seq, tid, index)}, keeping at most {@code cap} rows (§13.2).
     *
     * <p>{@code totalMatches} is the exact sum of matching classes'
     * {@link ClassSite#instanceCount()} so the caller can render a "showing first {@code cap} of N"
     * note even though only {@code cap} rows are materialised. For the global first {@code cap}
     * rows to be correct, {@code instancesProvider} must return at least the first {@code cap}
     * instances of each class (as {@code Engine.instancesOf} does when asked for {@code cap}).
     *
     * @param sites the distinct first-seen runtime classes (typically {@code Engine.classSites()})
     * @param instancesProvider maps a class binary name to its instances, ascending by seq
     * @param regex the user's query, interpreted case-insensitively; matched with {@code find()}
     * @param cap the maximum rows to return (a non-positive cap yields no rows)
     * @return the filter result
     * @throws PatternSyntaxException if {@code regex} is not a valid regular expression
     */
    public static Result filter(
            final List<ClassSite> sites,
            final Function<String, List<Instance>> instancesProvider,
            final String regex,
            final int cap) {
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        long total = 0;
        List<Row> all = new ArrayList<>();
        for (ClassSite site : sites) {
            if (!pattern.matcher(matchString(site)).find()) {
                continue;
            }
            total += site.instanceCount();
            List<Instance> instances = instancesProvider.apply(site.classBinary());
            if (instances != null) {
                for (Instance i : instances) {
                    all.add(new Row(i.seq(), i.tid(), i.index(), i.objectId(), display(site, i)));
                }
            }
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
