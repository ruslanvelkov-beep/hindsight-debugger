// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

import io.hindsight.reader.Format;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Pure numeric decoration for INLINE primitive values (VIEWER.md §20.5 hex, §26.4 date,
 * §26.5 byte/char forms). Everything is context-free — {@link #decorate(String, int, long)}
 * is applied by {@link Engine}'s {@code toValue} on every INLINE value, so locals, object
 * trees, watches, container elements and map keys/values all render identically:
 *
 * <ul>
 *   <li><b>Hex (§20.5).</b> Every integral value shows its two's-complement hex after the
 *       decimal, zero-padded uppercase to the type width — byte 2, char 4, short 4, int 8,
 *       long 16 hex digits. Booleans, floats and doubles get none.</li>
 *   <li><b>Byte (§26.5).</b> After the hex, a displayable byte (32–126) adds its character:
 *       {@code 72  0x48  'H'}.</li>
 *   <li><b>Char (§26.5).</b> {@code 'c'}, two spaces, the decimal char code left-padded to 5,
 *       then the hex: {@code 'A'     65  0x0041}.</li>
 *   <li><b>Date (§26.4).</b> Purely value-range-gated (the §21.1/§22.1 name gate is retired):
 *       any positive int/long — or float/double — landing in one of three epoch ranges that
 *       fall between 2000-01-01 and 2100-01-01 (seconds, millis, nanos since 1970-01-01) gets
 *       a UTC wall-clock suffix {@code (YYYY-MM-DD HH:MM:SS UTC)} (no sub-second text). The
 *       date follows the hex.</li>
 * </ul>
 *
 * <p>This class also hosts the two pure §22 name/element predicates that share the render
 * pipeline: {@link #isIdName} (§22.12, an ID-denoting field name) and
 * {@link #alignNumericElements} (§22.9, right-aligning a container's all-numeric element
 * column — aware of the hex and date suffixes).
 */
final class NumberFormatUtil {

    private static final DateTimeFormatter UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

    // §26.4: epoch ranges whose datings land between 2000-01-01T00:00Z and 2100-01-01T00:00Z.
    private static final long SEC_LO = 946_684_800L;
    private static final long SEC_HI = 4_102_444_800L;
    private static final long MS_LO = 946_684_800_000L;
    private static final long MS_HI = 4_102_444_800_000L;
    private static final long NS_LO = 946_684_800_000_000_000L;
    private static final long NS_HI = 4_102_444_800_000_000_000L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long MILLIS_PER_SECOND = 1_000L;

    private NumberFormatUtil() {}

    /**
     * Decorates a reader-rendered INLINE value with the context-free §20.5 hex, the §26.5
     * byte/char extras and the §26.4 range-gated date, as the tag warrants; {@code base} is
     * returned unchanged for boolean/null/void and any unknown tag.
     *
     * @param base the reader's already-rendered text (decimal for integrals, {@code 'c'} for char)
     * @param tag the FORMAT.md §2.3 value tag
     * @param slot the raw value slot (two's-complement for integrals, the char code for char,
     *     IEEE bits for float/double)
     */
    static String decorate(final String base, final int tag, final long slot) {
        return switch (tag) {
            case Format.TAG_BYTE -> base + hex(slot, 2) + byteChar(slot);
            case Format.TAG_SHORT -> base + hex(slot, 4);
            case Format.TAG_CHAR -> base + "  " + padLeft(Long.toString(slot), 5) + hex(slot, 4);
            case Format.TAG_INT -> base + hex(slot, 8) + date(slot);
            case Format.TAG_LONG -> base + hex(slot, 16) + date(slot);
            case Format.TAG_FLOAT -> base + date(Float.intBitsToFloat((int) slot));
            case Format.TAG_DOUBLE -> base + date(Double.longBitsToDouble(slot));
            default -> base;
        };
    }

    /** §26.5: {@code   'c'} for a displayable byte (32–126 inclusive), else empty. */
    private static String byteChar(final long slot) {
        return slot >= 32 && slot <= 126 ? "  '" + (char) slot + "'" : "";
    }

    /**
     * Whether a field name denotes an ID (§22.12): exactly {@code id} (any case), or ending in
     * {@code Id} (after a lowercase letter or digit) or {@code ID}, or starting with {@code id}
     * followed by an uppercase letter, {@code _} or {@code $}. So {@code id}, {@code Id}, {@code ID},
     * {@code accountId}, {@code nodeID}, {@code idField}, {@code id_x} and {@code id$} are ids, while
     * {@code notanid} and {@code valid} are not.
     */
    static boolean isIdName(final String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        return name.equalsIgnoreCase("id")
                || name.matches(".*[a-z0-9]Id$")
                || name.endsWith("ID")
                || name.matches("^id[A-Z_$].*");
    }

    private static final String HEX_MARK = "  0x";
    private static final String DATE_MARK = "  (";
    private static final Pattern INTEGRAL_HEAD = Pattern.compile("-?\\d+");
    private static final Pattern FLOAT_TEXT = Pattern.compile("-?\\d+\\.\\d+([eE][+-]?\\d+)?");

    /**
     * Right-aligns the numeric column of a container's element displays (§22.9), returning a same-size
     * list. When EVERY display is integral — its decimal head (the text before the {@code   0x} hex, or
     * before the §26.4 {@code   (} date for a hex-less display, or the whole string) is {@code -?\d+} —
     * each head is left-padded with spaces to the widest head, the tail following unchanged. When EVERY
     * display is floating-point (a {@code -?\d+\.\d+} with optional exponent before any date suffix, no
     * hex) the INTEGER PART (before the {@code .}) is padded to the widest integer part. Any other mix —
     * refs, strings, {@code null}, booleans, {@code NaN} — returns the input unchanged (no alignment).
     * Pure and order-preserving, so callers map the result back to their elements by index.
     */
    static List<String> alignNumericElements(final List<String> displays) {
        if (displays == null || displays.size() < 2) {
            return displays;
        }
        boolean allIntegral = true;
        boolean allFloat = true;
        for (final String d : displays) {
            final int kind = numericKind(d);
            allIntegral &= kind == 1;
            allFloat &= kind == 2;
        }
        if (allIntegral) {
            return padColumn(displays, NumberFormatUtil::decimalHead);
        }
        if (allFloat) {
            return padColumn(displays, NumberFormatUtil::integerPart);
        }
        return displays;
    }

    /** Left-pads each display's {@code segment} to the widest segment, keeping the rest verbatim. */
    private static List<String> padColumn(final List<String> displays, final Function<String, String> segment) {
        int width = 0;
        for (final String d : displays) {
            width = Math.max(width, segment.apply(d).length());
        }
        final List<String> out = new ArrayList<>(displays.size());
        for (final String d : displays) {
            final String seg = segment.apply(d);
            out.add(padLeft(seg, width) + d.substring(seg.length()));
        }
        return out;
    }

    /** {@code 1} integral ({@code -?\d+} head), {@code 2} floating-point, {@code 0} neither (§22.9). */
    private static int numericKind(final String d) {
        if (d == null) {
            return 0;
        }
        if (INTEGRAL_HEAD.matcher(decimalHead(d)).matches()) {
            return 1;
        }
        return d.indexOf(HEX_MARK) < 0 && FLOAT_TEXT.matcher(dateHead(d)).matches() ? 2 : 0;
    }

    /**
     * The decimal text before the {@code   0x} hex — or, for a hex-less display, before any §26.4
     * date suffix — or the whole display when it carries neither.
     */
    private static String decimalHead(final String d) {
        final int hex = d.indexOf(HEX_MARK);
        return hex >= 0 ? d.substring(0, hex) : dateHead(d);
    }

    /** The text before the §26.4 {@code   (…)} date suffix, or the whole display without one. */
    private static String dateHead(final String d) {
        final int date = d.indexOf(DATE_MARK);
        return date >= 0 ? d.substring(0, date) : d;
    }

    /** The integer part before the first {@code .}, or the whole display when it has none. */
    private static String integerPart(final String d) {
        final int dot = d.indexOf('.');
        return dot >= 0 ? d.substring(0, dot) : d;
    }

    /** Left-pads {@code s} with spaces to {@code width} columns (§22.9/§26.5). */
    static String padLeft(final String s, final int width) {
        if (s.length() >= width) {
            return s;
        }
        final StringBuilder b = new StringBuilder(width);
        for (int i = s.length(); i < width; i++) {
            b.append(' ');
        }
        return b.append(s).toString();
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

    /**
     * {@code   (YYYY-MM-DD HH:MM:SS UTC)} when {@code v} lands in one of the §26.4 epoch ranges
     * (all positive, all dating between 2000-01-01 and 2100-01-01), else empty.
     */
    static String date(final long v) {
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

    /**
     * The §26.4 date for a floating-point value: the same three ranges as {@link #date(long)},
     * the fractional part contributing sub-second precision that the (second-granular) text
     * simply truncates. NaN/infinite/negative/out-of-range values yield empty.
     */
    static String date(final double v) {
        if (!Double.isFinite(v) || v <= 0) {
            return "";
        }
        final Instant instant;
        if (v >= SEC_LO && v < SEC_HI) {
            instant = Instant.ofEpochSecond((long) v);
        } else if (v >= MS_LO && v < MS_HI) {
            instant = Instant.ofEpochSecond((long) (v / MILLIS_PER_SECOND));
        } else if (v >= NS_LO && v < NS_HI) {
            instant = Instant.ofEpochSecond((long) (v / NANOS_PER_SECOND));
        } else {
            return "";
        }
        return "  (" + UTC.format(instant) + " UTC)";
    }
}
