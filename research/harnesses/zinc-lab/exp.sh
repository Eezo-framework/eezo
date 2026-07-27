#!/bin/bash
# usage: exp.sh <label>
cd /tmp/eezo-zinc-lab
LOG=/tmp/eezo-zinc-lab/logs/$1.log
mkdir -p /tmp/eezo-zinc-lab/logs
sbt -batch -Dsbt.log.noformat=true compile > "$LOG" 2>&1
echo "--- $1 ---"
grep -E "compiling [0-9]+ Scala|Recompiling all sources|Initial source changes|invalidated|Total time" "$LOG" | grep -vE "^\[debug\] (Full|The) " | head -40
