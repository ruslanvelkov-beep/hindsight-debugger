// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.engine;

/**
 * One reconstructed local variable (or method parameter, or the synthetic
 * {@code this}) of a frame, as-of the cursor (VIEWER.md §2.4).
 *
 * <p>Locals are keyed by JVM local-variable {@code slot}: parameters seed the
 * slots from the frame's {@code ARG} records (slot {@code 0} being {@code this}
 * for an instance method), then {@code LOCAL_WRITE} records overwrite slots as
 * execution proceeds, latest-wins. Because a single slot can be reused by
 * variables of disjoint scopes, {@code name}/{@code descriptor} reflect the most
 * recent write to that slot rather than a fixed declaration.
 *
 * @param slot JVM local-variable table slot
 * @param name variable name ({@code this}, a parameter name, or {@code argN} when unknown)
 * @param descriptor JVM type descriptor of the value's declared/seeded type ({@code ""} if unknown)
 * @param value the value held in this slot at the cursor
 */
public record Local(int slot, String name, String descriptor, Value value) {}
