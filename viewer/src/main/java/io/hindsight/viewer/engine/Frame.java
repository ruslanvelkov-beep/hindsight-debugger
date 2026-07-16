// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

/**
 * One reconstructed call-stack frame at the current cursor (VIEWER.md §2.3).
 *
 * <p>{@code index} is the frame's position in the stack, {@code 0} being the
 * outermost caller and {@code depth-1} the innermost (the frame containing the
 * cursor line). {@code currentLine} is the source line the frame is "sitting on":
 * for the innermost frame that is the cursor line itself; for an outer frame it
 * is the <em>call-site</em> line — the last line executed in that frame before it
 * called into the frame below — which is what makes the Source view show each
 * caller paused at the call it made rather than at its own entry.
 *
 * @param index stack position, {@code 0} = outermost
 * @param enterIndex record index of this frame's {@code METHOD_ENTER}
 * @param methodSiteId the {@code METHOD} site id of the entered method
 * @param classBinary declaring class binary name, or {@code null} if the symbol is unknown
 * @param methodName bare method name, or {@code null} if unknown
 * @param descriptor JVM method descriptor (no {@code #s} suffix — that is {@code isStatic})
 * @param fileName source file name, or {@code null} if unknown
 * @param currentLine the source line this frame is currently on (call-site line for outer frames)
 * @param isStatic whether the method is static (determines whether {@code this} exists)
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
        boolean isStatic) {}
