// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import io.hindsight.reader.Format;
import io.hindsight.viewer.engine.Frame;

/**
 * Pure text renderings of a stack {@link Frame}'s position for the v1.5 chrome:
 * the fully-qualified {@code full.binary.Class.method} the status row shows while
 * the Stack window is focused (VIEWER.md §15.6) and the compact
 * {@code SimpleClass.method} suffix of the exec-mode Source title (§15.7).
 * Unknown symbols render as {@code ?}, matching the windows' row text; unrecorded
 * (grey, §14.5) frames are rendered exactly like recorded ones.
 */
public final class FrameText {

    private FrameText() {}

    /** §15.6: {@code full.binary.Class.method} of a frame; unknown symbols as {@code ?}. */
    public static String qualified(final Frame f) {
        String cls = f.classBinary() != null ? f.classBinary() : "?";
        return cls + "." + methodOf(f);
    }

    /** §15.7: {@code SimpleClass.method} of a frame; unknown symbols as {@code ?}. */
    public static String simple(final Frame f) {
        String cls = f.classBinary() != null ? Format.simpleName(f.classBinary()) : "?";
        return cls + "." + methodOf(f);
    }

    private static String methodOf(final Frame f) {
        return f.methodName() != null ? f.methodName() : "?";
    }
}
