// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the §22.4 caret field logic ({@link FieldEdit}): mid-string insert/backspace at the caret,
 * clamped caret motion, the start/end edge predicates the {@code /} and {@code V} windows use to
 * cross between their two fields, and the compact constructor's null/clamp normalisation.
 */
final class FieldEditTest {

    @Test
    void constructorNormalisesNullAndClampsCaret() {
        assertEquals("", new FieldEdit(null, 3).text());
        assertEquals(0, new FieldEdit(null, 3).caret());
        assertEquals(3, new FieldEdit("abc", 99).caret()); // clamped to length
        assertEquals(0, new FieldEdit("abc", -5).caret()); // clamped to 0
    }

    @Test
    void insertHappensAtTheCaretAndAdvancesIt() {
        FieldEdit fe = new FieldEdit("ac", 1).insert('b'); // caret between a and c
        assertEquals("abc", fe.text());
        assertEquals(2, fe.caret());
        // Appending at the end.
        FieldEdit end = new FieldEdit("ab", 2).insert('c');
        assertEquals("abc", end.text());
        assertEquals(3, end.caret());
        // Inserting at the start.
        FieldEdit start = new FieldEdit("bc", 0).insert('a');
        assertEquals("abc", start.text());
        assertEquals(1, start.caret());
    }

    @Test
    void backspaceDeletesBeforeTheCaretAndIsNoOpAtStart() {
        FieldEdit fe = new FieldEdit("abc", 2).backspace(); // deletes 'b'
        assertEquals("ac", fe.text());
        assertEquals(1, fe.caret());
        FieldEdit atStart = new FieldEdit("abc", 0).backspace();
        assertEquals("abc", atStart.text());
        assertEquals(0, atStart.caret());
    }

    @Test
    void caretMovesAreClampedToTheEnds() {
        assertEquals(0, new FieldEdit("abc", 0).left().caret());
        assertEquals(1, new FieldEdit("abc", 2).left().caret());
        assertEquals(3, new FieldEdit("abc", 3).right().caret());
        assertEquals(2, new FieldEdit("abc", 1).right().caret());
        // Motion never changes the text.
        assertEquals("abc", new FieldEdit("abc", 1).left().text());
        assertEquals("abc", new FieldEdit("abc", 1).right().text());
    }

    @Test
    void edgePredicatesDriveFieldCrossing() {
        assertTrue(new FieldEdit("abc", 0).atStart());
        assertFalse(new FieldEdit("abc", 1).atStart());
        assertTrue(new FieldEdit("abc", 3).atEnd());
        assertFalse(new FieldEdit("abc", 2).atEnd());
        // An empty field is simultaneously at the start and the end.
        assertTrue(new FieldEdit("", 0).atStart());
        assertTrue(new FieldEdit("", 0).atEnd());
    }
}
