// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hindsight.reader.Format;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure §20.5/§26.4/§26.5 numeric decoration helper. {@code decorate} is fully
 * context-free: the §20.5 hex (width per type, two's-complement for negatives), the §26.5 byte
 * char-representation and char decimal+hex forms, and the §26.4 value-range-gated date — three
 * non-overlapping epoch ranges (seconds/millis/nanos) whose datings all fall between 2000-01-01
 * and 2100-01-01, applied to ints, longs, floats and doubles alike. The §21.1/§22.1 name gate is
 * retired: no variable name is consulted anywhere.
 */
final class NumberFormatUtilTest {

    @Test
    void hexWidthPerIntegralType() {
        assertEquals("42  0x2A  '*'", NumberFormatUtil.decorate("42", Format.TAG_BYTE, 42));
        assertEquals("42  0x002A", NumberFormatUtil.decorate("42", Format.TAG_SHORT, 42));
        assertEquals("42  0x0000002A", NumberFormatUtil.decorate("42", Format.TAG_INT, 42));
        assertEquals("42  0x000000000000002A", NumberFormatUtil.decorate("42", Format.TAG_LONG, 42));
    }

    @Test
    void byteAddsCharRepresentationOnlyWhenDisplayable() {
        // §26.5: displayable bytes (32..126) show their character after the hex.
        assertEquals("72  0x48  'H'", NumberFormatUtil.decorate("72", Format.TAG_BYTE, 72));
        assertEquals("32  0x20  ' '", NumberFormatUtil.decorate("32", Format.TAG_BYTE, 32));
        assertEquals("126  0x7E  '~'", NumberFormatUtil.decorate("126", Format.TAG_BYTE, 126));
        // Control chars, DEL and negatives stay hex-only.
        assertEquals("31  0x1F", NumberFormatUtil.decorate("31", Format.TAG_BYTE, 31));
        assertEquals("127  0x7F", NumberFormatUtil.decorate("127", Format.TAG_BYTE, 127));
        assertEquals("-1  0xFF", NumberFormatUtil.decorate("-1", Format.TAG_BYTE, -1));
    }

    @Test
    void charShowsDecimalPaddedToFiveThenHex() {
        // §26.5: 'c', two spaces, 5-wide left-padded decimal code, two spaces, hex.
        assertEquals("'A'     65  0x0041", NumberFormatUtil.decorate("'A'", Format.TAG_CHAR, 'A'));
        assertEquals("'x'  65535  0xFFFF", NumberFormatUtil.decorate("'x'", Format.TAG_CHAR, 0xFFFF));
        assertEquals("' '     32  0x0020", NumberFormatUtil.decorate("' '", Format.TAG_CHAR, 32));
    }

    @Test
    void negativesUseTwosComplementHexAtEachWidth() {
        assertEquals("-1  0xFF", NumberFormatUtil.decorate("-1", Format.TAG_BYTE, -1));
        assertEquals("-1  0xFFFF", NumberFormatUtil.decorate("-1", Format.TAG_SHORT, -1));
        assertEquals("-1  0xFFFFFFFF", NumberFormatUtil.decorate("-1", Format.TAG_INT, -1));
        assertEquals("-1  0xFFFFFFFFFFFFFFFF", NumberFormatUtil.decorate("-1", Format.TAG_LONG, -1));
    }

    @Test
    void booleanIsUndecoratedAndOutOfRangeFloatsKeepPlainText() {
        assertEquals("true", NumberFormatUtil.decorate("true", Format.TAG_BOOLEAN, 1));
        assertEquals("false", NumberFormatUtil.decorate("false", Format.TAG_BOOLEAN, 0));
        assertEquals("3.14", NumberFormatUtil.decorate("3.14", Format.TAG_FLOAT,
                Float.floatToIntBits(3.14f) & 0xFFFFFFFFL));
        assertEquals("3.14", NumberFormatUtil.decorate("3.14", Format.TAG_DOUBLE,
                Double.doubleToLongBits(3.14)));
    }

    @Test
    void decorateDatesInRangeIntsAndLongsWithoutAnyName() {
        // §26.4: purely value-range-gated — every in-range value is dated, no name needed.
        assertEquals("1000000000  0x3B9ACA00  (2001-09-09 01:46:40 UTC)",
                NumberFormatUtil.decorate("1000000000", Format.TAG_INT, 1_000_000_000L));
        assertEquals("946684800000  0x000000DC6ACFAC00  (2000-01-01 00:00:00 UTC)",
                NumberFormatUtil.decorate("946684800000", Format.TAG_LONG, 946_684_800_000L));
        assertEquals("946684800000000000  0x0D234CCF52430000  (2000-01-01 00:00:00 UTC)",
                NumberFormatUtil.decorate("946684800000000000", Format.TAG_LONG, 946_684_800_000_000_000L));
        // Between-ranges, tiny and negative values: hex only.
        assertEquals("5000000000  0x000000012A05F200",
                NumberFormatUtil.decorate("5000000000", Format.TAG_LONG, 5_000_000_000L));
        assertEquals("7  0x0000000000000007", NumberFormatUtil.decorate("7", Format.TAG_LONG, 7));
        assertEquals("-5  0xFFFFFFFFFFFFFFFB", NumberFormatUtil.decorate("-5", Format.TAG_LONG, -5));
    }

    @Test
    void dateRangesSpan2000To2100Exclusive() {
        // Seconds range boundaries.
        assertEquals("  (2000-01-01 00:00:00 UTC)", NumberFormatUtil.date(946_684_800L));
        assertEquals("", NumberFormatUtil.date(946_684_799L));
        assertEquals("  (2099-12-31 23:59:59 UTC)", NumberFormatUtil.date(4_102_444_799L));
        assertEquals("", NumberFormatUtil.date(4_102_444_800L));
        // Millis and nanos lows land on the same 2000-01-01 instant; highs are exclusive.
        assertEquals("  (2000-01-01 00:00:00 UTC)", NumberFormatUtil.date(946_684_800_000L));
        assertEquals("", NumberFormatUtil.date(4_102_444_800_000L));
        assertEquals("  (2000-01-01 00:00:00 UTC)", NumberFormatUtil.date(946_684_800_000_000_000L));
        assertEquals("", NumberFormatUtil.date(4_102_444_800_000_000_000L));
        // Between ranges / negative: no date.
        assertEquals("", NumberFormatUtil.date(5_000_000_000L));
        assertEquals("", NumberFormatUtil.date(-1_700_000_000L));
    }

    @Test
    void floatingPointValuesDateByTheSameRanges() {
        // §26.4: floats/doubles date too — seconds, millis and nanos magnitudes.
        assertEquals("  (2023-11-14 22:13:20 UTC)", NumberFormatUtil.date(1.7e9d));
        assertEquals("  (2023-11-14 22:13:20 UTC)", NumberFormatUtil.date(1.7e12d));
        assertEquals("  (2023-11-14 22:13:20 UTC)", NumberFormatUtil.date(1.7e18d));
        // The suffix rides on decorate for TAG_FLOAT/TAG_DOUBLE.
        long bits17e9f = Float.floatToIntBits(1.7e9f) & 0xFFFFFFFFL;
        assertEquals("1.7E9  (2023-11-14 22:13:20 UTC)",
                NumberFormatUtil.decorate("1.7E9", Format.TAG_FLOAT, bits17e9f));
        assertEquals("1.7E12  (2023-11-14 22:13:20 UTC)",
                NumberFormatUtil.decorate("1.7E12", Format.TAG_DOUBLE, Double.doubleToLongBits(1.7e12)));
        // Out of range, negative, NaN and infinities: no date.
        assertEquals("", NumberFormatUtil.date(1.0d));
        assertEquals("", NumberFormatUtil.date(-1.7e9d));
        assertEquals("", NumberFormatUtil.date(Double.NaN));
        assertEquals("", NumberFormatUtil.date(Double.POSITIVE_INFINITY));
        assertEquals("", NumberFormatUtil.date(5e9d)); // between seconds and millis ranges
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
        // §26.4: a date suffix after the hex — or after a float — does not defeat the alignment.
        assertEquals(List.of("         7  0x0000000000000007",
                        "1700000000  0x0000000065534740  (2023-11-14 22:13:20 UTC)"),
                NumberFormatUtil.alignNumericElements(List.of("7  0x0000000000000007",
                        "1700000000  0x0000000065534740  (2023-11-14 22:13:20 UTC)")));
        // Floats align on the INTEGER part; a dated float still classifies as float (equal
        // integer-part widths here, so the texts pass through unchanged rather than degrading
        // to the no-alignment mixed case).
        assertEquals(List.of("1.5", "1.7E9  (2023-11-14 22:13:20 UTC)"),
                NumberFormatUtil.alignNumericElements(List.of("1.5", "1.7E9  (2023-11-14 22:13:20 UTC)")));
        assertEquals(List.of(" 1.5", "22.25  (…ignored)"),
                NumberFormatUtil.alignNumericElements(List.of("1.5", "22.25  (…ignored)")));
    }
}
