<!-- draft -->
# The eezo command line

Every command `bin/eezo` and `sbt run <command>` answer, with their flags and exit codes.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- the launcher versus the application's own dispatch: which commands forward to `run <cmd>` and which are sbt tasks
- commands
  - `new <name>`
  - `dev`, `routes`
  - `status`, `sync [--apply] [--force]`, `freeze <name...> [--accept-all|--skip-destructive]`, `migrate [--apply]`, `reset`, `drop`, `dump`, `ddl`
  - `build`, `deploy [--app <name>] [--target fly]`
  - `help`
- `--json` on every command: the body shape, and the flags it implies
- exit codes: 0, 1 for drift or refusal, and what the launcher filters from sbt's output
- which commands need a database and which never open one
- environment the launcher reads: `.eezo-version`, `SBT_OPTS`

## Where the material is

- `bin/eezo`
- `modules/db/src/main/scala/io/eezo/db/cli/Commands.scala`, `modules/http/.../cli/Commands.scala`, `modules/core/.../Dispatch.scala`
- `examples/todo/README.md`, Command reference
