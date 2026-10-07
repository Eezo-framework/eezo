#!/usr/bin/env bash
#
# Installs the eezo command line: https://eezo.io
#
#   curl -fsSL https://eezo.io/install | bash
#
# What it does, and nothing else:
#   1. asks Maven Central which eezo is the latest release, and reads off its POM the Scala
#      version, the JDK floor and the sbt version that release is built with;
#   2. checks for a JDK at that floor or newer and for sbt, and says what is missing;
#   3. records the four in ~/.eezo/eezo-version, which is where `eezo new` reads the versions
#      it writes into a scaffold;
#   4. downloads the launcher to ~/.local/bin/eezo.
#
# eezo itself comes from Maven Central when a scaffolded application first builds; nothing is
# compiled here. Run it again, or `eezo upgrade`, to move to a newer release. EEZO_VERSION pins
# a release instead of the latest; EEZO_HOME, EEZO_BIN, EEZO_SITE and EEZO_MAVEN override the
# locations. Runs on macOS and Linux; on Windows, use WSL.

set -euo pipefail

EEZO_HOME="${EEZO_HOME:-$HOME/.eezo}"
EEZO_BIN="${EEZO_BIN:-$HOME/.local/bin}"
EEZO_SITE="${EEZO_SITE:-https://eezo.io}"
EEZO_MAVEN="${EEZO_MAVEN:-https://repo1.maven.org/maven2/io/eezo}"

say()  { printf '→ %s\n' "$*"; }
ok()   { printf '✓ %s\n' "$*"; }
fail() { printf '✗ %s\n' "$*" >&2; exit 1; }

java_major() {
  # "25.0.1" -> 25, and the old "1.8.0_292" -> 8
  local raw
  raw="$(java -version 2>&1 | head -n 1 | sed -E 's/.*"([^"]+)".*/\1/')"
  case "$raw" in
    1.*) printf '%s' "$raw" | cut -d. -f2 ;;
    *)   printf '%s' "$raw" | cut -d. -f1 | tr -dc '0-9' ;;
  esac
}

# A tag's text out of a small XML document, first occurrence; empty when it is not there.
tag() { # <xml> <tag>
  printf '%s\n' "$1" | sed -n "s:.*<$2>\\([^<]*\\)</$2>.*:\\1:p" | head -n 1
}

command -v curl > /dev/null 2>&1 || fail "curl is not installed"

# 1. the release, and what it was built with, from Maven Central. The POM names the Scala
#    version through its scala3-library dependency; the JDK floor and the sbt version are
#    properties eezo writes into the POM from 0.1.1 on, so for 0.1.0 the two are the values that
#    release was built with.
if [ -n "${EEZO_VERSION:-}" ]; then
  say "pinned to eezo $EEZO_VERSION"
else
  say "asking Maven Central for the latest eezo"
  metadata="$(curl -fsSL "$EEZO_MAVEN/eezo_3/maven-metadata.xml")" \
    || fail "could not reach Maven Central at $EEZO_MAVEN"
  EEZO_VERSION="$(tag "$metadata" release)"
  [ -n "$EEZO_VERSION" ] || fail "Maven Central lists no eezo release"
fi
pom="$(curl -fsSL "$EEZO_MAVEN/eezo_3/$EEZO_VERSION/eezo_3-$EEZO_VERSION.pom")" \
  || fail "eezo $EEZO_VERSION is not on Maven Central"
SCALA_VERSION="$(printf '%s\n' "$pom" \
  | awk '/<artifactId>scala3-library_3<\/artifactId>/ { found = 1 }
         found && /<version>/ { gsub(/.*<version>|<\/version>.*/, ""); print; exit }')"
[ -n "$SCALA_VERSION" ] || fail "the POM of eezo $EEZO_VERSION names no Scala version"
JDK_FLOOR="$(tag "$pom" eezo.jdkFloor)"
SBT_VERSION="$(tag "$pom" eezo.sbtVersion)"
JDK_FLOOR="${JDK_FLOOR:-25}"
SBT_VERSION="${SBT_VERSION:-1.12.14}"
ok "eezo $EEZO_VERSION, built with Scala $SCALA_VERSION, JDK $JDK_FLOOR and sbt $SBT_VERSION"

# 2. the toolchain, checked and never installed for you
missing=0
if ! command -v java > /dev/null 2>&1; then
  printf '✗ no JDK found; eezo needs JDK %s or newer\n' "$JDK_FLOOR" >&2; missing=1
else
  major="$(java_major)"
  if [ -z "$major" ] || [ "$major" -lt "$JDK_FLOOR" ]; then
    printf '✗ JDK %s or newer is needed, and `java -version` reports %s\n' "$JDK_FLOOR" "${major:-something unreadable}" >&2
    missing=1
  fi
fi
if ! command -v sbt > /dev/null 2>&1; then
  printf '✗ sbt is not installed\n' >&2; missing=1
fi
if [ "$missing" -ne 0 ]; then
  cat >&2 <<'MSG'

The easiest way to get a JDK and sbt is SDKMAN (https://sdkman.io):

    curl -s "https://get.sdkman.io" | bash
    sdk install java 25-tem      # or whatever the floor above says
    sdk install sbt

Then run this installer again.
MSG
  exit 1
fi
ok "JDK $(java_major) and sbt found"

# 3. the versions a scaffold is written with
mkdir -p "$EEZO_HOME" "$EEZO_BIN"
cat > "$EEZO_HOME/eezo-version" <<EOF
version=$EEZO_VERSION
scalaVersion=$SCALA_VERSION
jdkFloor=$JDK_FLOOR
sbt.version=$SBT_VERSION
EOF
ok "eezo $EEZO_VERSION recorded in $EEZO_HOME/eezo-version"

# 4. the launcher, swapped in with a rename so an `eezo upgrade` replacing itself finishes on
#    the old file
say "downloading the launcher from $EEZO_SITE/install/eezo"
curl -fsSL "$EEZO_SITE/install/eezo" -o "$EEZO_BIN/eezo.tmp"
head -n 1 "$EEZO_BIN/eezo.tmp" | grep -q '^#!' || { rm -f "$EEZO_BIN/eezo.tmp"; fail "that did not look like the launcher"; }
mv "$EEZO_BIN/eezo.tmp" "$EEZO_BIN/eezo"
chmod +x "$EEZO_BIN/eezo"
ok "installed $EEZO_BIN/eezo"

case ":$PATH:" in
  *":$EEZO_BIN:"*) ;;
  *)
    printf '\n%s is not on your PATH. Add it, for example:\n\n' "$EEZO_BIN"
    printf '    echo '"'"'export PATH="%s:$PATH"'"'"' >> ~/.bashrc    # or ~/.zshrc\n\n' "$EEZO_BIN"
    ;;
esac

cat <<MSG

Done. Start with:

    eezo new myapp
    cd myapp
    eezo dev

The first build downloads eezo $EEZO_VERSION from Maven Central. The docs are at $EEZO_SITE/docs,
and \`eezo upgrade\` moves to the next release.
MSG
