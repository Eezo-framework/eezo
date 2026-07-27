#!/bin/bash
# run.sh <projectdir> <logfile> [reps] [debug]
#
# Runs the whole experiment matrix inside ONE sbt batch invocation, so the
# compiler, the JIT and Zinc's analysis stay warm across every edit. This is the
# thing the 2026-07-26 rig got wrong: it ran a separate cold `sbt compile`
# process per experiment, which measures sbt's boot time, not the edit loop.
#
# Protocol, per experiment E, repeated <reps> times:
#     edit reset      restore pristine sources
#     timed settle_E  compile back to the pristine state (not reported)
#     edit E          apply the edit
#     timed E         the measurement
set -euo pipefail

PROJ=${1:?projectdir}
LOG=${2:?logfile}
REPS=${3:-3}
DEBUG=${4:-false}

EDITS=(
  touch_mtime
  comment
  method_body
  add_field
  rename_unused
  rename_used
  field_type
  add_derive
  drop_derive
  order_add_field
  inline_body
  noninline_body
  unrelated_body
  consumer_body
)

CMDS=("clean" "timed clean_build")
r=1
while [ "$r" -le "$REPS" ]; do
  for e in "${EDITS[@]}"; do
    CMDS+=("edit reset" "timed settle_${e}_r${r}" "edit $e" "timed ${e}_r${r}")
  done
  r=$((r+1))
done
CMDS+=("edit reset" "timed final_reset")

mkdir -p "$(dirname "$LOG")"
cd "$PROJ"
echo "running ${#CMDS[@]} sbt commands in one session -> $LOG"
sbt -batch -Dsbt.log.noformat=true -Dlab.debug="$DEBUG" "${CMDS[@]}" > "$LOG" 2>&1 || {
  echo "SBT RUN FAILED, tail:" >&2; tail -40 "$LOG" >&2; exit 1; }
echo "done"
