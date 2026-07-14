// SPDX-License-Identifier: Apache-2.0
package io.hindsight.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@code int} advice parameter to be bound to the instrumented method's return-shape
 * tag per FORMAT.md §2.3: {@code VOID} (12) for void methods, a primitive tag when the exit
 * advice boxes a primitive return value, {@code REF} (0) otherwise.
 *
 * <p>The exit hook receives the return value as an {@code Object}; without this tag the
 * runtime could not tell a genuinely boxed {@code Integer} return from an {@code int} return
 * the instrumentation had to box for the hook's calling convention (§2.4 records them
 * differently — the latter must not carry the BOXED flag). Bound by
 * {@link SiteMappings.ReturnTagFactory} as an inlined constant, like {@link SiteId}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@interface ReturnTag {}
