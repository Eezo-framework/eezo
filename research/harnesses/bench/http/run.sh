#!/bin/zsh
# eezo #3 HTTP server boot/restart rig driver.
# Usage: ./run.sh <JAVA_HOME> <repeats>
set -e
cd "$(dirname "$0")"
JH=${1:?java home}
N=${2:-7}
CPJ=$(cat cp_jetty_ws.txt); CPH=$(cat cp_helidon_ws.txt); CPU=$(cat cp_undertow.txt)
CPV=$(cat cp_vertx.txt); CPN=$(cat cp_netty.txt)

rm -rf out && mkdir -p out
$JH/bin/javac -nowarn -d out src/Rig.java
$JH/bin/javac -nowarn -cp "out:$CPJ" -d out src/BootJetty.java
$JH/bin/javac -nowarn -cp "out:$CPH" -d out src/BootHelidon.java
$JH/bin/javac -nowarn -cp "out:$CPU" -d out src/BootUndertow.java
$JH/bin/javac -nowarn -cp out -d out src/BootJdk.java
$JH/bin/javac -nowarn -cp "out:$CPV" -d out src/BootVertx.java
$JH/bin/javac -nowarn -cp "out:$CPN" -d out src/BootNetty.java

echo "# toolchain: $($JH/bin/java -version 2>&1 | head -1)"
for i in $(seq 1 $N); do
  $JH/bin/java -cp "out"      BootJdk      18090 2>/dev/null
  $JH/bin/java -cp "out:$CPJ" BootJetty    18091 2>/dev/null
  $JH/bin/java -cp "out:$CPH" BootHelidon  18092 2>/dev/null
  $JH/bin/java -cp "out:$CPU" BootUndertow 18093 2>/dev/null
  $JH/bin/java -cp "out:$CPV" BootVertx    18094 2>/dev/null
  $JH/bin/java -cp "out:$CPN" BootNetty    18095 2>/dev/null
done
