# The environment every sbt run in this checkout needs, in one place. bin/gate and bin/fast both
# source this file, so the two cannot drift. Why each line exists is in the header of bin/gate.
#
# The caller sets ROOT, the checkout, before sourcing. It may set DRY_RUN=1, which keeps the git
# config untouched, and TOOL, the name printed in front of each message, which defaults to gate.
# After sourcing, EEZO_JDK_MISMATCH is 1 when sbt will not run on the JDK CI builds on.

if [ -z "${ROOT:-}" ]; then
  echo "bin/lib/env.sh: ROOT is not set" >&2
  return 2 2>/dev/null || exit 2
fi
EEZO_TOOL=${TOOL:-gate}
EEZO_DRY_RUN=${DRY_RUN:-0}

# The JDK CI builds on is JdkFloor in project/Toolchain.scala, the value of -release, and the
# build checks at load that the workflows set up that same JDK. On a newer JDK the compiler reads
# only the public API that -release names, so it misses what CI sees, such as a package private
# member of a JDK class that makes a name ambiguous. sbt runs on the java on the PATH, so when that
# is another JDK, the first JDK of the right version in JAVA_HOME or under a directory of
# EEZO_JDK_DIRS goes first on the PATH. When there is none, a warning says so.
EEZO_JDK_WANTED=$(sed -nE 's/^[[:space:]]*val JdkFloor[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' \
  "$ROOT/project/Toolchain.scala" 2>/dev/null | tail -1)
if [ -z "$EEZO_JDK_WANTED" ]; then
  echo "bin/lib/env.sh: no JdkFloor in $ROOT/project/Toolchain.scala" >&2
  return 2 2>/dev/null || exit 2
fi
EEZO_JDK_DIRS=${EEZO_JDK_DIRS:-${SDKMAN_CANDIDATES_DIR:-$HOME/.sdkman/candidates}/java:$HOME/Library/Java/JavaVirtualMachines:/Library/Java/JavaVirtualMachines:/usr/lib/jvm}
EEZO_JDK_MISMATCH=0

eezo_jdk_major() {
  if [ -x "$1/bin/java" ] && [ -f "$1/release" ]; then
    sed -nE 's/^JAVA_VERSION="([0-9]+).*/\1/p' "$1/release" | tail -1
  fi
}

eezo_find_jdk() {
  local rest=$EEZO_JDK_DIRS dir home
  if [ -n "${JAVA_HOME:-}" ] && [ "$(eezo_jdk_major "$JAVA_HOME")" = "$EEZO_JDK_WANTED" ]; then
    echo "$JAVA_HOME"
    return 0
  fi
  while [ -n "$rest" ]; do
    dir=${rest%%:*}
    case "$rest" in *:*) rest=${rest#*:} ;; *) rest= ;; esac
    [ -n "$dir" ] || continue
    for home in "$dir"/* "$dir"/*/Contents/Home; do
      if [ "$(eezo_jdk_major "$home")" = "$EEZO_JDK_WANTED" ]; then
        echo "$home"
        return 0
      fi
    done
  done
}

eezo_jdk_on_path() {
  if [ -z "$EEZO_JDK_HOME" ]; then
    echo "there is no java on the PATH"
  else
    echo "the java on the PATH is JDK ${EEZO_JDK_RUNNING:-of unknown version} at $EEZO_JDK_HOME"
  fi
}

eezo_jdk_switched() {
  echo "$EEZO_TOOL: $(eezo_jdk_on_path), so sbt runs on JDK $EEZO_JDK_WANTED at $1, the JDK CI builds on"
}

eezo_jdk_warning() {
  local w="$EEZO_TOOL: WARNING:"
  echo "$w $(eezo_jdk_on_path), and CI builds on JDK $EEZO_JDK_WANTED."
  echo "$w the compiler reads the JDK it runs on, so code that compiles here can fail on CI."
  echo "$w no JDK $EEZO_JDK_WANTED in JAVA_HOME or under EEZO_JDK_DIRS, which is $EEZO_JDK_DIRS."
  echo "$w install a JDK $EEZO_JDK_WANTED (sdkman: sdk list java, then sdk install java <a $EEZO_JDK_WANTED version>;" \
    "Debian or Ubuntu: apt install openjdk-$EEZO_JDK_WANTED-jdk; or https://adoptium.net)," \
    "or set JAVA_HOME to one."
}

EEZO_JDK_HOME=
EEZO_JDK_RUNNING=
if command -v java >/dev/null 2>&1; then
  eezo_settings=$(java -XshowSettings:properties -version 2>&1)
  EEZO_JDK_HOME=$(printf '%s\n' "$eezo_settings" | sed -n 's/^[[:space:]]*java\.home = //p' | tail -1)
  EEZO_JDK_RUNNING=$(printf '%s\n' "$eezo_settings" \
    | sed -n 's/^[[:space:]]*java\.specification\.version = \([0-9][0-9]*\).*/\1/p' | tail -1)
fi
if [ -z "$EEZO_JDK_HOME" ] || [ "$EEZO_JDK_RUNNING" != "$EEZO_JDK_WANTED" ]; then
  eezo_jdk_found=$(eezo_find_jdk)
  if [ -n "$eezo_jdk_found" ]; then
    eezo_jdk_switched "$eezo_jdk_found"
    export JAVA_HOME="$eezo_jdk_found"
    export PATH="$eezo_jdk_found/bin:$PATH"
  else
    eezo_jdk_warning >&2
    EEZO_JDK_MISMATCH=1
  fi
fi

# Docker through colima: point DOCKER_HOST at the socket when nothing else set it, and pin the
# API version testcontainers needs whenever DOCKER_HOST names a colima socket.
if [ -z "${DOCKER_HOST:-}" ] && [ -S "$HOME/.colima/default/docker.sock" ]; then
  export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
fi
case "${DOCKER_HOST:-}" in
  *colima*)
    case "${JAVA_TOOL_OPTIONS:-}" in
      *api.version*) ;;
      *) export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dapi.version=1.43" ;;
    esac ;;
esac

# A linked worktree needs nothing any more: sbt-ci-release 1.12.1 dropped sbt-git, whose jgit
# probe used to fail there. Earlier gates wrote a local-worktree.sbt that set an sbt-git key, and
# that file now stops sbt from loading, so a leftover one goes.
if [ -f "$ROOT/local-worktree.sbt" ] && grep -q 'gitUncommittedChanges' "$ROOT/local-worktree.sbt"; then
  rm "$ROOT/local-worktree.sbt"
  echo "$EEZO_TOOL: removed the stale local-worktree.sbt an earlier gate wrote for this worktree"
fi

# The repository's git hooks run only once core.hooksPath names them.
if [ "$(git -C "$ROOT" config core.hooksPath)" != ".githooks" ]; then
  if [ "$EEZO_DRY_RUN" -eq 1 ]; then
    echo "$EEZO_TOOL: would set core.hooksPath to .githooks"
  else
    git -C "$ROOT" config core.hooksPath .githooks
    echo "$EEZO_TOOL: installed the repository's git hooks, core.hooksPath is .githooks"
  fi
fi
