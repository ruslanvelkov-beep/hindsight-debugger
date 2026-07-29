#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Records the full transaction path of a crypto transfer through consensus-node node0.
#
# Workload: MiscCryptoSuite.transferChangesBalance — one cryptoCreate, one plain HBAR
# cryptoTransfer (GENESIS -> newPayee), one free balance query. All spec traffic goes
# to node0 (account .3, node.selector=fixed), so node0 sees ingest AND consensus AND
# handle AND state commit for the transfer.
#
# Mechanism: writes hedera-node/test-clients/build/hindsight/node0.jvmargs (a seam
# patched into ProcessUtils.javaCommandLineFor) so ONLY node0 gets the Hindsight
# -javaagent. Recording is gated on the first IngestWorkflowImpl#submitTransaction
# call, so node startup pays only transform + gated-hook overhead, not recording I/O.
#
# Uses the plain `testSubprocess` task (NOT hapiTestCrypto): mock TSS signatures,
# 2s blocks, and no hedera.transaction.maximumPermissibleUnhealthySeconds=5 override —
# all friendlier to a deliberately slowed node.

set -euo pipefail

HINDSIGHT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONSENSUS_DIR="${CONSENSUS_DIR:-/Users/ruslan.velkov/IdeaProjects/cloned/hiero-consensus-node}"
TEST_CLIENTS_DIR="$CONSENSUS_DIR/hedera-node/test-clients"
OUT_DIR="${OUT_DIR:-$TEST_CLIENTS_DIR/build/hindsight/recordings/transfer}"
TEST_FILTER="${TEST_FILTER:-com.hedera.services.bdd.suites.crypto.MiscCryptoSuite.transferChangesBalance}"

echo "==> Building hindsight jars + reader distribution"
(cd "$HINDSIGHT_DIR" && ./gradlew -q :hooks:jar :agent:jar :reader:installDist)

AGENT_JAR="$(ls "$HINDSIGHT_DIR"/agent/build/libs/hindsight-agent*.jar | head -1)"
HOOKS_JAR="$(ls "$HINDSIGHT_DIR"/hooks/build/libs/hooks*.jar | head -1)"
READER_BIN="$HINDSIGHT_DIR/reader/build/install/reader/bin/reader"
[[ -f "$AGENT_JAR" && -f "$HOOKS_JAR" && -x "$READER_BIN" ]] || { echo "missing artifacts"; exit 1; }

# Instrumentation scopes (longest prefix wins):
#  - full detail (brackets + args + locals + fields): the app layer end-to-end, the
#    state API/merkle bridge, and the platform's transaction pool/handling bridge.
#  - callsOnly (brackets + args): platform internals — gossip, event intake/creation,
#    hashgraph, PCES, crypto, wiring framework, virtualmap/merkledb, hints/history/tss.
#  - exclude: pure-noise app subpackages (metrics, block-node comms, grpc usage stats).
INCLUDE='com.hedera.node.app.|com.swirlds.state.|org.hiero.consensus.transaction.'
CALLS_ONLY='org.hiero.consensus.|org.hiero.base.crypto.|org.hiero.base.concurrent.|com.swirlds.component.framework.|com.swirlds.platform.|com.swirlds.virtualmap.|com.swirlds.merkledb.|com.hedera.node.app.hints.|com.hedera.node.app.history.|com.hedera.node.app.tss.'
EXCLUDE='com.hedera.node.app.metrics.|com.hedera.node.app.blocks.impl.streaming.|com.hedera.node.app.grpc.impl.usage.'
# PBJ model/state classes (AccountID, Account, TransactionBody, ...) are data, not code —
# instrumenting them would drown the recording, but their packages must be OPEN to the
# hooks module or their reflective snapshots silently come out empty (FORMAT.md §10).
# com.swirlds. adds reflection (NOT instrumentation) for platform classes outside the
# include scope; java.time. lives in java.base whose packages the agent opens eagerly
# at premain (Instant & friends load before any transform event).
SNAPSHOT_OPEN='com.hedera.hapi.|com.hedera.pbj.|com.swirlds.|java.time.'
# OneOf cells hold the actual values of PBJ oneofs (e.g. an AccountID's account number)
# and appear arbitrarily deep — always dump them regardless of snapshotDepth. Atomics and
# StringBuilder/StringBuffer are always-dumped unconditionally (FORMAT.md §7).
# java.time values and swirlds state objects appear arbitrarily deep and are exactly
# what recordings get inspected for — dump them regardless of snapshotDepth.
SNAPSHOT_ALWAYS='com.hedera.pbj.runtime.OneOf|java.time.|com.swirlds.state.|com.hedera.hapi.node.base.Timestamp'
TRIGGER='com.hedera.node.app.workflows.ingest.IngestWorkflowImpl#submitTransaction'

# Size the recording cap from actual free disk (leave 20 GiB headroom, cap at 100 GiB):
# an over-sized cap can never trip before ENOSPC, and a full disk corrupts the recording.
FREE_MB=$(df -m "$TEST_CLIENTS_DIR" | awk 'NR==2 {print $4}')
MAX_TOTAL_MB=$(( FREE_MB - 20480 ))
(( MAX_TOTAL_MB > 102400 )) && MAX_TOTAL_MB=102400
(( MAX_TOTAL_MB < 4096 )) && { echo "!! less than ~24 GiB free on disk; refusing to record"; exit 1; }

# Capture the target project's git HEAD so the recording's manifest records exactly which
# sources it was made against (FORMAT.md §13 / VIEWER.md §21.4). Empty when there is no .git.
# The §10 agent grammar rejects empty values, so only append the key when non-empty; an absent
# key defaults sourceCommit to "" in the agent, giving an empty manifest value.
SOURCE_COMMIT="$(git -C "$CONSENSUS_DIR" rev-parse HEAD 2>/dev/null || true)"
if [[ -n "$SOURCE_COMMIT" ]]; then
    echo "==> Target source commit: $SOURCE_COMMIT"
else
    echo "==> Target source commit: (unknown — no .git under $CONSENSUS_DIR)"
fi

# snapshotEvery=256: ~1 refresh per 256 object-id resolutions — frequent enough that
# container contents are inspectable at checkpoints, still a rounding error in CPU/IO.
# snapshotDepth=3: objects discovered inside a snapshot (account IDs, states, nested
# containers) get their own baseline dump, one level further so PBJ wrapper cells are
# readable too — Account(L1) -> AccountID(L2) -> OneOf holding the account number (L3)
# (FORMAT.md §7).
AGENT_OPTS="hooks=$HOOKS_JAR,outDir=$OUT_DIR,include=$INCLUDE,callsOnly=$CALLS_ONLY,exclude=$EXCLUDE,snapshotOpen=$SNAPSHOT_OPEN,snapshotAlways=$SNAPSHOT_ALWAYS,trigger=$TRIGGER,maxString=512,snapshotEvery=256,snapshotDepth=3,maxTotalMB=$MAX_TOTAL_MB,debug=true"
[[ -n "$SOURCE_COMMIT" ]] && AGENT_OPTS="$AGENT_OPTS,sourceCommit=$SOURCE_COMMIT"

echo "==> Writing node0 jvmargs seam file"
mkdir -p "$TEST_CLIENTS_DIR/build/hindsight"
# The seam is read by EVERY future subprocess-test run on this machine — remove it on exit
# so unrelated runs don't silently re-attach the agent.
trap 'rm -f "$TEST_CLIENTS_DIR/build/hindsight/node0.jvmargs"' EXIT
RUN_START_MS=$(( $(date +%s) * 1000 ))
cat > "$TEST_CLIENTS_DIR/build/hindsight/node0.jvmargs" <<EOF
# Written by record-crypto-transfer.sh ($(date))
-javaagent:$AGENT_JAR=$AGENT_OPTS
# Consensus node classfiles are Java 25 (major 69); let ByteBuddy accept them even if
# it postdates its official support matrix.
-Dnet.bytebuddy.experimental=true
EOF
cat "$TEST_CLIENTS_DIR/build/hindsight/node0.jvmargs"

echo "==> Running HAPI test (this is slow under tracing; expect many minutes)"
cd "$CONSENSUS_DIR"
set +e
./gradlew testSubprocess --tests "$TEST_FILTER" -x :yahcli:testSubprocess 2>&1 | tee "$TEST_CLIENTS_DIR/build/hindsight/run.log"
TEST_EXIT=${PIPESTATUS[0]}
set -e
echo "==> gradle exit: $TEST_EXIT"

# The agent creates OUT_DIR, or a "-<n>" sibling if OUT_DIR already had content. Guard
# against announcing a STALE recording from an earlier run (e.g. premain failed this time):
# the manifest's startEpochMillis must postdate this script's start.
REC_DIR="$(ls -dt "$OUT_DIR" "$OUT_DIR"-* 2>/dev/null | head -1 || true)"
if [[ -z "$REC_DIR" || ! -f "$REC_DIR/manifest.properties" ]]; then
    echo "!! No recording found under $OUT_DIR — check node0 agent.log / run.log"
    exit 1
fi
REC_START_MS=$(grep '^startEpochMillis=' "$REC_DIR/manifest.properties" | cut -d= -f2)
if [[ -z "$REC_START_MS" || "$REC_START_MS" -lt "$RUN_START_MS" ]]; then
    echo "!! Newest recording $REC_DIR predates this run (start $REC_START_MS < $RUN_START_MS)"
    echo "!! The agent likely failed to start — check run.log for premain errors"
    exit 1
fi

echo "==> Recording at: $REC_DIR"
du -sh "$REC_DIR"
echo "==> verify"
"$READER_BIN" verify --dir "$REC_DIR" || echo "(verify reported violations — see above)"
echo "==> stats"
"$READER_BIN" stats --dir "$REC_DIR" | tee "$REC_DIR/stats.txt"
echo "==> first 100 records of the merged trace"
"$READER_BIN" dump --dir "$REC_DIR" --limit 100
echo
echo "Recording ready: $REC_DIR  (exit of test run: $TEST_EXIT)"
exit "$TEST_EXIT"
