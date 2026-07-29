// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.reader.Format;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure §20.5/§21.1 numeric decoration helper. {@code decorate} is the context-free
 * §20.5 hex (width per type, two's-complement for negatives, boolean/float/double skipped, char) —
 * and, per §21.1, NEVER the date. The date lives in {@code dateSuffix} (the three epoch ranges at
 * their inclusive-low / exclusive-high boundaries) and is applied only through {@code decorateNamed}'s
 * name gate ({@code time}/{@code date}/{@code instant}). Date literals are the UTC wall-clock of the
 * epoch value; all three range-lows land on the same 2001-09-09 01:46:40 instant.
 */
final class NumberFormatUtilTest {

    @Test
    void hexWidthPerIntegralType() {
        assertEquals("42  0x2A", NumberFormatUtil.decorate("42", Format.TAG_BYTE, 42));
        assertEquals("42  0x002A", NumberFormatUtil.decorate("42", Format.TAG_SHORT, 42));
        assertEquals("42  0x0000002A", NumberFormatUtil.decorate("42", Format.TAG_INT, 42));
        assertEquals("42  0x000000000000002A", NumberFormatUtil.decorate("42", Format.TAG_LONG, 42));
    }

    @Test
    void charKeepsLiteralThenFourDigitHex() {
        assertEquals("'A'  0x0041", NumberFormatUtil.decorate("'A'", Format.TAG_CHAR, 'A'));
        // Max char (0xFFFF) exercises the full 4-digit width; the base text is passed through as-is.
        assertEquals("'x'  0xFFFF", NumberFormatUtil.decorate("'x'", Format.TAG_CHAR, 0xFFFF));
    }

    @Test
    void negativesUseTwosComplementHexAtEachWidth() {
        assertEquals("-1  0xFF", NumberFormatUtil.decorate("-1", Format.TAG_BYTE, -1));
        assertEquals("-1  0xFFFF", NumberFormatUtil.decorate("-1", Format.TAG_SHORT, -1));
        assertEquals("-1  0xFFFFFFFF", NumberFormatUtil.decorate("-1", Format.TAG_INT, -1));
        assertEquals("-1  0xFFFFFFFFFFFFFFFF", NumberFormatUtil.decorate("-1", Format.TAG_LONG, -1));
    }

    @Test
    void booleanFloatDoubleAreUndecorated() {
        assertEquals("true", NumberFormatUtil.decorate("true", Format.TAG_BOOLEAN, 1));
        assertEquals("false", NumberFormatUtil.decorate("false", Format.TAG_BOOLEAN, 0));
        assertEquals("3.14", NumberFormatUtil.decorate("3.14", Format.TAG_FLOAT, 0x4048F5C3L));
        assertEquals("3.14", NumberFormatUtil.decorate("3.14", Format.TAG_DOUBLE, 0x40091EB851EB851FL));
    }

    @Test
    void decorateIsHexOnlyEvenForInRangeValues() {
        // §21.1: decorate is now context-free hex ONLY — an in-range value gets NO date here
        // (the date is name-gated and lives in dateSuffix/decorateNamed).
        assertEquals("1000000000  0x3B9ACA00",
                NumberFormatUtil.decorate("1000000000", Format.TAG_INT, 1_000_000_000L));
        assertEquals("1000000000000  0x000000E8D4A51000",
                NumberFormatUtil.decorate("1000000000000", Format.TAG_LONG, 1_000_000_000_000L));
        assertEquals("1000000000000000000  0x0DE0B6B3A7640000",
                NumberFormatUtil.decorate("1000000000000000000", Format.TAG_LONG, 1_000_000_000_000_000_000L));
        // A between-ranges value and a tiny/negative long: hex only, exactly as before.
        assertEquals("5000000000  0x000000012A05F200",
                NumberFormatUtil.decorate("5000000000", Format.TAG_LONG, 5_000_000_000L));
        assertEquals("7  0x0000000000000007", NumberFormatUtil.decorate("7", Format.TAG_LONG, 7));
        assertEquals("-5  0xFFFFFFFFFFFFFFFB", NumberFormatUtil.decorate("-5", Format.TAG_LONG, -5));
    }

    @Test
    void dateSuffixSecondsRangeBoundaries() {
        // Inclusive low: 1e9 seconds = 2001-09-09 01:46:40 UTC (an int reaches this range).
        assertEquals("  (2001-09-09 01:46:40 UTC)", NumberFormatUtil.dateSuffix(Format.TAG_INT, 1_000_000_000L));
        assertEquals("", NumberFormatUtil.dateSuffix(Format.TAG_INT, 999_999_999L)); // just below
        assertEquals("", NumberFormatUtil.dateSuffix(Format.TAG_LONG, 4_000_000_000L)); // exclusive high
        assertEquals("  (2096-10-02 07:06:39 UTC)", NumberFormatUtil.dateSuffix(Format.TAG_LONG, 3_999_999_999L));
    }

    @Test
    void dateSuffixMillisAndNanosRangeBoundaries() {
        assertEquals("  (2001-09-09 01:46:40 UTC)",
                NumberFormatUtil.dateSuffix(Format.TAG_LONG, 1_000_000_000_000L));
        assertEquals("", NumberFormatUtil.dateSuffix(Format.TAG_LONG, 4_000_000_000_000L)); // exclusive high
        assertEquals("  (2001-09-09 01:46:40 UTC)",
                NumberFormatUtil.dateSuffix(Format.TAG_LONG, 1_000_000_000_000_000_000L));
        assertEquals("", NumberFormatUtil.dateSuffix(Format.TAG_LONG, 4_000_000_000_000_000_000L));
        // A between-ranges value: no date.
        assertEquals("", NumberFormatUtil.dateSuffix(Format.TAG_LONG, 5_000_000_000L));
    }

    @Test
    void dateSuffixOnlyForIntAndLongTags() {
        // Only int/long can be a date; byte/short/char/boolean/double never date even in-range.
        assertEquals("", NumberFormatUtil.dateSuffix(Format.TAG_SHORT, 1_000_000_000L));
        assertEquals("", NumberFormatUtil.dateSuffix(Format.TAG_BYTE, 1_000_000_000L));
        assertEquals("", NumberFormatUtil.dateSuffix(Format.TAG_CHAR, 1_000_000_000L));
        assertEquals("", NumberFormatUtil.dateSuffix(Format.TAG_BOOLEAN, 1_000_000_000L));
        assertEquals("", NumberFormatUtil.dateSuffix(Format.TAG_DOUBLE, 1_000_000_000L));
    }

    @Test
    void decorateNamedGatesDateOnTimeLikeNames() {
        String hex = NumberFormatUtil.decorate("1000000000000", Format.TAG_LONG, 1_000_000_000_000L);
        String dated = hex + "  (2001-09-09 01:46:40 UTC)";
        // time / date / instant (any case) → date appended after the hex.
        assertEquals(dated, NumberFormatUtil.decorateNamed(hex, "lastUpdateTime", Format.TAG_LONG, 1_000_000_000_000L));
        assertEquals(dated, NumberFormatUtil.decorateNamed(hex, "createdDate", Format.TAG_LONG, 1_000_000_000_000L));
        assertEquals(dated, NumberFormatUtil.decorateNamed(hex, "myINSTANT", Format.TAG_LONG, 1_000_000_000_000L));
        // A non-time name, and a null name (nameless context), keep hex only.
        assertEquals(hex, NumberFormatUtil.decorateNamed(hex, "count", Format.TAG_LONG, 1_000_000_000_000L));
        assertEquals(hex, NumberFormatUtil.decorateNamed(hex, null, Format.TAG_LONG, 1_000_000_000_000L));
        // A time name but an out-of-range value: the gate opens but dateSuffix is empty → hex only.
        String hexSmall = NumberFormatUtil.decorate("42", Format.TAG_LONG, 42L);
        assertEquals(hexSmall, NumberFormatUtil.decorateNamed(hexSmall, "timestamp", Format.TAG_LONG, 42L));
    }

    // ---- §22.1 token-based date gate ----

    @Test
    void nameSuggestsTimeMatchesTokensNotSubstrings() {
        // Positive: a time-like token in the camelCase / '_' / '$' split opens the gate.
        assertTrue(NumberFormatUtil.nameSuggestsTime("startTime"));
        assertTrue(NumberFormatUtil.nameSuggestsTime("endMillis"));
        assertTrue(NumberFormatUtil.nameSuggestsTime("expirationSecond"));
        assertTrue(NumberFormatUtil.nameSuggestsTime("createdAt"), "'created' is a token");
        assertTrue(NumberFormatUtil.nameSuggestsTime("epoch_ms"), "'_' split + 'ms' token");
        assertTrue(NumberFormatUtil.nameSuggestsTime("ts"), "whole name is a token");
        // Negative: the old §21.1 substring gate mis-fired on these; token matching fixes it.
        assertFalse(NumberFormatUtil.nameSuggestsTime("update"), "⊇ 'date' but the token is 'update'");
        assertFalse(NumberFormatUtil.nameSuggestsTime("runtime"), "⊇ 'time' but the token is 'runtime'");
        assertFalse(NumberFormatUtil.nameSuggestsTime("renderCount"));
        assertFalse(NumberFormatUtil.nameSuggestsTime("append"));
        assertFalse(NumberFormatUtil.nameSuggestsTime(null));
    }

    @Test
    void decorateNamedTokenGateFixesOldFalsePositives() {
        // An in-range long that WOULD have been dated by the §21.1 substring gate on 'update'/'runtime'.
        String hex = NumberFormatUtil.decorate("1000000000000", Format.TAG_LONG, 1_000_000_000_000L);
        String dated = hex + "  (2001-09-09 01:46:40 UTC)";
        assertEquals(dated, NumberFormatUtil.decorateNamed(hex, "startTime", Format.TAG_LONG, 1_000_000_000_000L));
        assertEquals(hex, NumberFormatUtil.decorateNamed(hex, "update", Format.TAG_LONG, 1_000_000_000_000L));
        assertEquals(hex, NumberFormatUtil.decorateNamed(hex, "runtime", Format.TAG_LONG, 1_000_000_000_000L));
    }

    // ---- §22.12 ID-name predicate ----

    @Test
    void isIdNameMatrix() {
        for (String id : new String[] {"id", "Id", "ID", "accountId", "nodeID", "idField", "id_x", "id$"}) {
            assertTrue(NumberFormatUtil.isIdName(id), id + " denotes an id");
        }
        for (String notId : new String[] {"notanid", "valid", "identifier", "count", "", null}) {
            assertFalse(NumberFormatUtil.isIdName(notId), notId + " does not denote an id");
        }
    }

    // ---- §22.9 numeric element alignment ----

    @Test
    void alignNumericElementsRightAlignsIntegersAndFloats() {
        // Integers: pad decimals to the widest (width 3).
        assertEquals(List.of("  1", " 22", "333"),
                NumberFormatUtil.alignNumericElements(List.of("1", "22", "333")));
        // The hex tail follows the padded decimal (width is computed on the decimal only).
        assertEquals(List.of(" 7  0x07", "42  0x2A"),
                NumberFormatUtil.alignNumericElements(List.of("7  0x07", "42  0x2A")));
        // A negative counts its sign toward the width.
        assertEquals(List.of("-3", " 5"),
                NumberFormatUtil.alignNumericElements(List.of("-3", "5")));
        // Floats: pad the integer part; the fractional part is kept as-is.
        assertEquals(List.of("  1.5", " 22.25", "333.0"),
                NumberFormatUtil.alignNumericElements(List.of("1.5", "22.25", "333.0")));
        // Mixed (ref, or int+float) / non-numeric: returned unchanged.
        assertEquals(List.of("1", "Bar#100"),
                NumberFormatUtil.alignNumericElements(List.of("1", "Bar#100")));
        assertEquals(List.of("1", "2.5"),
                NumberFormatUtil.alignNumericElements(List.of("1", "2.5")));
    }
}
