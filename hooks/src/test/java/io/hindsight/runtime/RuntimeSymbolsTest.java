// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link RuntimeSymbols}: id space, dedup, TSV escaping and eager flushing (FORMAT.md §4). */
class RuntimeSymbolsTest {

    @TempDir
    Path dir;

    @Test
    void idsAreRuntimeSpaceAndDense() throws IOException {
        final RuntimeSymbols syms = new RuntimeSymbols(dir);
        assertEquals(0x4000_0000 | 1, syms.intern(RuntimeSymbols.Kind.THREAD, "main"));
        assertEquals(0x4000_0000 | 2, syms.intern(RuntimeSymbols.Kind.RTCLASS, "java.util.ArrayList"));
        assertEquals(0x4000_0000 | 3, syms.intern(RuntimeSymbols.Kind.FIELDNAME, "size"));
        syms.close();
    }

    @Test
    void dedupIsByKindAndText() throws IOException {
        final RuntimeSymbols syms = new RuntimeSymbols(dir);
        final int a = syms.intern(RuntimeSymbols.Kind.THREAD, "worker");
        final int b = syms.intern(RuntimeSymbols.Kind.THREAD, "worker");
        final int c = syms.intern(RuntimeSymbols.Kind.RTCLASS, "worker"); // same text, other kind
        assertEquals(a, b);
        assertNotEquals(a, c);
        syms.close();
        assertEquals(2, Files.readAllLines(dir.resolve("rt-symbols.tsv")).size());
    }

    @Test
    void escapingRoundTrips() throws IOException {
        final String nasty = "a\\b\tc\nd\\te\\nf";
        assertEquals("a\\\\b\\tc\\nd\\\\te\\\\nf", RuntimeSymbols.escape(nasty));
        assertEquals(nasty, Recordings.unescape(RuntimeSymbols.escape(nasty)));

        final RuntimeSymbols syms = new RuntimeSymbols(dir);
        final int id = syms.intern(RuntimeSymbols.Kind.THREAD, nasty);
        syms.close();

        final Map<Integer, String[]> parsed = Recordings.rtSymbols(dir);
        assertArrayEquals(new String[] {"THREAD", nasty}, parsed.get(id));
    }

    @Test
    void plainTextIsNotCopied() {
        final String plain = "no escaping needed";
        assertTrue(RuntimeSymbols.escape(plain) == plain, "escape must return the same instance");
    }

    @Test
    void flushIsEagerLineByLine() throws IOException {
        final RuntimeSymbols syms = new RuntimeSymbols(dir);
        syms.intern(RuntimeSymbols.Kind.THREAD, "t1");
        // No close() — simulates a crashed run; the line must already be durable (§4).
        final List<String> lines = Files.readAllLines(dir.resolve("rt-symbols.tsv"));
        assertEquals(1, lines.size());
        assertEquals((0x4000_0000 | 1) + "\tTHREAD\tt1", lines.get(0));
        syms.close();
    }
}
