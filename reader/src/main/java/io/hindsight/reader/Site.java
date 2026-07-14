// SPDX-License-Identifier: Apache-2.0
package io.hindsight.reader;

/**
 * One parsed {@code sites.tsv} row (FORMAT.md §5): a transform-time instrumented
 * location. The {@code #s} static-marker suffix that the agent appends to
 * {@code desc} is split off at parse time into {@link #isStatic()} so consumers
 * never have to re-scan descriptor text.
 *
 * @param id dense site id (from 1)
 * @param kind {@code METHOD}, {@code FIELD} or {@code LOCAL}
 * @param classSym CLASS symbol id (for FIELD sites: the field <em>owner</em> class)
 * @param fileSym FILE symbol id of the source file, 0 if unknown
 * @param line source line (first body line / write line / store line), 0 if unknown
 * @param memberSym METHOD / FIELD / VAR symbol id of the member name
 * @param desc JVM descriptor text with any {@code #s} suffix removed
 * @param slot LVT slot for LOCAL sites, -1 otherwise
 * @param isStatic whether the descriptor carried the {@code #s} static suffix
 */
public record Site(
        int id,
        String kind,
        int classSym,
        int fileSym,
        int line,
        int memberSym,
        String desc,
        int slot,
        boolean isStatic) {

    /** Site kind constant for method-bracket sites. */
    public static final String KIND_METHOD = "METHOD";

    /** Site kind constant for field-write sites. */
    public static final String KIND_FIELD = "FIELD";

    /** Site kind constant for local-variable-store sites. */
    public static final String KIND_LOCAL = "LOCAL";
}
