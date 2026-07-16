// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.hindsight.viewer.highlight.JavaHighlighter;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests tab expansion and styled-cell layout for the Source window (VIEWER.md §6.1). */
final class SourceLayoutTest {

    @Test
    void expandTabsToNextStop() {
        assertEquals("    ab", SourceLayout.expandTabs("\tab", 4));
        assertEquals("a   b", SourceLayout.expandTabs("a\tb", 4));
        assertEquals("ab  cd", SourceLayout.expandTabs("ab\tcd", 4));
    }

    @Test
    void cellsReconstructLineAndCarryStyles() {
        JavaHighlighter hl = new JavaHighlighter();
        String line = "int x";
        List<SourceLayout.Cell> cells = SourceLayout.cells(line, hl.highlight(line), 4);
        assertEquals(5, cells.size());
        StringBuilder text = new StringBuilder();
        for (SourceLayout.Cell c : cells) {
            text.append(c.ch());
        }
        assertEquals(line, text.toString());
        assertEquals(JavaHighlighter.Style.KEYWORD, cells.get(0).style());
    }

    @Test
    void tabExpandsToStyledSpaces() {
        JavaHighlighter hl = new JavaHighlighter();
        String line = "\tx";
        List<SourceLayout.Cell> cells = SourceLayout.cells(line, hl.highlight(line), 4);
        assertEquals(5, cells.size()); // 4 filler spaces + 'x'
        for (int i = 0; i < 4; i++) {
            assertEquals(' ', cells.get(i).ch());
        }
        assertEquals('x', cells.get(4).ch());
    }
}
