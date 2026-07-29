// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.hindsight.reader.Format;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure §20.4/§20.5 numeric decoration helper: hex width per type, two's-complement
 * hex for negatives, the boolean/float/double skips, char, and the three epoch date ranges at their
 * inclusive-low / exclusive-high boundaries (plus a value in no range). Date literals are the UTC
 * wall-clock of the epoch value; all three range-lows land on the same 2001-09-09 01:46:40 instant.
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
    void secondsRangeBoundaries() {
        // Inclusive low: 1e9 seconds = 2001-09-09 01:46:40 UTC (an int reaches this range).
        assertEquals("1000000000  0x3B9ACA00  (2001-09-09 01:46:40 UTC)",
                NumberFormatUtil.decorate("1000000000", Format.TAG_INT, 1_000_000_000L));
        // Just below the low: no date.
        assertEquals("999999999  0x3B9AC9FF",
                NumberFormatUtil.decorate("999999999", Format.TAG_INT, 999_999_999L));
        // Exclusive high (a long, since it exceeds int range): no date, hex only.
        assertEquals("4000000000  0x00000000EE6B2800",
                NumberFormatUtil.decorate("4000000000", Format.TAG_LONG, 4_000_000_000L));
        // Just below the high: date present.
        assertEquals("3999999999  0x00000000EE6B27FF  (2096-10-02 07:06:39 UTC)",
                NumberFormatUtil.decorate("3999999999", Format.TAG_LONG, 3_999_999_999L));
    }

    @Test
    void millisRangeBoundaries() {
        assertEquals("1000000000000  0x000000E8D4A51000  (2001-09-09 01:46:40 UTC)",
                NumberFormatUtil.decorate("1000000000000", Format.TAG_LONG, 1_000_000_000_000L));
        // Exclusive high: no date.
        assertEquals("4000000000000  0x000003A352944000",
                NumberFormatUtil.decorate("4000000000000", Format.TAG_LONG, 4_000_000_000_000L));
    }

    @Test
    void nanosRangeBoundaries() {
        assertEquals("1000000000000000000  0x0DE0B6B3A7640000  (2001-09-09 01:46:40 UTC)",
                NumberFormatUtil.decorate("1000000000000000000", Format.TAG_LONG, 1_000_000_000_000_000_000L));
        // Exclusive high: no date.
        assertEquals("4000000000000000000  0x3782DACE9D900000",
                NumberFormatUtil.decorate("4000000000000000000", Format.TAG_LONG, 4_000_000_000_000_000_000L));
    }

    @Test
    void valueInNoRangeGetsHexButNoDate() {
        // Between the seconds and millis ranges — hex only, no date.
        assertEquals("5000000000  0x000000012A05F200",
                NumberFormatUtil.decorate("5000000000", Format.TAG_LONG, 5_000_000_000L));
        // A tiny value and a negative long: hex only.
        assertEquals("7  0x0000000000000007", NumberFormatUtil.decorate("7", Format.TAG_LONG, 7));
        assertEquals("-5  0xFFFFFFFFFFFFFFFB", NumberFormatUtil.decorate("-5", Format.TAG_LONG, -5));
    }
}
