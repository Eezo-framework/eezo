#!/bin/zsh
# Counts virtual vs platform threads while N WebSocket connections are held open.
export PATH=/usr/bin:/bin:/usr/sbin:/sbin
# Usage: ./vtcount.sh <ConnScaleClass> <classpath> <port> <statsPath> <n>
JH=/Users/rcardin/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home
cd "$(dirname "$0")"
cls=$1; cp=$2; port=$3; path=$4; n=$5
ulimit -n 20000
$JH/bin/java -Xmx2g -cp "out:$cp" $cls $port > /dev/null 2>&1 &
PID=$!
/bin/sleep 5
$JH/bin/java -cp out ConnLoad $port $n "$path" > /tmp/load_$cls.log 2>&1 &
LP=$!
/bin/sleep 6
$JH/bin/jcmd $PID Thread.dump_to_file -overwrite -format=json /tmp/dump_$cls.json > /dev/null 2>&1
/usr/bin/python3 - "$cls" <<'PY'
import json, sys, collections
cls = sys.argv[1]
d = json.load(open('/tmp/dump_%s.json' % cls))
tot = virt = 0
names = collections.Counter()
def walk(c):
    global tot, virt
    for t in (c.get('threads') or []):
        tot += 1
        v = t.get('virtual') in ('true', True)
        if v:
            virt += 1
            names[(t.get('name') or '<unnamed>')[:30]] += 1
for c in d['threadDump']['threadContainers']:
    walk(c)
print('%s: total=%d virtual=%d platform=%d' % (cls, tot, virt, tot - virt))
for k, v in names.most_common(3):
    print('    virtual name sample: %r x%d' % (k, v))
PY
/usr/bin/grep -E "opened|loaded" /tmp/load_$cls.log
wait $LP 2>/dev/null
kill $PID 2>/dev/null
