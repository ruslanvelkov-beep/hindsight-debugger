// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

import io.hindsight.reader.Format;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

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
 * <p><b>Date (§20.4/§21.1/§22.1).</b> An int/long whose value lands in one of three non-overlapping
 * epoch ranges — seconds {@code [1e9,4e9)}, millis {@code [1e12,4e12)}, nanos {@code [1e18,4e18)} — has
 * a UTC wall-clock suffix {@code (YYYY-MM-DD HH:MM:SS UTC)} (no sub-second text) available via
 * {@link #dateSuffix}. Per §22.1 the suffix is only appended when the value's variable name has a
 * time-like <em>token</em> (see {@link #nameSuggestsTime}); {@link #decorateNamed} applies that gate.
 * The §22.1 token gate replaces the §21.1 raw {@code contains} test, which mis-fired on {@code update}
 * (⊇ {@code date}) and {@code runtime} (⊇ {@code time}). An int only reaches the seconds range; a long
 * reaches all three. The date follows the hex.
 *
 * <p>This class also hosts the two pure §22 name/element predicates that share the render pipeline:
 * {@link #isIdName} (§22.12, an ID-denoting field name) and {@link #alignNumericElements} (§22.9,
 * right-aligning a container's all-numeric element column).
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

    /**
     * The §22.1 time-like token set: a variable name whose {@link #tokenize tokens} include ANY of
     * these opens the date gate. Tokens are matched exactly — {@code update} tokenises to
     * {@code [update]} (not {@code date}) and {@code runtime} to {@code [runtime]} (not {@code time}),
     * so the substring false positives of the old §21.1 gate no longer date.
     */
    private static final Set<String> TIME_TOKENS = Set.of(
            "time", "date", "datetime", "instant", "timestamp", "ts",
            "second", "seconds", "sec", "milli", "millis", "ms",
            "nano", "nanos", "micro", "micros",
            "start", "end", "epoch", "expiry", "expiration", "expires",
            "deadline", "elapsed", "since", "until", "created", "updated", "modified");

    /**
     * Whether a variable name has a time-like token (§22.1): the name is split into lowercase tokens
     * on camelCase boundaries (lower→Upper, and either direction of letter↔digit) and on {@code _}/
     * {@code $}, and the gate opens iff any token is in {@link #TIME_TOKENS}. So {@code startTime},
     * {@code endMillis}, {@code expirationSecond} and {@code createdAt} qualify, while {@code update},
     * {@code runtime}, {@code renderCount} and {@code append} do not.
     */
    static boolean nameSuggestsTime(final String varName) {
        for (final String token : tokenize(varName)) {
            if (TIME_TOKENS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Splits {@code name} into lowercase tokens (§22.1): {@code _} and {@code $} are separators, a
     * lower→upper transition starts a new token (camelCase), and either direction of a letter↔digit
     * transition does too. {@code null}/empty yields an empty list. Consecutive uppercase letters
     * stay together (acronyms), which is harmless since no time token is a multi-letter acronym.
     */
    static List<String> tokenize(final String name) {
        final List<String> tokens = new ArrayList<>();
        if (name == null || name.isEmpty()) {
            return tokens;
        }
        final StringBuilder cur = new StringBuilder();
        char prev = 0;
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (c == '_' || c == '$') {
                flush(tokens, cur);
                prev = 0;
                continue;
            }
            if (cur.length() > 0 && prev != 0 && isTokenBoundary(prev, c)) {
                flush(tokens, cur);
            }
            cur.append(c);
            prev = c;
        }
        flush(tokens, cur);
        return tokens;
    }

    /** A camelCase (lower→Upper) or letter↔digit boundary between {@code prev} and {@code c} (§22.1). */
    private static boolean isTokenBoundary(final char prev, final char c) {
        final boolean lowerToUpper = Character.isLowerCase(prev) && Character.isUpperCase(c);
        final boolean letterDigit = (Character.isLetter(prev) && Character.isDigit(c))
                || (Character.isDigit(prev) && Character.isLetter(c));
        return lowerToUpper || letterDigit;
    }

    private static void flush(final List<String> tokens, final StringBuilder cur) {
        if (cur.length() > 0) {
            tokens.add(cur.toString().toLowerCase(Locale.ROOT));
            cur.setLength(0);
        }
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
    private static final Pattern INTEGRAL_HEAD = Pattern.compile("-?\\d+");
    private static final Pattern FLOAT_TEXT = Pattern.compile("-?\\d+\\.\\d+([eE][+-]?\\d+)?");

    /**
     * Right-aligns the numeric column of a container's element displays (§22.9), returning a same-size
     * list. When EVERY display is integral — its decimal head (the text before the {@code   0x} hex, or
     * the whole string when there is none) is {@code -?\d+} — each head is left-padded with spaces to
     * the widest head, the {@code   0x…} tail (if any) following unchanged. When EVERY display is
     * floating-point (a {@code -?\d+\.\d+} with optional exponent, no hex) the INTEGER PART (before the
     * {@code .}) is padded to the widest integer part, the fractional part unchanged. Any other mix —
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
        return d.indexOf(HEX_MARK) < 0 && FLOAT_TEXT.matcher(d).matches() ? 2 : 0;
    }

    /** The decimal text before the {@code   0x} hex, or the whole display when it carries no hex. */
    private static String decimalHead(final String d) {
        final int hex = d.indexOf(HEX_MARK);
        return hex >= 0 ? d.substring(0, hex) : d;
    }

    /** The integer part before the first {@code .}, or the whole display when it has none. */
    private static String integerPart(final String d) {
        final int dot = d.indexOf('.');
        return dot >= 0 ? d.substring(0, dot) : d;
    }

    private static String padLeft(final String s, final int width) {
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
