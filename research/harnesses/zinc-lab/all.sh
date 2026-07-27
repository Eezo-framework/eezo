#!/bin/bash
# all.sh [workdir]
# Full re-run of the zinc-lab matrix. Sequential on purpose: two sbt JVMs
# competing for cores would make the timings meaningless.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK=${1:-/tmp/eezo-zinc-lab}
LOGS="$HERE/logs"
mkdir -p "$LOGS" "$WORK"

run_variant() { # name ncons nnamed nunrel nderives reps
  local name=$1 nc=$2 nn=$3 nu=$4 nd=$5 reps=$6
  echo "=== variant $name (cons=$nc named=$nn unrel=$nu derives=$nd reps=$reps) ==="
  "$HERE/gen.sh" "$WORK/$name" "$nc" "$nn" "$nu" "$nd"
  "$HERE/run.sh" "$WORK/$name" "$LOGS/$name.log" "$reps" false
  "$HERE/analyse.sh" "$LOGS/$name.log" > "$LOGS/$name.tsv"
  echo "--- $name ---"; cat "$LOGS/$name.tsv"
}

# 1. Invalidation sets, debug level, one rep. This is the log that answers
#    "what exactly does Zinc invalidate", not "how long does it take".
"$HERE/gen.sh" "$WORK/debug" 6 6 8 4
"$HERE/run.sh" "$WORK/debug" "$LOGS/invalidation-debug.log" 1 true
"$HERE/analyse.sh" "$LOGS/invalidation-debug.log" > "$LOGS/invalidation-debug.tsv"

# 2. Timings, info level.
run_variant small-derives4 6 6 8 4 3
run_variant small-derives1 6 6 8 1 3
run_variant scale-derives4 50 50 100 4 3
run_variant scale-derives1 50 50 100 1 3

echo "ALL DONE"
