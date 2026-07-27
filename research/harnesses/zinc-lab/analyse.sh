#!/bin/bash
# analyse.sh <logfile>
# Emits: label, sources recompiled (summed over Zinc's internal rounds), ms.
set -euo pipefail
awk -F'\t' '
  /^===LAB-BEGIN/ { label=$2; srcs=0; rounds=0; next }
  /compiling [0-9]+ Scala source/ {
    if (match($0, /compiling [0-9]+ Scala source/)) {
      s=substr($0, RSTART+10); split(s, a, " "); srcs+=a[1]; rounds++
    }
    next
  }
  /^===LAB-TIMED/ { printf "%s\t%s\t%s\t%s\n", $2, srcs, rounds, $3; next }
' "$1"
