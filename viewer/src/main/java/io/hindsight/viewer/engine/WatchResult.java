// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

/**
 * Result of resolving a watch expression as-of the current cursor (VIEWER.md §2.7).
 *
 * <p>Exactly one of {@code root}/{@code error} is meaningful: on success
 * {@code root} is the tree node the Watches window renders and {@code error} is
 * {@code null}; on failure {@code root} is {@code null} and {@code error} carries
 * the reason (unsupported grammar, or a missing field/object as-of this seq).
 * {@code expression} echoes the user's input verbatim so the window can title the
 * watch even when it does not resolve.
 *
 * @param expression the original watch text
 * @param root resolved tree root, or {@code null} on error
 * @param error failure text, or {@code null} on success
 */
public record WatchResult(String expression, ObjectNode root, String error) {}
