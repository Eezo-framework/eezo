#!/bin/bash
# loop.sh <projectdir> <logdir>
#
# Measures the terms of the dev loop that `run.sh` deliberately excludes,
# because `run.sh` times Compile/compile from inside an already warm sbt and
# that is the floor, not the loop:
#
#   cold_noop      a fresh `sbt compile` process on an up to date tree
#                  (= what you pay if the build tool is not resident)
#   cold_edit      a fresh `sbt compile` process after one edit
#   watch_*        wall clock from `touch`ing the file to sbt's `~compile`
#                  reporting the rebuild finished (= the real trigger to
#                  compiled latency, including the file watcher's own delay)
#   scalacli_*     the same edits under scala-cli, which drives Bloop
set -euo pipefail
PROJ=${1:?projectdir}
OUT=${2:?logdir}
mkdir -p "$OUT"
cd "$PROJ"

now_ms() { python3 -c 'import time;print(int(time.time()*1000))'; }

# ------------------------------------------------------------------ cold sbt
echo "== cold sbt =="
bash edits.sh reset >/dev/null 2>&1
sbt -batch -Dsbt.log.noformat=true compile > "$OUT/cold-warmup.log" 2>&1

for i in 1 2 3; do
  t0=$(now_ms)
  sbt -batch -Dsbt.log.noformat=true compile > "$OUT/cold-noop-$i.log" 2>&1
  t1=$(now_ms)
  echo -e "cold_noop\t$i\t$((t1-t0))"
done

for i in 1 2 3; do
  bash edits.sh reset >/dev/null 2>&1
  sbt -batch -Dsbt.log.noformat=true compile > /dev/null 2>&1
  bash edits.sh add_field >/dev/null 2>&1
  t0=$(now_ms)
  sbt -batch -Dsbt.log.noformat=true compile > "$OUT/cold-addfield-$i.log" 2>&1
  t1=$(now_ms)
  echo -e "cold_add_field\t$i\t$((t1-t0))"
done

bash edits.sh reset >/dev/null 2>&1
sbt -batch -Dsbt.log.noformat=true compile > /dev/null 2>&1

# ------------------------------------------------------------- sbt ~labWatch
echo "== sbt ~labWatch =="
WLOG="$OUT/watch-sbt.log"
: > "$WLOG"
sbt -Dsbt.log.noformat=true "~labWatch" > "$WLOG" 2>&1 &
SBTPID=$!
trap 'kill $SBTPID 2>/dev/null || true' EXIT

markers()  { grep -c '===WATCH-DONE' "$WLOG" || true; }
lastmark() { grep '===WATCH-DONE' "$WLOG" | tail -1 | cut -f2; }
laststat() { grep '===WATCH-DONE' "$WLOG" | tail -1 | cut -f3; }

# Wait for the build to go quiet: no new marker for `quiet` ms, then report the
# timestamp of the LAST marker. A multi file edit can legitimately trigger more
# than one rebuild; the user only cares about when the last one lands.
settle() { # settle <minimum markers required before quiet counts>
  local min=$1 quiet=1500 deadline=$((SECONDS+240)) last cur lastchange
  last=$(markers); lastchange=$(now_ms)
  while :; do
    sleep 0.05
    cur=$(markers)
    if [ "$cur" != "$last" ]; then last=$cur; lastchange=$(now_ms); fi
    if [ "$cur" -ge "$min" ] && [ $(( $(now_ms) - lastchange )) -ge $quiet ]; then break; fi
    if [ $SECONDS -gt $deadline ]; then echo "TIMEOUT" >&2; return 1; fi
  done
}
settle 1

for e in comment add_field rename_unused field_type rename_used add_derive; do
  for i in 1 2 3; do
    sleep 1
    n0=$(markers)
    t0=$(now_ms)
    bash edits.sh "$e" >/dev/null 2>&1
    settle $(( n0 + 1 ))
    n1=$(markers)
    echo -e "watch_sbt_$e\t$i\t$(( $(lastmark) - t0 ))\trebuilds=$(( n1 - n0 ))\t$(laststat)"
    sleep 1
    bash edits.sh reset >/dev/null 2>&1
    settle $(( n1 + 1 ))
  done
done
kill $SBTPID 2>/dev/null || true
wait $SBTPID 2>/dev/null || true
trap - EXIT
bash edits.sh reset >/dev/null 2>&1

# ------------------------------------------------------------------ scala-cli
echo "== scala-cli (Bloop) =="
SDIR="$PROJ/src/main/scala"
scala-cli compile "$SDIR" -S 3.8.4 > "$OUT/scalacli-warmup.log" 2>&1 || true
for e in comment add_field rename_used add_derive; do
  for i in 1 2 3; do
    bash edits.sh reset >/dev/null 2>&1
    scala-cli compile "$SDIR" -S 3.8.4 > /dev/null 2>&1 || true
    bash edits.sh "$e" >/dev/null 2>&1
    t0=$(now_ms)
    scala-cli compile "$SDIR" -S 3.8.4 > "$OUT/scalacli-$e-$i.log" 2>&1 || true
    t1=$(now_ms)
    echo -e "scalacli_$e\t$i\t$((t1-t0))"
  done
done
bash edits.sh reset >/dev/null 2>&1
echo "LOOP DONE"
