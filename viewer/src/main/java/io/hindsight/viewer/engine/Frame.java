// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.engine;

/**
 * One call-stack frame at the current cursor (VIEWER.md §2.3, §14.5).
 *
 * <p>{@code index} is the frame's position in the stack, {@code 0} being the
 * outermost caller and {@code depth-1} the innermost (the frame containing the
 * cursor line). {@code currentLine} is the source line the frame is "sitting on":
 * for the innermost frame that is the cursor line itself; for an outer frame it
 * is the <em>call-site</em> line — the last line executed in that frame before it
 * called into the frame below — which is what makes the Source view show each
 * caller paused at the call it made rather than at its own entry.
 *
 * <p>{@code recorded} distinguishes frames reconstructed from {@code METHOD_ENTER}
 * brackets ({@code true}) from unrecorded caller context merged in from the
 * thread's nearest {@code STACK_SNAPSHOT} capture (§14.5, schema 4). An
 * unrecorded frame carries {@code classBinary}/{@code methodName}/{@code fileName}
 * (may be {@code null}) and {@code currentLine} from the capture, with
 * {@code enterIndex = -1}, {@code methodSiteId = 0}, {@code descriptor = ""} and
 * {@code isStatic = false}; it has no locals. Unrecorded frames always precede
 * the recorded frames, so the innermost recorded frame stays last.
 *
 * @param index stack position, {@code 0} = outermost
 * @param enterIndex record index of this frame's {@code METHOD_ENTER}, or {@code -1} if unrecorded
 * @param methodSiteId the {@code METHOD} site id of the entered method, or {@code 0} if unrecorded
 * @param classBinary declaring class binary name, or {@code null} if the symbol is unknown
 * @param methodName bare method name, or {@code null} if unknown
 * @param descriptor JVM method descriptor (no {@code #s} suffix — that is {@code isStatic})
 * @param fileName source file name, or {@code null} if unknown
 * @param currentLine the source line this frame is currently on (call-site line for outer frames;
 *     the capture line for unrecorded frames)
 * @param isStatic whether the method is static (determines whether {@code this} exists)
 * @param recorded whether the frame was reconstructed from recorded brackets (§14.5)
 */
public record Frame(
        int index,
        int enterIndex,
        int methodSiteId,
        String classBinary,
        String methodName,
        String descriptor,
        String fileName,
        int currentLine,
        boolean isStatic,
        boolean recorded) {}
