// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

/**
 * Outcome of a navigation command (VIEWER.md §2.2). {@code moved} tells the TUI
 * whether the cursor actually changed (so it knows to refresh the view);
 * {@code message} is an optional human note for the status row — empty on a plain
 * success, or an explanation such as {@code "no earlier line in this thread"}
 * when a step could not move.
 *
 * @param moved whether the cursor changed position
 * @param message status text ({@code ""} when there is nothing to say)
 */
public record StepResult(boolean moved, String message) {}
