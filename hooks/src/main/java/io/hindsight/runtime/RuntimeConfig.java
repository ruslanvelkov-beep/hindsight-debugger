// SPDX-License-Identifier: Apache-2.0
package io.hindsight.runtime;

import java.nio.file.Path;

/**
 * Plain configuration holder handed to {@link HindsightRuntime#boot(RuntimeConfig)} by the
 * agent's premain (FORMAT.md §8).
 *
 * <p>Deliberately a bag of public fields rather than a builder: this class crosses the
 * bootstrap-classloader boundary (the agent constructs it, the runtime consumes it), and the
 * dumber the surface, the less can go wrong when the two jars evolve at different speeds.
 * Field defaults mirror the agent-argument defaults of FORMAT.md §10 so a hand-constructed
 * config (tests, embedding) behaves like an unconfigured agent.
 */
public final class RuntimeConfig {

    /** Recording directory requested by the user; §1's sibling rule may substitute another. */
    public Path outDir = Path.of(".hindsight");

    /** Maximum recorded string length in chars; longer strings are cut and flagged TRUNCATED. */
    public int maxString = 512;

    /** Emit a shallow snapshot roughly once per this many object-id resolutions; 0 = off (§7). */
    public int snapshotEvery = 8192;

    /** Per-thread record chunk size in KiB (§9). */
    public int recordBufKB = 256;

    /** Per-thread blob chunk size in KiB (§9). */
    public int blobBufKB = 128;

    /** Total recording size cap in MiB; exceeding it stops recording and drops a TRUNCATED marker. */
    public long maxTotalMB = 204_800;

    /** Start recording immediately at boot instead of waiting for the trigger site. */
    public boolean autostart = true;

    /**
     * Interval in millis at which a background sweeper steals every worker's partial chunks to
     * disk, bounding data loss when the process is killed without running shutdown hooks
     * (SIGKILL — exactly how HAPI-test subprocess nodes die). 0 disables sweeping.
     */
    public int sweepMillis = 500;
}
