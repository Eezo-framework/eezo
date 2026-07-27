#!/bin/bash
# all3.sh [workdir] : loop terms only (all2.sh's second half, after the watch
# driver was fixed).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK=${1:-/tmp/eezo-zinc-lab}
LOGS="$HERE/logs"
mkdir -p "$LOGS" "$WORK"

echo "=== loop terms, small project ==="
"$HERE/gen.sh" "$WORK/loop-small" 6 6 8 4
"$HERE/loop.sh" "$WORK/loop-small" "$LOGS/loop-small" | tee "$LOGS/loop-small.tsv"

echo "=== loop terms, scale project ==="
"$HERE/gen.sh" "$WORK/loop-scale" 50 50 100 4
"$HERE/loop.sh" "$WORK/loop-scale" "$LOGS/loop-scale" | tee "$LOGS/loop-scale.tsv"

echo "ALL3 DONE"
