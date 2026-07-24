// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests the §17.6 recording-name abbreviation and the popup's path wrapping. */
final class RecordingNameTest {

    @Test
    void shortNamesPassThroughUnchanged() {
        assertEquals("rec-2024", RecordingName.abbreviate("rec-2024", 21));
        assertEquals("exactly-21-chars-long", RecordingName.abbreviate("exactly-21-chars-long", 21));
        assertEquals(21, "exactly-21-chars-long".length());
        assertEquals("", RecordingName.abbreviate("", 21));
        assertEquals("", RecordingName.abbreviate(null, 21));
    }

    @Test
    void longNamesKeepFirstTenAndLastTenAroundAnEllipsis() {
        // §17.6: > 21 chars → first 10 + '…' + last 10 (21 total).
        String name = "recording-2024-07-13-crypto-transfer";
        String out = RecordingName.abbreviate(name, 21);
        assertEquals(21, out.length());
        assertEquals("recording-…o-transfer", out);
        // A 22-char name (one over) abbreviates too.
        String over = "abcdefghijklmnopqrstuv";
        assertEquals(22, over.length());
        assertEquals("abcdefghij…mnopqrstuv", RecordingName.abbreviate(over, 21));
    }

    @Test
    void smallBudgetsDegradeGracefully() {
        assertEquals("", RecordingName.abbreviate("whatever", 0));
        assertEquals("…", RecordingName.abbreviate("whatever", 1));
        assertEquals("…r", RecordingName.abbreviate("whatever", 2));
        assertEquals("w…er", RecordingName.abbreviate("whatever", 4));
        assertEquals("ab", RecordingName.abbreviate("ab", 2));
    }

    @Test
    void wrapSplitsIntoWidthSizedLines() {
        assertEquals(List.of("abcd", "efgh", "ij"), RecordingName.wrap("abcdefghij", 4));
        assertEquals(List.of("abcd", "efgh"), RecordingName.wrap("abcdefgh", 4));
        assertEquals(List.of("abc"), RecordingName.wrap("abc", 10));
    }

    @Test
    void wrapAlwaysYieldsAtLeastOneLineAndToleratesBadWidths() {
        assertEquals(List.of(""), RecordingName.wrap("", 10));
        assertEquals(List.of(""), RecordingName.wrap(null, 10));
        assertEquals(List.of("a", "b"), RecordingName.wrap("ab", 0), "width < 1 is treated as 1");
    }
}
