// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.engine;

/**
 * One distinct {@code METHOD} site that the recording actually entered, with how
 * many times it was entered (VIEWER.md §11.7). It backs the regex "browse method
 * calls" dialog: the caller filters the {@link Engine#methodSites()} list by each
 * site's {@code Class.method} string and expands a match into its individual
 * invocations via {@link Engine#callsOf(int, int)}.
 *
 * <p>Names are resolved exactly as {@link Frame} and {@link RecordInfo} resolve
 * them, so {@code classBinary}/{@code methodName} may be {@code null} when the
 * recording's symbol table did not carry them.
 *
 * @param siteId the dense {@code METHOD} site id (matches a {@code METHOD_ENTER} record's location)
 * @param classBinary declaring class binary name, or {@code null} if the symbol is unknown
 * @param methodName bare method name, or {@code null} if unknown
 * @param descriptor JVM method descriptor (no {@code #s} suffix — that is carried separately)
 * @param invocationCount how many {@code METHOD_ENTER} records target this site across all threads
 */
public record MethodSite(
        int siteId,
        String classBinary,
        String methodName,
        String descriptor,
        long invocationCount) {}
