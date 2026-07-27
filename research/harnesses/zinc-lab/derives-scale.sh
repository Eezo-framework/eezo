#!/bin/bash
# derives-scale.sh <workdir> <logdir>
#
# How much does a `derives` clause cost the COMPILER, as a function of how many
# typeclasses are in it and how many models carry it? Same instrument and same
# reporting convention as research/capture-checking.md §5 and
# research/db-query-layer.md §3: `scala-cli compile --server=false -O
# -Yprofile-enabled`, and the figure reported is the sum of per phase `run ns`
# from the compiler's own profiler, which excludes JVM start and scala-cli
# overhead.
set -euo pipefail
WORK=${1:?workdir}
OUT=${2:?logdir}
mkdir -p "$WORK" "$OUT"
SCALA=3.8.4

TC_SRC="$(cd "$(dirname "$0")" && pwd)/tc.scala"

gen() { # gen <dir> <n_models> <n_derives>
  local d=$1 n=$2 k=$3
  rm -rf "$d"; mkdir -p "$d"
  cp "$TC_SRC" "$d/Typeclasses.scala"
  local names="Codec, Table, Form, Resource, Show, Json, Csv, Diff"
  local list
  list=$(echo "$names" | cut -d, -f1-"$k" | sed 's/^ *//')
  {
    echo "package model"
    local i=0
    while [ "$i" -lt "$n" ]; do
      echo "case class Ent$i(id: Int, f1: String, f2: String, f3: Boolean, f4: Int, f5: Long, f6: String, f7: Int) derives $list"
      i=$((i+1))
    done
  } > "$d/Models.scala"
}

phasesum() { # sum the compiler profiler's per phase `run ns` from a log, in ms
  # -Yprofile-enabled emits one line per phase:
  #   typer,run ns = 410150833,idle ns = 0,cpu ns = 387955000,...
  python3 - "$1" <<'PY'
import re,sys
tot=0
for line in open(sys.argv[1],errors='ignore'):
    m=re.match(r'^([A-Za-z][A-Za-z0-9_]*),run ns = (\d+)', line)
    if m: tot+=int(m.group(2))
print(f"{tot/1e6:.1f}")
PY
}

echo -e "n_models\tn_derives\trun\tprofiler_ms\twall_ms"
for n in 5 20 40; do
  for k in 1 2 4 8; do
    d="$WORK/d${k}_n${n}"
    gen "$d" "$n" "$k"
    for r in 1 2; do
      rm -rf "$d/.scala-build"
      t0=$(python3 -c 'import time;print(int(time.time()*1000))')
      scala-cli compile "$d" -S "$SCALA" --server=false -O -Yprofile-enabled \
        > "$OUT/derives-n${n}-k${k}-r${r}.log" 2>&1 || {
          echo "FAILED n=$n k=$k r=$r" >&2; tail -20 "$OUT/derives-n${n}-k${k}-r${r}.log" >&2; exit 1; }
      t1=$(python3 -c 'import time;print(int(time.time()*1000))')
      echo -e "$n\t$k\t$r\t$(phasesum "$OUT/derives-n${n}-k${k}-r${r}.log")\t$((t1-t0))"
    done
  done
done
