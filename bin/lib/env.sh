# The environment every sbt run in this checkout needs, in one place. bin/gate and bin/fast both
# source this file, so the two cannot drift. Why each line exists is in the header of bin/gate.
#
# The caller sets ROOT, the checkout, before sourcing. It may set DRY_RUN=1, which keeps the git
# config untouched, and TOOL, the name printed in front of each message, which defaults to gate.

if [ -z "${ROOT:-}" ]; then
  echo "bin/lib/env.sh: ROOT is not set" >&2
  return 2 2>/dev/null || exit 2
fi
EEZO_TOOL=${TOOL:-gate}
EEZO_DRY_RUN=${DRY_RUN:-0}

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

# A linked worktree: turn sbt-git's uncommitted changes probe off, or sbt does not load.
if [ -f "$ROOT/.git" ] && [ ! -f "$ROOT/local-worktree.sbt" ]; then
  printf '// Written by bin/gate for this linked worktree, see its header. Never commit it.\nThisBuild / git.gitUncommittedChanges := false\n' > "$ROOT/local-worktree.sbt"
  exclude="$(git -C "$ROOT" rev-parse --git-common-dir)/info/exclude"
  grep -qx 'local-worktree.sbt' "$exclude" 2>/dev/null || echo 'local-worktree.sbt' >> "$exclude"
  echo "$EEZO_TOOL: wrote local-worktree.sbt for this worktree"
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
