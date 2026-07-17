// SPDX-License-Identifier: Apache-2.0
package io.hindsight.reader;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code verify} subcommand's invariant checks (FORMAT.md §12), each
 * violation attributed to an exact file + record index:
 *
 * <ol>
 *   <li>per-thread {@code seq} strictly increasing;</li>
 *   <li>ENTER/EXIT balance per thread — truncated-tail tolerant (frames still
 *       open at end-of-file are legal, §2 ordering rules);</li>
 *   <li>every {@code location} / symbol id resolvable (sites for method, field
 *       and local records; symbol ids for OBJECT_FIRST_SEEN / THREAD_START;
 *       plus the ids a site row itself references);</li>
 *   <li>every blob offset lands on a {@code 0xB5} magic with a sane length —
 *       including SNAPSHOT payload structure and its nested UTF8 offsets, and
 *       (schema 4) STACK payload bounds plus frame-symbol resolvability;</li>
 *   <li>{@code OBJECT_FIRST_SEEN} precedes, by seq, every reference to that
 *       object id (checked over the global merge);</li>
 *   <li>aux tags and flags within spec (known type, known tag, flags only on
 *       the record types that define them).</li>
 * </ol>
 *
 * <p>Verification never stops at the first problem: a corrupted recording is
 * exactly when a complete damage report is most valuable.
 */
final class Verifier {

    private final Recording recording;
    private final List<String> violations = new ArrayList<>();

    private Verifier(final Recording recording) {
        this.recording = recording;
    }

    /** Runs all checks; returns formatted violations ({@code file[index]: message}). */
    static List<String> verify(final Recording recording) throws IOException {
        Verifier v = new Verifier(recording);
        for (int tid : recording.threadIds()) {
            v.verifyThread(tid);
        }
        v.verifyFirstSeenOrder();
        return v.violations;
    }

    private void violation(final Rec r, final String message) {
        violations.add(r.fileName() + "[" + r.index() + "]: " + message);
    }

    private void verifyThread(final int tid) throws IOException {
        long count = recording.recordCount(tid);
        long prevSeq = 0; // seq starts at 1 (§2), so 0 is a safe floor
        Deque<Rec> stack = new ArrayDeque<>();
        for (long i = 0; i < count; i++) {
            Rec r = recording.recordAt(tid, i);
            if (r.seq() <= prevSeq) {
                violation(r, "seq regression: " + r.seq() + " after " + prevSeq);
            }
            prevSeq = r.seq();
            checkTypeTagFlags(r);
            checkLocation(r);
            checkBlobs(r);
            switch (r.type()) {
                case Format.METHOD_ENTER -> stack.push(r);
                case Format.METHOD_EXIT -> {
                    if (stack.isEmpty()) {
                        violation(r, "METHOD_EXIT with no open METHOD_ENTER");
                    } else {
                        Rec enter = stack.pop();
                        if (enter.location() != r.location()) {
                            violation(r, "METHOD_EXIT site " + r.location()
                                    + " does not match open METHOD_ENTER site " + enter.location());
                        }
                    }
                }
                default -> {
                    // no balance bookkeeping
                }
            }
        }
        // frames still open at EOF are legal: live at shutdown / truncated tail (§2)
    }

    private void checkTypeTagFlags(final Rec r) {
        if (!Format.typeKnown(r.type())) {
            violation(r, "unknown record type 0x" + Integer.toHexString(r.type()));
            return;
        }
        if (!Format.tagKnown(r.tag())) {
            violation(r, "unknown value tag " + r.tag());
        }
        if (r.isExceptional() && r.type() != Format.METHOD_EXIT) {
            violation(r, "EXCEPTIONAL flag on " + Format.typeName(r.type()));
        }
        if (r.isStatic() && r.type() != Format.FIELD_WRITE) {
            violation(r, "STATIC flag on " + Format.typeName(r.type()));
        }
        if (r.type() == Format.SNAPSHOT && r.afterKind() != Format.KIND_BLOB) {
            violation(r, "SNAPSHOT record without AFTER_KIND=BLOB");
        }
        if (r.type() == Format.STACK_SNAPSHOT && r.afterKind() != Format.KIND_BLOB) {
            violation(r, "STACK_SNAPSHOT record without AFTER_KIND=BLOB");
        }
    }

    private void checkLocation(final Rec r) {
        switch (r.type()) {
            case Format.METHOD_ENTER, Format.METHOD_EXIT, Format.ARG ->
                    requireSite(r, Site.KIND_METHOD);
            case Format.FIELD_WRITE -> requireSite(r, Site.KIND_FIELD);
            case Format.LOCAL_WRITE -> requireSite(r, Site.KIND_LOCAL);
            case Format.LINE -> requireFlowSite(r, Site.KIND_LINE);
            case Format.BRANCH -> requireFlowSite(r, Site.KIND_BRANCH);
            case Format.OBJECT_FIRST_SEEN, Format.THREAD_START -> {
                if (recording.symbol(r.location()) == null) {
                    violation(r, Format.typeName(r.type()) + " references unknown symbol id "
                            + r.location());
                }
            }
            default -> {
                // SNAPSHOT / TIME_SYNC: location is 0 by spec; a nonzero value is noise,
                // not a resolvability failure — not flagged
            }
        }
    }

    private void requireSite(final Rec r, final String expectedKind) {
        Site s = recording.site(r.location());
        if (s == null) {
            violation(r, Format.typeName(r.type()) + " references unknown site id " + r.location());
            return;
        }
        if (!expectedKind.equals(s.kind())) {
            violation(r, Format.typeName(r.type()) + " references " + s.kind() + " site "
                    + r.location() + " (expected " + expectedKind + ")");
        }
        if (recording.symbol(s.classSym()) == null) {
            violation(r, "site " + s.id() + " references unknown class symbol " + s.classSym());
        }
        if (recording.symbol(s.memberSym()) == null) {
            violation(r, "site " + s.id() + " references unknown member symbol " + s.memberSym());
        }
        if (s.fileSym() != 0 && recording.symbol(s.fileSym()) == null) {
            violation(r, "site " + s.id() + " references unknown file symbol " + s.fileSym());
        }
    }

    /** Like {@link #requireSite} but for LINE/BRANCH sites, which carry no member symbol. */
    private void requireFlowSite(final Rec r, final String expectedKind) {
        Site s = recording.site(r.location());
        if (s == null) {
            violation(r, Format.typeName(r.type()) + " references unknown site id " + r.location());
            return;
        }
        if (!expectedKind.equals(s.kind())) {
            violation(r, Format.typeName(r.type()) + " references " + s.kind() + " site "
                    + r.location() + " (expected " + expectedKind + ")");
        }
        if (recording.symbol(s.classSym()) == null) {
            violation(r, "site " + s.id() + " references unknown class symbol " + s.classSym());
        }
        if (s.fileSym() != 0 && recording.symbol(s.fileSym()) == null) {
            violation(r, "site " + s.id() + " references unknown file symbol " + s.fileSym());
        }
    }

    private void checkBlobs(final Rec r) {
        if (r.beforeKind() == Format.KIND_BLOB) {
            checkBlobOffset(r, r.before());
        }
        if (r.afterKind() == Format.KIND_BLOB) {
            checkBlobOffset(r, r.after());
        }
    }

    private void checkBlobOffset(final Rec r, final long offset) {
        final Recording.BlobEntry entry;
        try {
            entry = recording.blobAt(r.tid(), offset);
        } catch (IOException e) {
            violation(r, e.getMessage());
            return;
        }
        if (r.type() == Format.SNAPSHOT) {
            if (entry.kind() != Format.BLOB_SNAPSHOT) {
                violation(r, "SNAPSHOT record points at blob kind " + entry.kind());
                return;
            }
            checkSnapshotPayload(r, entry.payload(), offset);
        }
        if (r.type() == Format.STACK_SNAPSHOT) {
            if (entry.kind() != Format.BLOB_STACK) {
                violation(r, "STACK_SNAPSHOT record points at blob kind " + entry.kind());
                return;
            }
            checkStackPayload(r, entry.payload());
        }
    }

    /**
     * §12 schema-4 STACK payload checks: the frame count must fit within the payload bounds,
     * and every frame's class/method/file symbol id must resolve ({@code fileSym} 0 = "no
     * source file" is allowed).
     */
    private void checkStackPayload(final Rec r, final byte[] payload) {
        final List<Recording.StackFrame> frames;
        try {
            frames = Recording.parseStack(payload);
        } catch (IOException e) {
            violation(r, e.getMessage());
            return;
        }
        for (Recording.StackFrame f : frames) {
            if (recording.symbol(f.classSym()) == null) {
                violation(r, "stack frame references unknown class symbol " + f.classSym());
            }
            if (recording.symbol(f.methodSym()) == null) {
                violation(r, "stack frame references unknown method symbol " + f.methodSym());
            }
            if (f.fileSym() != 0 && recording.symbol(f.fileSym()) == null) {
                violation(r, "stack frame references unknown file symbol " + f.fileSym());
            }
        }
    }

    private void checkSnapshotPayload(final Rec r, final byte[] payload, final long entryOffset) {
        final Recording.Snapshot snap;
        try {
            snap = Recording.parseSnapshot(payload, recording.schemaVersion());
        } catch (IOException e) {
            violation(r, e.getMessage());
            return;
        }
        if (recording.symbol(snap.classSym()) == null) {
            violation(r, "snapshot references unknown class symbol " + snap.classSym());
        }
        for (Recording.SnapField f : snap.fields()) {
            if (f.nameSym() >= 0 && recording.symbol(f.nameSym()) == null) {
                violation(r, "snapshot field references unknown name symbol " + f.nameSym());
            }
            if (!Format.tagKnown(f.tag())) {
                violation(r, "snapshot field has unknown tag " + f.tag());
            }
            if (f.kind() == Format.KIND_BLOB) {
                if (f.value() >= entryOffset) {
                    // §3: nested BLOB values point at a UTF8 entry written EARLIER in this file
                    violation(r, "snapshot nested blob offset " + f.value()
                            + " not earlier than snapshot entry at " + entryOffset);
                }
                try {
                    Recording.BlobEntry nested = recording.blobAt(r.tid(), f.value());
                    if (nested.kind() != Format.BLOB_UTF8) {
                        violation(r, "snapshot nested blob at " + f.value() + " has kind "
                                + nested.kind() + " (expected UTF8)");
                    }
                } catch (IOException e) {
                    violation(r, "snapshot nested blob: " + e.getMessage());
                }
            }
        }
    }

    /** Global pass: OBJECT_FIRST_SEEN must precede (by seq) every reference to its id. */
    private void verifyFirstSeenOrder() {
        Map<Long, Long> firstSeenSeq = new HashMap<>();
        RecordCursor cur = recording.cursor();
        while (cur.hasNext()) {
            Rec r = cur.next();
            if (r.type() == Format.OBJECT_FIRST_SEEN) {
                Long prev = firstSeenSeq.putIfAbsent(r.target(), r.seq());
                if (prev != null) {
                    violation(r, "duplicate OBJECT_FIRST_SEEN for object id " + r.target()
                            + " (first at seq " + prev + ")");
                }
                continue;
            }
            checkRef(r, firstSeenSeq, r.target(), "target");
            if (r.beforeKind() == Format.KIND_OBJECT_ID) {
                checkRef(r, firstSeenSeq, r.before(), "before slot");
            }
            if (r.afterKind() == Format.KIND_OBJECT_ID) {
                checkRef(r, firstSeenSeq, r.after(), "after slot");
            }
        }
    }

    private void checkRef(final Rec r, final Map<Long, Long> firstSeenSeq, final long id, final String where) {
        if (id == 0) {
            return; // null / not applicable
        }
        if (r.type() == Format.THREAD_START || r.type() == Format.TIME_SYNC) {
            return; // their target/after columns are not object ids
        }
        Long seen = firstSeenSeq.get(id);
        if (seen == null) {
            violation(r, where + " references object id " + id + " with no OBJECT_FIRST_SEEN");
        } else if (seen >= r.seq()) {
            violation(r, where + " references object id " + id + " first seen at seq " + seen
                    + " which is not before seq " + r.seq());
        }
    }
}
