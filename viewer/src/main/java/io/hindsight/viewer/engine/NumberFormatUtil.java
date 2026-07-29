// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

import io.hindsight.reader.Format;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Pure numeric decoration for INLINE primitive values (VIEWER.md §20.4/§20.5): the single place
 * that turns a reader-rendered integral/char value into the viewer's decorated form
 * {@code <decimal>  0x<HEX>[  (<date> UTC)]}. {@link Engine}'s value-formatting path ({@code toValue})
 * applies it when it builds a {@link Value.Primitive}, so locals, object trees, watches and the
 * map/element/field values reconstructed from snapshots all render identically.
 *
 * <p><b>Hex (§20.5).</b> Every integral value shows its two's-complement hex after the decimal,
 * zero-padded uppercase to the type width — byte 2, char 4, short 4, int 8, long 16 hex digits.
 * Booleans, floats and doubles get none. A char keeps its {@code 'c'} form then its {@code 0x00NN}.
 *
 * <p><b>Date (§20.4).</b> An int/long whose value lands in one of three non-overlapping epoch
 * ranges — seconds {@code [1e9,4e9)}, millis {@code [1e12,4e12)}, nanos {@code [1e18,4e18)} — is
 * suffixed with its UTC wall-clock {@code (YYYY-MM-DD HH:MM:SS UTC)} (no sub-second text). An int
 * only reaches the seconds range; a long reaches all three. The date follows the hex.
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
     * Decorates a reader-rendered INLINE value with §20.5 hex and §20.4 date as the tag warrants,
     * returning {@code base} unchanged for non-integral tags (boolean/float/double/null/void and
     * any unknown tag).
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
            case Format.TAG_INT -> base + hex(slot, 8) + date(slot);
            case Format.TAG_LONG -> base + hex(slot, 16) + date(slot);
            default -> base;
        };
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
