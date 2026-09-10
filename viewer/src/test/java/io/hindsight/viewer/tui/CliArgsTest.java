// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Tests the CLI grammar of VIEWER.md §1 (valid parses and every error case). */
final class CliArgsTest {

    @Test
    void parsesEverything() {
        CliArgs a = CliArgs.parse(new String[] {
            "--dir", "/rec", "--source", "/a:/b", "--repo", "/r1", "--repo", "/r2", "--tid", "3"
        });
        assertEquals(Path.of("/rec"), a.dir());
        assertEquals(List.of(Path.of("/a"), Path.of("/b")), a.sources());
        assertEquals(List.of(Path.of("/r1"), Path.of("/r2")), a.repos());
        assertEquals(OptionalInt.of(3), a.tid());
    }

    @Test
    void multipleSourceFlagsAccumulate() {
        CliArgs a = CliArgs.parse(new String[] {"--dir", "/rec", "--source", "/a", "--source", "/b:/c"});
        assertEquals(List.of(Path.of("/a"), Path.of("/b"), Path.of("/c")), a.sources());
    }

    @Test
    void emptyColonSegmentsSkipped() {
        CliArgs a = CliArgs.parse(new String[] {"--dir", "/rec", "--source", "/a::/b:"});
        assertEquals(List.of(Path.of("/a"), Path.of("/b")), a.sources());
    }

    @Test
    void defaultsWhenOnlyDir() {
        CliArgs a = CliArgs.parse(new String[] {"--dir", "/rec"});
        assertTrue(a.sources().isEmpty());
        assertTrue(a.repos().isEmpty());
        assertTrue(a.tid().isEmpty());
        assertTrue(a.seq().isEmpty());
        assertTrue(!a.last());
        assertTrue(a.seqs().isEmpty());
    }

    // ---- §26.7 opening-record flags ----

    @Test
    void seqAndLastParse() {
        CliArgs s = CliArgs.parse(new String[] {"--dir", "/rec", "--seq", "2034516"});
        assertEquals(2034516L, s.seq().orElseThrow());
        assertTrue(!s.last());
        CliArgs l = CliArgs.parse(new String[] {"--dir", "/rec", "--last"});
        assertTrue(l.last());
        assertTrue(l.seq().isEmpty());
    }

    @Test
    void nonIntegerSeqFails() {
        CliArgs.CliException e = assertThrows(CliArgs.CliException.class,
                () -> CliArgs.parse(new String[] {"--dir", "/rec", "--seq", "abc"}));
        assertTrue(e.getMessage().contains("--seq"));
    }

    @Test
    void seqAndLastAreMutuallyExclusive() {
        CliArgs.CliException e = assertThrows(CliArgs.CliException.class,
                () -> CliArgs.parse(new String[] {"--dir", "/rec", "--seq", "5", "--last"}));
        assertTrue(e.getMessage().contains("mutually exclusive"));
    }

    @Test
    void missingDirFails() {
        CliArgs.CliException e = assertThrows(CliArgs.CliException.class,
                () -> CliArgs.parse(new String[] {"--source", "/a"}));
        assertTrue(e.getMessage().contains("--dir"));
    }

    @Test
    void unknownFlagFails() {
        CliArgs.CliException e = assertThrows(CliArgs.CliException.class,
                () -> CliArgs.parse(new String[] {"--dir", "/rec", "--bogus"}));
        assertTrue(e.getMessage().contains("--bogus"));
    }

    @Test
    void nonIntegerTidFails() {
        CliArgs.CliException e = assertThrows(CliArgs.CliException.class,
                () -> CliArgs.parse(new String[] {"--dir", "/rec", "--tid", "x"}));
        assertTrue(e.getMessage().contains("--tid"));
    }

    @Test
    void missingValueFails() {
        CliArgs.CliException e = assertThrows(CliArgs.CliException.class,
                () -> CliArgs.parse(new String[] {"--dir"}));
        assertTrue(e.getMessage().contains("--dir"));
    }

    // ---- §29.1 pre-populated Records (--seqs) ----

    @Test
    void seqsParseInOrderAndAccumulate() {
        CliArgs a = CliArgs.parse(new String[] {"--dir", "/rec", "--seqs", "40,12, 981", "--seqs", "7"});
        assertEquals(List.of(40L, 12L, 981L, 7L), a.seqs());
        assertTrue(a.seq().isEmpty());
        assertTrue(!a.last());
    }

    @Test
    void seqsSkipBlankItems() {
        assertEquals(List.of(3L, 4L), CliArgs.parseSeqList("3,,4,"));
    }

    @Test
    void seqsWithNoNumbersFails() {
        CliArgs.CliException e = assertThrows(CliArgs.CliException.class, () -> CliArgs.parseSeqList(","));
        assertTrue(e.getMessage().contains("--seqs"));
    }

    @Test
    void nonIntegerSeqsFails() {
        CliArgs.CliException e = assertThrows(CliArgs.CliException.class,
                () -> CliArgs.parse(new String[] {"--dir", "/rec", "--seqs", "5,abc"}));
        assertTrue(e.getMessage().contains("--seqs"));
        assertTrue(e.getMessage().contains("abc"));
    }

    @Test
    void seqsExclusiveWithSeqAndLast() {
        CliArgs.CliException e1 = assertThrows(CliArgs.CliException.class,
                () -> CliArgs.parse(new String[] {"--dir", "/rec", "--seqs", "5", "--seq", "9"}));
        assertTrue(e1.getMessage().contains("mutually exclusive"));
        CliArgs.CliException e2 = assertThrows(CliArgs.CliException.class,
                () -> CliArgs.parse(new String[] {"--dir", "/rec", "--last", "--seqs", "5"}));
        assertTrue(e2.getMessage().contains("mutually exclusive"));
    }
}
