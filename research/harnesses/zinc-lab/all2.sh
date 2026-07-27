#!/bin/bash
# all2.sh [workdir] : second batch. Bigger scale point, plus the loop terms.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK=${1:-/tmp/eezo-zinc-lab}
LOGS="$HERE/logs"
mkdir -p "$LOGS" "$WORK"

echo "=== variant big-derives4 (cons=200 named=200 unrel=100 derives=4) ==="
"$HERE/gen.sh" "$WORK/big-derives4" 200 200 100 4
"$HERE/run.sh" "$WORK/big-derives4" "$LOGS/big-derives4.log" 3 false
"$HERE/analyse.sh" "$LOGS/big-derives4.log" > "$LOGS/big-derives4.tsv"
grep "_r3" "$LOGS/big-derives4.tsv"

echo "=== loop terms, small project ==="
"$HERE/gen.sh" "$WORK/loop-small" 6 6 8 4
"$HERE/loop.sh" "$WORK/loop-small" "$LOGS/loop-small" | tee "$LOGS/loop-small.tsv"

echo "=== loop terms, scale project ==="
"$HERE/gen.sh" "$WORK/loop-scale" 50 50 100 4
"$HERE/loop.sh" "$WORK/loop-scale" "$LOGS/loop-scale" | tee "$LOGS/loop-scale.tsv"

echo "ALL2 DONE"
