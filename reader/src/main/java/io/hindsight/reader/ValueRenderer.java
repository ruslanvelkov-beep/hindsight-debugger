// SPDX-License-Identifier: Apache-2.0
package io.hindsight.reader;

import java.io.IOException;
import java.util.List;

/**
 * Renders encoded value slots to display text per FORMAT.md §12: INLINE values
 * by tag ({@code true} / {@code 'c'} / {@code 3.14}), BLOB strings quoted (with
 * a trailing {@code …} inside the quotes when the record's TRUNCATED flag is
 * set), object ids as {@code SimpleClassName#id}, {@code null} and {@code void}.
 *
 * <p>BOXED values arrive INLINE with their primitive tag (§2.4 rule 4) and render
 * exactly like the primitive — the flag records provenance, not a different value.
 *
 * <p>Rendering never throws: a dangling blob offset or unknown class renders as
 * a diagnostic placeholder, because {@code dump} must stay usable on the very
 * recordings {@code verify} would reject.
 */
public final class ValueRenderer {

    private final Recording recording;

    /** Creates a renderer bound to one recording (for symbols, blobs, object classes). */
    public ValueRenderer(final Recording recording) {
        this.recording = recording;
    }

    /**
     * Renders one of a record's two value slots.
     *
     * @param r the record supplying tag, flags and thread (for blob lookup)
     * @param beforeSlot {@code true} → the {@code before} slot, else {@code after}
     */
    public String render(final Rec r, final boolean beforeSlot) {
        int kind = beforeSlot ? r.beforeKind() : r.afterKind();
        long slot = beforeSlot ? r.before() : r.after();
        // §2.3: a reference-typed FIELD_WRITE carries per-slot runtime tags — the before
        // slot's in the aux idx byte (0 = TAG_REF, whose rendering never consults the tag),
        // the after slot's in the record's tag byte.
        int tag = (beforeSlot && r.type() == Format.FIELD_WRITE && r.idx() != 0) ? r.idx() : r.tag();
        return render(kind, tag, slot, r.tid(), r.isTruncated());
    }

    /**
     * Renders an encoded {@code (kind, tag, slot)} triple. {@code tid} locates the
     * blob file for BLOB kinds; {@code truncated} appends the ellipsis to strings.
     */
    public String render(final int kind, final int tag, final long slot, final int tid, final boolean truncated) {
        return switch (kind) {
            case Format.KIND_NONE -> switch (tag) {
                case Format.TAG_NULL -> "null";
                case Format.TAG_VOID -> "void";
                default -> "-";
            };
            case Format.KIND_INLINE -> renderInline(tag, slot);
            case Format.KIND_OBJECT_ID -> renderObjectId(slot);
            case Format.KIND_BLOB -> renderBlob(tag, slot, tid, truncated);
            default -> "<bad kind " + kind + ">";
        };
    }

    private static String renderInline(final int tag, final long slot) {
        return switch (tag) {
            case Format.TAG_INT, Format.TAG_LONG, Format.TAG_BYTE, Format.TAG_SHORT -> Long.toString(slot);
            case Format.TAG_BOOLEAN -> slot != 0 ? "true" : "false";
            case Format.TAG_CHAR -> "'" + (char) slot + "'";
            case Format.TAG_FLOAT -> Float.toString(Float.intBitsToFloat((int) slot));
            case Format.TAG_DOUBLE -> Double.toString(Double.longBitsToDouble(slot));
            case Format.TAG_NULL -> "null";
            case Format.TAG_VOID -> "void";
            default -> "<inline tag " + tag + ": 0x" + Long.toHexString(slot) + ">";
        };
    }

    /**
     * {@code SimpleClassName#id}; class from OBJECT_FIRST_SEEN, {@code ?} if unknown.
     * Enums render as their constant ({@code ResponseCodeEnum.OK}) — the constant IS the
     * identity of a singleton; the numeric id would be noise.
     */
    public String renderObjectId(final long objectId) {
        if (objectId == 0) {
            return "null";
        }
        String cls = recording.objectClassName(objectId);
        String display = recording.objectDisplayName(objectId);
        if (display != null) {
            return (cls == null ? "?" : Format.simpleName(cls)) + "." + display;
        }
        return (cls == null ? "?" : Format.simpleName(cls)) + "#" + objectId;
    }

    private String renderBlob(final int tag, final long offset, final int tid, final boolean truncated) {
        if (tag == Format.TAG_STRING || tag == Format.TAG_REF) {
            // REF-tagged blob happens for a String-valued write of a REF-declared field (§2.3)
            try {
                String s = recording.utf8At(tid, offset);
                return "\"" + s + (truncated ? "…" : "") + "\"";
            } catch (IOException e) {
                return "<bad blob @" + offset + ": " + e.getMessage() + ">";
            }
        }
        return "<blob @" + offset + ">";
    }

    /**
     * Renders a SNAPSHOT record's payload per its §3 form: fields as
     * {@code {field=value, …}}, maps as {@code {K -> V, …}}, sets as {@code {V1, V2, …}},
     * lists as {@code (e1, e2, …)}, arrays as {@code [v1, v2, …]}. A container larger than
     * the captured entry cap appends {@code …+N more}. Nested strings carry no per-field
     * truncation flag (§3), so no ellipsis inside values.
     */
    public String renderSnapshot(final Rec r) {
        StringBuilder b = new StringBuilder();
        b.append(renderObjectId(r.target())).append(' ');
        try {
            Recording.Snapshot snap = recording.snapshotAt(r.tid(), r.after());
            final char open;
            final char close;
            switch (snap.form()) {
                case Format.FORM_LIST -> {
                    open = '(';
                    close = ')';
                }
                case Format.FORM_ARRAY -> {
                    open = '[';
                    close = ']';
                }
                default -> {
                    open = '{';
                    close = '}';
                }
            }
            b.append(open);
            List<Recording.SnapField> entries = snap.fields();
            long rendered;
            if (snap.form() == Format.FORM_FIELDS) {
                for (int i = 0; i < entries.size(); i++) {
                    appendSep(b, i > 0);
                    Recording.SnapField f = entries.get(i);
                    String name = recording.symbolText(f.nameSym());
                    b.append(name == null ? "?sym" + f.nameSym() : name)
                            .append('=')
                            .append(render(f.kind(), f.tag(), f.value(), r.tid(), false));
                }
                rendered = entries.size();
            } else if (snap.form() == Format.FORM_MAP) {
                for (int i = 0; i + 1 < entries.size(); i += 2) {
                    appendSep(b, i > 0);
                    b.append(render(entries.get(i).kind(), entries.get(i).tag(), entries.get(i).value(), r.tid(), false))
                            .append(" -> ")
                            .append(render(
                                    entries.get(i + 1).kind(),
                                    entries.get(i + 1).tag(),
                                    entries.get(i + 1).value(),
                                    r.tid(),
                                    false));
                }
                rendered = entries.size() / 2;
            } else {
                for (int i = 0; i < entries.size(); i++) {
                    appendSep(b, i > 0);
                    Recording.SnapField f = entries.get(i);
                    b.append(render(f.kind(), f.tag(), f.value(), r.tid(), false));
                }
                rendered = entries.size();
            }
            if (snap.totalSize() > rendered) {
                appendSep(b, rendered > 0);
                b.append("…+").append(snap.totalSize() - rendered).append(" more");
            }
            b.append(close);
        } catch (IOException e) {
            b.append("<bad snapshot: ").append(e.getMessage()).append('>');
        }
        return b.toString();
    }

    private static void appendSep(final StringBuilder b, final boolean needed) {
        if (needed) {
            b.append(", ");
        }
    }
}
