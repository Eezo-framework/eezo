#!/usr/bin/env bash
#
# Installs the eezo command line: https://eezo.io
#
#   curl -fsSL https://eezo.io/install | bash
#
# What it does, and nothing else:
#   1. checks for a JDK 25 or newer and sbt, and says what is missing;
#   2. records the released eezo version in ~/.eezo/eezo-version, which is where `eezo new`
#      reads the versions it writes into a scaffold;
#   3. downloads the launcher to ~/.local/bin/eezo.
#
# eezo itself comes from Maven Central when a scaffolded application first builds; nothing is
# compiled here. EEZO_HOME, EEZO_BIN and EEZO_SITE override the locations. Run it again to
# update. Runs on macOS and Linux; on Windows, use WSL.

set -euo pipefail

EEZO_VERSION="0.1.0"
SCALA_VERSION="3.8.4"
JDK_FLOOR=25
SBT_VERSION="1.12.14"

EEZO_HOME="${EEZO_HOME:-$HOME/.eezo}"
EEZO_BIN="${EEZO_BIN:-$HOME/.local/bin}"
EEZO_SITE="${EEZO_SITE:-https://eezo.io}"

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

# 1. the toolchain, checked and never installed for you
missing=0
if ! command -v curl > /dev/null 2>&1; then
  printf '✗ curl is not installed\n' >&2; missing=1
fi
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
    sdk install java 25-tem
    sdk install sbt

Then run this installer again.
MSG
  exit 1
fi
ok "JDK $(java_major) and sbt found"

# 2. the versions a scaffold is written with
mkdir -p "$EEZO_HOME" "$EEZO_BIN"
cat > "$EEZO_HOME/eezo-version" <<EOF
version=$EEZO_VERSION
scalaVersion=$SCALA_VERSION
jdkFloor=$JDK_FLOOR
sbt.version=$SBT_VERSION
EOF
ok "eezo $EEZO_VERSION recorded in $EEZO_HOME/eezo-version"

# 3. the launcher
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

The first build downloads eezo $EEZO_VERSION from Maven Central. The docs are at $EEZO_SITE/docs.
MSG
