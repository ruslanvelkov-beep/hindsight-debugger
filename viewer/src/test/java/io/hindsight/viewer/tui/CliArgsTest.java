// SPDX-License-Identifier: Apache-2.0
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
}
