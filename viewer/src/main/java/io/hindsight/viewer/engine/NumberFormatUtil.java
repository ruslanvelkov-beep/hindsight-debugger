// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

import io.hindsight.reader.Format;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Pure numeric decoration for INLINE primitive values (VIEWER.md §20.5 hex, §20.4/§21.1 date).
 * The decoration is split in two so hex stays context-free while the date is <em>name-gated</em>:
 *
 * <ul>
 *   <li>{@link #decorate(String, int, long)} — the context-free part {@link Engine}'s
 *       {@code toValue} always applies: the §20.5 hex on every integral value. This is the single
 *       place turning a reader-rendered integral/char into {@code <decimal>  0x<HEX>}, so locals,
 *       object trees, watches and the map/element/field values reconstructed from snapshots all get
 *       the hex identically.</li>
 *   <li>{@link #dateSuffix(int, long)} / {@link #decorateNamed(String, String, int, long)} — the
 *       §21.1 name-gated date, applied ONLY at the render sites that carry a variable name (locals,
 *       snapshot fields, watches). Nameless contexts (container elements, map keys/values) get no
 *       date.</li>
 * </ul>
 *
 * <p><b>Hex (§20.5).</b> Every integral value shows its two's-complement hex after the decimal,
 * zero-padded uppercase to the type width — byte 2, char 4, short 4, int 8, long 16 hex digits.
 * Booleans, floats and doubles get none. A char keeps its {@code 'c'} form then its {@code 0x00NN}.
 *
 * <p><b>Date (§20.4/§21.1).</b> An int/long whose value lands in one of three non-overlapping epoch
 * ranges — seconds {@code [1e9,4e9)}, millis {@code [1e12,4e12)}, nanos {@code [1e18,4e18)} — has a
 * UTC wall-clock suffix {@code (YYYY-MM-DD HH:MM:SS UTC)} (no sub-second text) available via
 * {@link #dateSuffix}. Per §21.1 the suffix is only appended when the value's variable name contains
 * {@code time}, {@code date} or {@code instant} (case-insensitive); {@link #decorateNamed} applies
 * that gate. An int only reaches the seconds range; a long reaches all three. The date follows the hex.
 */
final class NumberFormatUtil {

    private static final DateTimeFormatter UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

    private static final long SEC_LO = 1_000_000_000L;
    private static final long SEC_HI = 4_000_000_000L;
    private static final long MS_LO = 1_000_000_000_000L;
    private static final long MS_HI = 4_000_000_000_000L;
    private static final long NS_LO = 1_000_000_000_000_000_000L;
    private static final long NS_HI = 4_000_000_000_000_000_000L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private NumberFormatUtil() {}

    /**
     * Decorates a reader-rendered INLINE value with the context-free §20.5 hex as the tag warrants,
     * returning {@code base} unchanged for non-integral tags (boolean/float/double/null/void and any
     * unknown tag). The §20.4/§21.1 date is <em>not</em> applied here — it is name-gated and added by
     * {@link #decorateNamed} at the name-bearing render sites.
     *
     * @param base the reader's already-rendered text (decimal for integrals, {@code 'c'} for char)
     * @param tag the FORMAT.md §2.3 value tag
     * @param slot the raw value slot (two's-complement for integrals, the char code for char)
     */
    static String decorate(final String base, final int tag, final long slot) {
        return switch (tag) {
            case Format.TAG_BYTE -> base + hex(slot, 2);
            case Format.TAG_SHORT -> base + hex(slot, 4);
            case Format.TAG_CHAR -> base + hex(slot, 4);
            case Format.TAG_INT -> base + hex(slot, 8);
            case Format.TAG_LONG -> base + hex(slot, 16);
            default -> base;
        };
    }

    /**
     * The §20.4 UTC date suffix {@code   (YYYY-MM-DD HH:MM:SS UTC)} for an int/long value that lands
     * in an epoch range (seconds {@code [1e9,4e9)}, millis {@code [1e12,4e12)}, nanos {@code [1e18,4e18)});
     * {@code ""} for any other tag or an out-of-range value. This is the raw, ungated suffix — callers
     * apply the §21.1 name gate via {@link #decorateNamed}.
     *
     * @param tag the FORMAT.md §2.3 value tag (only {@code TAG_INT}/{@code TAG_LONG} can produce a date)
     * @param slot the raw value slot
     */
    static String dateSuffix(final int tag, final long slot) {
        return (tag == Format.TAG_INT || tag == Format.TAG_LONG) ? date(slot) : "";
    }

    /**
     * Appends the §21.1 name-gated date to an already-hex-decorated value: the {@link #dateSuffix}
     * is added only when {@code varName} (case-insensitive) contains {@code time}, {@code date} or
     * {@code instant} AND the value is an in-range int/long. Nameless / non-time-named values are
     * returned unchanged, so container elements and non-temporal fields keep hex only (§21.1).
     *
     * @param hexDecorated the value already carrying its §20.5 hex (the output of {@link #decorate})
     * @param varName the value's variable/field/watch name, or {@code null} when nameless
     * @param tag the FORMAT.md §2.3 value tag
     * @param slot the raw value slot
     */
    static String decorateNamed(final String hexDecorated, final String varName, final int tag, final long slot) {
        return nameSuggestsTime(varName) ? hexDecorated + dateSuffix(tag, slot) : hexDecorated;
    }

    /** Whether a variable name (case-insensitive) contains {@code time}, {@code date} or {@code instant} (§21.1). */
    private static boolean nameSuggestsTime(final String varName) {
        if (varName == null) {
            return false;
        }
        String n = varName.toLowerCase(Locale.ROOT);
        return n.contains("time") || n.contains("date") || n.contains("instant");
    }

    /** {@code   0x<HEX>}: {@code slot} masked to {@code digits} nibbles, zero-padded uppercase. */
    private static String hex(final long slot, final int digits) {
        long masked = digits >= 16 ? slot : slot & ((1L << (digits * 4)) - 1);
        String h = Long.toHexString(masked).toUpperCase(Locale.ROOT);
        StringBuilder b = new StringBuilder("  0x");
        for (int pad = digits - h.length(); pad > 0; pad--) {
            b.append('0');
        }
        return b.append(h).toString();
    }

    /** {@code   (YYYY-MM-DD HH:MM:SS UTC)} when {@code v} lands in an epoch range, else empty. */
    private static String date(final long v) {
        final Instant instant;
        if (v >= SEC_LO && v < SEC_HI) {
            instant = Instant.ofEpochSecond(v);
        } else if (v >= MS_LO && v < MS_HI) {
            instant = Instant.ofEpochMilli(v);
        } else if (v >= NS_LO && v < NS_HI) {
            instant = Instant.ofEpochSecond(Math.floorDiv(v, NANOS_PER_SECOND), Math.floorMod(v, NANOS_PER_SECOND));
        } else {
            return "";
        }
        return "  (" + UTC.format(instant) + " UTC)";
    }
}
