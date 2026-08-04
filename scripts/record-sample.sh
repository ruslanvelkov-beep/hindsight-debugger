#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Records the bundled sample application (io.hindsight.sample.BankSim) under the Hindsight
# agent and prints how to open the result in the viewer. This is the quickest end-to-end
# demonstration of the tool; use it as a template for recording your own program.
#
# To record YOUR OWN program, the shape is the same — attach the agent and name the packages
# to instrument:
#
#   java -javaagent:<agent.jar>=hooks=<hooks.jar>,outDir=<dir>,include=<your.package.> \
#        -jar your-app.jar
#
# See FORMAT.md §10 for the full agent configuration grammar (include / branchOnly / callsOnly
# scopes, snapshot options, triggers, size caps).

set -euo pipefail

HINDSIGHT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$HINDSIGHT_DIR/build/recordings/banksim}"

echo "==> Building agent, hooks, sample jars + reader/viewer distributions"
(cd "$HINDSIGHT_DIR" && ./gradlew -q :agent:jar :hooks:jar :sample:jar :reader:installDist :viewer:installDist)

AGENT_JAR="$(ls "$HINDSIGHT_DIR"/agent/build/libs/hindsight-agent*.jar | head -1)"
HOOKS_JAR="$(ls "$HINDSIGHT_DIR"/hooks/build/libs/hooks*.jar | head -1)"
SAMPLE_JAR="$(ls "$HINDSIGHT_DIR"/sample/build/libs/sample*.jar | head -1)"
READER_BIN="$HINDSIGHT_DIR/reader/build/install/reader/bin/reader"
[[ -f "$AGENT_JAR" && -f "$HOOKS_JAR" && -f "$SAMPLE_JAR" && -x "$READER_BIN" ]] \
    || { echo "!! missing build artifacts"; exit 1; }

# Fresh output dir (the agent creates a "-<n>" sibling if the target already has content).
rm -rf "$OUT_DIR"
mkdir -p "$(dirname "$OUT_DIR")"

# include=io.hindsight.sample. → full instrumentation (method brackets + args + locals + fields
# + line/branch flow) of the sample's own classes; autostart=true records from the first event.
AGENT_OPTS="hooks=$HOOKS_JAR,outDir=$OUT_DIR,include=io.hindsight.sample.,autostart=true,snapshotEvery=64,debug=true"

echo "==> Running the sample under the agent"
java -javaagent:"$AGENT_JAR=$AGENT_OPTS" -jar "$SAMPLE_JAR"

echo "==> verify"
"$READER_BIN" verify --dir "$OUT_DIR" || echo "(verify reported violations — see above)"
echo "==> stats"
"$READER_BIN" stats --dir "$OUT_DIR"
echo
echo "Recording ready: $OUT_DIR"
echo "Open it in the viewer:"
echo "  $HINDSIGHT_DIR/viewer/build/install/viewer/bin/viewer --dir \"$OUT_DIR\" --repo \"$HINDSIGHT_DIR\""
