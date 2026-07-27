#!/bin/bash
# reparse-derives.sh <logdir>
# Re-derives the profiler table from logs already on disk. Kept separate from
# derives-scale.sh because the first run of that script shipped a phasesum
# regex that expected "123ms" while -Yprofile-enabled actually emits
# "typer,run ns = 410150833,...". The logs were fine; only the parse was wrong.
set -euo pipefail
OUT=${1:?logdir}
echo -e "n_models\tn_derives\trun\tprofiler_ms"
for n in 5 20 40; do
  for k in 1 2 4 8; do
    for r in 1 2; do
      f="$OUT/derives-n${n}-k${k}-r${r}.log"
      [ -f "$f" ] || continue
      ms=$(python3 - "$f" <<'PY'
import re,sys
tot=0
for line in open(sys.argv[1],errors='ignore'):
    m=re.match(r'^([A-Za-z][A-Za-z0-9_]*),run ns = (\d+)', line)
    if m: tot+=int(m.group(2))
print(f"{tot/1e6:.1f}")
PY
)
      echo -e "$n\t$k\t$r\t$ms"
    done
  done
done
