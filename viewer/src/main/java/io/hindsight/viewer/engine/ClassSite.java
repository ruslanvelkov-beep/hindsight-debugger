// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.engine;

/**
 * One distinct runtime class the recording registered instances of, with how many
 * {@code OBJECT_FIRST_SEEN} records carried it (VIEWER.md §13.6). It backs the
 * "browse instances" mode of the {@code /} dialog: the caller filters the
 * {@link Engine#classSites()} list by each class's binary name and expands a
 * match into its individual instances via {@link Engine#instancesOf(String, int)}.
 *
 * <p>The name comes from the first-seen record's {@code RTCLASS} rt-symbol, so it
 * is the <em>runtime</em> class (which may be a subclass of any declared type and
 * may carry {@code $} nesting or array syntax).
 *
 * @param classBinary runtime class binary name, e.g. {@code com.ex.Foo$Inner}
 * @param instanceCount how many {@code OBJECT_FIRST_SEEN} records carry this class across all threads
 */
public record ClassSite(String classBinary, long instanceCount) {}
