// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure §16.3 record-jump input logic: digit-only editing, error clearing on any edit,
 * and the overflow-safe parse.
 */
final class RecordJumpTest {

    @Test
    void appendsOnlyDigits() {
        RecordJump j = new RecordJump();
        j.appendDigit('1');
        j.appendDigit('a'); // dropped
        j.appendDigit('2');
        j.appendDigit(' '); // dropped
        j.appendDigit('3');
        assertEquals("123", j.text());
        assertFalse(j.isEmpty());
    }

    @Test
    void backspaceEditsAndBothEditsClearTheError() {
        RecordJump j = new RecordJump();
        j.appendDigit('4');
        j.appendDigit('2');
        j.setError("no record with seq 42");
        assertEquals("no record with seq 42", j.error());
        j.backspace();
        assertEquals("4", j.text());
        assertEquals("", j.error(), "backspace clears the error");
        j.setError("boom");
        j.appendDigit('7');
        assertEquals("47", j.text());
        assertEquals("", j.error(), "typing a digit clears the error");
    }

    @Test
    void backspaceOnEmptyIsHarmlessAndStillClearsError() {
        RecordJump j = new RecordJump();
        j.setError("x");
        j.backspace();
        assertTrue(j.isEmpty());
        assertEquals("", j.error());
    }

    @Test
    void setDigitsFillsTheBufferAndClearsTheError() {
        RecordJump j = new RecordJump();
        j.appendDigit('9');
        j.setError("boom");
        j.setDigits(4200); // §22.13: `l` fills the last record's seq
        assertEquals("4200", j.text());
        assertEquals("", j.error(), "filling clears the error");
        assertEquals(OptionalLong.of(4200L), j.parse());
        j.setDigits(-5); // defensive: a negative value fills 0
        assertEquals("0", j.text());
    }

    @Test
    void parseIsEmptyForNothingTypedOrOverflow() {
        RecordJump empty = new RecordJump();
        assertTrue(empty.parse().isEmpty());

        RecordJump good = new RecordJump();
        "9007199254740993".chars().forEach(c -> good.appendDigit((char) c));
        assertEquals(OptionalLong.of(9007199254740993L), good.parse());

        RecordJump overflow = new RecordJump();
        // 30 nines overflow a long -> empty rather than throwing out of the input loop.
        "999999999999999999999999999999".chars().forEach(c -> overflow.appendDigit((char) c));
        assertTrue(overflow.parse().isEmpty());
    }
}
