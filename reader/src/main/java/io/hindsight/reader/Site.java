// SPDX-License-Identifier: Apache-2.0
package io.hindsight.reader;

import java.util.Map;

/**
 * One parsed {@code sites.tsv} row (FORMAT.md §5): a transform-time instrumented
 * location. The suffixes the agent appends to {@code desc} — the {@code #s}
 * static marker and (recorder ≥ v1.7) the {@code #p<slot>=<name>|...}
 * parameter-name list — are split off at parse time into {@link #isStatic()}
 * and {@link #paramNames()}, so consumers never have to re-scan descriptor
 * text and pre-v1.7 consumers of {@code desc} are unaffected.
 *
 * @param id dense site id (from 1)
 * @param kind {@code METHOD}, {@code FIELD} or {@code LOCAL}
 * @param classSym CLASS symbol id (for FIELD sites: the field <em>owner</em> class)
 * @param fileSym FILE symbol id of the source file, 0 if unknown
 * @param line source line (first body line / write line / store line), 0 if unknown
 * @param memberSym METHOD / FIELD / VAR symbol id of the member name
 * @param desc JVM descriptor text with any {@code #s} / {@code #p} suffix removed
 * @param slot LVT slot for LOCAL sites, -1 otherwise
 * @param isStatic whether the descriptor carried the {@code #s} static suffix
 * @param paramNames first-instruction LVT slot → parameter name from the {@code #p}
 *     suffix (§5, recorder ≥ v1.7); empty — never {@code null} — when the row carried
 *     none, in which case ENTER args keep the {@code argN} fallback
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
        boolean isStatic,
        Map<Integer, String> paramNames) {

    /** Normalises {@code paramNames} to an immutable, never-null map. */
    public Site {
        paramNames = paramNames == null || paramNames.isEmpty() ? Map.of() : Map.copyOf(paramNames);
    }

    /** Convenience shape for sites without parameter names (non-METHOD kinds, pre-v1.7 rows). */
    public Site(
            int id,
            String kind,
            int classSym,
            int fileSym,
            int line,
            int memberSym,
            String desc,
            int slot,
            boolean isStatic) {
        this(id, kind, classSym, fileSym, line, memberSym, desc, slot, isStatic, Map.of());
    }

    /** Site kind constant for method-bracket sites. */
    public static final String KIND_METHOD = "METHOD";

    /** Site kind constant for field-write sites. */
    public static final String KIND_FIELD = "FIELD";

    /** Site kind constant for local-variable-store sites. */
    public static final String KIND_LOCAL = "LOCAL";

    /** Site kind constant for source-line sites (§7 flow); {@code slot} is unused (-1). */
    public static final String KIND_LINE = "LINE";

    /** Site kind constant for conditional-jump sites (§7); {@code slot} holds the JVM opcode. */
    public static final String KIND_BRANCH = "BRANCH";
}
