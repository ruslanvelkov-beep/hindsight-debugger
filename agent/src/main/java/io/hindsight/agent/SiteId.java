// SPDX-License-Identifier: Apache-2.0
package io.hindsight.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@code int} advice parameter to be bound to the instrumented method's site id
 * (FORMAT.md §5) as an inlined integer constant.
 *
 * <p>Advice bytecode is copied into every instrumented method, so anything per-method must
 * arrive as a constant baked in at transform time — there is no instance state to read it
 * from. The binding lives in {@link SiteMappings.SiteIdFactory}, which resolves the id through
 * {@link SiteRegistry#methodSite} when ByteBuddy maps the advice onto a concrete method.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@interface SiteId {}
