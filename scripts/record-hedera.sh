#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Hedera/Hiero consensus-node recording profile for Hindsight.
#
# A thin, project-specific wrapper around scripts/record-init.gradle (which is itself
# project-neutral): it supplies the instrumentation scopes the consensus node needs and runs
# the Gradle test you name. Nothing here applies to any other codebase — to record a different
# project, use record-init.gradle directly with your own -Dhindsight.include/-Dhindsight.open.
#
# Run it from a consensus-node checkout (any clone or worktree), or set CONSENSUS_DIR:
#
#   cd <consensus-node checkout>
#   <hindsight>/scripts/record-hedera.sh :hedera-app:test --tests 'DispatchProcessorTest'
#
# Overrides: CONSENSUS_DIR, REC_DIR, INCLUDE, OPEN, ALWAYS, TRIGGER, DEPTH; DRY_RUN=1 prints
# the command instead of running it. All remaining arguments go to Gradle verbatim.

set -euo pipefail

HINDSIGHT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONSENSUS_DIR="${CONSENSUS_DIR:-$PWD}"
REC_DIR="${REC_DIR:-$CONSENSUS_DIR/build/hindsight/rec-$(date +%Y%m%d-%H%M%S)}"

[[ -x "$CONSENSUS_DIR/gradlew" ]] \
    || { echo "!! $CONSENSUS_DIR is not a Gradle checkout — cd into one or set CONSENSUS_DIR"; exit 1; }

# The consensus node's application layer. Widen with INCLUDE=... for platform/state internals.
INCLUDE="${INCLUDE:-com.hedera.node.app.}"
# snapshotOpen decides whether object FIELDS can be read at all — it opens those packages to the
# hooks module. The node's objects reach across all four package roots (app, generated HAPI/PBJ
# records, platform, and java.time timestamps), so all four must be listed or those objects show
# "<No info available>" in the viewer. This is ORTHOGONAL to snapshotAlways below.
OPEN="${OPEN:-com.hedera.|com.swirlds.|org.hiero.|java.time.}"
# Value carriers worth dumping however deep they are discovered (depth-exemption only).
ALWAYS="${ALWAYS:-com.hedera.pbj.runtime.OneOf|java.time.|com.swirlds.state.|com.hedera.hapi.node.base.Timestamp}"
DEPTH="${DEPTH:-3}"
TRIGGER="${TRIGGER:-}"

ARGS=(--init-script "$HINDSIGHT_DIR/scripts/record-init.gradle"
      -Dhindsight.rec="$REC_DIR"
      -Dhindsight.include="$INCLUDE"
      -Dhindsight.open="$OPEN"
      -Dhindsight.always="$ALWAYS"
      -Dhindsight.depth="$DEPTH")
[[ -n "$TRIGGER" ]] && ARGS+=(-Dhindsight.trigger="$TRIGGER")

if [[ -n "${DRY_RUN:-}" ]]; then
    printf '%q ' "$CONSENSUS_DIR/gradlew" "$@" "${ARGS[@]}"; echo
    exit 0
fi

(cd "$HINDSIGHT_DIR" && ./gradlew -q :agent:jar :hooks:jar :reader:installDist)
echo "==> recording into $REC_DIR"
cd "$CONSENSUS_DIR"
./gradlew "$@" "${ARGS[@]}"
echo
echo "Recording ready: $REC_DIR"
echo "  $HINDSIGHT_DIR/reader/build/install/reader/bin/reader stats --dir \"$REC_DIR\""
echo "  $HINDSIGHT_DIR/viewer/build/install/viewer/bin/viewer --dir \"$REC_DIR\" --repo \"$CONSENSUS_DIR\""
