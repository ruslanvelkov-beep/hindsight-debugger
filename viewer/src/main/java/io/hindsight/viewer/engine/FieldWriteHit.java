// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.engine;

/**
 * One {@code FIELD_WRITE} matching a variable-history query (VIEWER.md §13.5/§13.6):
 * a write to field {@code fieldName} whose owner class belongs to the queried source
 * file. {@code before}/{@code after} are ready-to-display strings rendered exactly
 * as the reader dump renders a field write's old/new values — via the reader's
 * {@code ValueRenderer}, honouring the FORMAT.md §2.3 per-slot <em>runtime</em> tags
 * of reference-typed fields and resolving blob strings.
 *
 * @param seq global sequence number of the {@code FIELD_WRITE} record
 * @param tid thread id the write occurred on
 * @param index record index of the {@code FIELD_WRITE} within that thread
 * @param ownerId owner object id, {@code 0} for static fields
 * @param ownerClass owner class binary name (may carry {@code $} nesting), or {@code null} if unknown
 * @param fieldName bare field name (the queried name)
 * @param isStatic whether the field is static (site {@code #s} suffix or record flag)
 * @param before rendered old value
 * @param after rendered new value
 * @param fileName source file name from the write site, or {@code null} if unknown
 * @param line source line of the write, from the write site
 */
public record FieldWriteHit(
        long seq,
        int tid,
        long index,
        long ownerId,
        String ownerClass,
        String fieldName,
        boolean isStatic,
        String before,
        String after,
        String fileName,
        int line) {}
