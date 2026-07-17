// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.hindsight.viewer.engine.Frame;
import org.junit.jupiter.api.Test;

/**
 * Pins the frame position texts of the v1.5 chrome: the fully-qualified
 * {@code full.binary.Class.method} the status row shows while Stack is focused
 * (VIEWER.md §15.6) and the {@code SimpleClass.method} Source-title suffix (§15.7),
 * including the {@code ?} fallbacks and the unrecorded-frame case.
 */
final class FrameTextTest {

    private static Frame frame(final String cls, final String method, final boolean recorded) {
        return new Frame(0, recorded ? 3 : -1, 0, cls, method, "", "Foo.java", 12, false, recorded);
    }

    @Test
    void qualifiedUsesTheFullBinaryName() {
        assertEquals("com.ex.Outer$Inner.helper", FrameText.qualified(frame("com.ex.Outer$Inner", "helper", true)));
        assertEquals("com.ex.Foo.main", FrameText.qualified(frame("com.ex.Foo", "main", true)));
    }

    @Test
    void simpleUsesTheSimpleClassName() {
        assertEquals("Inner.helper", FrameText.simple(frame("com.ex.Outer$Inner", "helper", true)));
        assertEquals("Foo.main", FrameText.simple(frame("com.ex.Foo", "main", true)));
    }

    @Test
    void unknownSymbolsFallBackToQuestionMarks() {
        assertEquals("?.main", FrameText.qualified(frame(null, "main", true)));
        assertEquals("com.ex.Foo.?", FrameText.qualified(frame("com.ex.Foo", null, true)));
        assertEquals("?.?", FrameText.simple(frame(null, null, true)));
    }

    @Test
    void unrecordedGreyFramesRenderLikeRecordedOnes() {
        // §15.6 explicitly includes unrecorded (§14.5 capture-only) frames.
        assertEquals("com.ex.Foo.main", FrameText.qualified(frame("com.ex.Foo", "main", false)));
        assertEquals("Foo.main", FrameText.simple(frame("com.ex.Foo", "main", false)));
    }
}
