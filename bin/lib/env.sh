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
