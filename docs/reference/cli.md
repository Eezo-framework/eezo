# The eezo command line

Every command `bin/eezo` and `sbt "run <command>"` answer, with flags, exit codes and what each
one touches. The outputs on this page were captured from the bookshelf application the
tutorials build; the version string in them changes with every commit.

## Two layers

`bin/eezo` is a shell script. Three of its commands are its own: `new`, `build` and `deploy`.
`dev` runs the sbt task `eezoDev`. Everything else forwards to the application's own `main`
as `sbt "run <command> <args>"`, so `eezo status` and `sbt "run status"` are the same code
path, and so is the sbt task `eezoStatus`.

The application's `main` is inherited from its entry trait. Each edge adds its commands: the
database edge's eight and the http edge's two. `help` is matched before either, so no edge can
shadow it. No arguments runs the application.

## Commands

| command | layer | needs | what it does |
|---|---|---|---|
| `new <name>` | launcher | `~/.eezo/eezo-version` | scaffolds `./<name>` |
| `dev` | sbt task | | serves with restart on save, the route listing and the reload client |
| `routes [--json]` | http edge | | the mounted table and boot's warnings |
| `status [--json]` | database edge | a database | the model against the live database |
| `sync [--apply] [--force] [--json]` | database edge | a database | the diff, applied directly with `--apply` |
| `freeze <name...> [--accept-all\|--skip-destructive] [--json]` | database edge | | writes a migration from the snapshot diff |
| `migrate [--apply] [--json]` | database edge | a database | lists pending migrations, applies them, verifies |
| `reset [--json]` | database edge | a database | drops every table and recreates from the model |
| `drop [--json]` | database edge | a database | drops every table and the ledger |
| `dump [--json]` | database edge | | prints the model's snapshot |
| `ddl [--json]` | database edge | | prints the full DDL, one statement per line |
| `build` | launcher | | stages `target/eezo/stage/` |
| `deploy [--app <name>] [--target fly]` | launcher | the Fly CLI | builds, deploys, migrates, waits for health |
| `help` | both edges | | the command table |

`freeze` takes everything after it that isn't a flag as the migration's name, joined with
spaces, so `eezo freeze add isbn to book` names the migration `add_isbn_to_book`. Without a
flag it prompts on each destructive change, `[d]` to drop, `[k]` to keep and skip.

`deploy` on a first run, with no `fly.toml`, runs `fly launch`, and in a session with no
terminal that needs `--app <name>`. Before deploying an application with migrations it checks
that a `DATABASE_URL` or `EEZO_DB_URL` secret exists. After deploying it polls
`/eezo/health` for up to ninety seconds.

## Which commands open a database

`status`, `sync`, `migrate`, `reset` and `drop` run with a database installed, and so does the
application itself. `freeze`, `dump`, `ddl`, `routes` and `help` never touch one: `freeze`
diffs the model against `db/schema.json`, and the rest read the model alone. `dev` on the
umbrella runs the drift check first, and serves with the database down after a warning.

## `--json`

Every edge command takes `--json`, which renders the same result through the JSON renderer.
Exit codes are the same in both modes. Each body carries a `"command"` discriminator. A failure
line goes to stderr as `{"error": "..."}`.

```
$ eezo status --json
{
  "command": "status",
  "inSync": false,
  "changes": [
    {
      "describe": "- book.notes",
      "destructive": true,
      "risky": false,
      "sql": "alter table \"book\" drop column \"notes\""
    }
  ]
}
```

The shapes, one per command:

| command | fields |
|---|---|
| `routes` | `routes`, `overridden`, `shadowed` (pairs of `earlier`, `later`), `orphans`; a route is `method`, `path`, `provenance` |
| `status` | `inSync`, `changes` |
| `sync` | `applied`, `refused`, `changes`, `blocked` |
| `freeze` | `migration` (path or null), `resolutions` (each a `change` and a `decision`: `accept`, `skip`, `manual`) |
| `migrate` | `outcome`: `tampered` with `problems`; `upToDate` with `drift`; `pending` with `pending`; `applied` with `applied` and `drift` |
| `reset` | `dropped`, `ddl` |
| `drop` | `dropped` |
| `dump` | `schema`, the snapshot |
| `ddl` | `statements` |

A change is `describe`, `destructive`, `risky` and `sql`. A pending migration is `number`,
`file` and `statements`.

`--json` on `freeze` implies `--skip-destructive`, because a machine caller must never hang on
a prompt.

## Exit codes

| code | meaning |
|---|---|
| 0 | done, or in sync |
| 1 | `status` found drift; `sync --apply` refused over destructive or risky changes; `migrate` found the files tampered, or drift remains after the ledger is settled; a schema error; a failed deploy or build |
| 2 | unknown command |

The launcher passes the application's exit code through. sbt prints three lines of its own when
a forked application exits 1, and the launcher drops exactly those, because that exit code is
the command's answer and not a build failure:

```
[error] Nonzero exit code returned from runner: 1
[error] (Compile / run) Nonzero exit code: 1
[error] Total time: ...
```

## What the launcher reads

- **`~/.eezo/eezo-version`**, written by the installer: the `version`, `scalaVersion`,
  `jdkFloor` and `sbt.version` that `new` writes into a scaffold. In a checkout of the
  repository, `.eezo-version` at its root wins, written by `sbt publishLocalForExample` with
  the locally published version, and `sbt.version` comes from
  `examples/project/build.properties`.
- **`SBT_OPTS`**, to which it appends `--enable-native-access=ALL-UNNAMED
  --sun-misc-unsafe-memory-access=allow`, so sbt's own JVM prints no native-access warnings.
  sbt itself runs with `-error -batch`, so only compile errors and the application's output
  reach the terminal. The forked application gets `run / javaOptions`, not `SBT_OPTS`.
- **`fly.toml`**, for the app name and region on deploy.

## What `new` writes

```
<name>/
  project/build.properties      sbt.version
  project/plugins.sbt           addSbtPlugin("io.eezo" % "sbt-eezo" % <version>)
  build.sbt                     the umbrella dependency, EezoPlugin, run / fork := true
  src/main/scala/Main.scala     object Main extends EezoApp, Schema.empty, Routes.table()
  src/main/scala/app/Index.scala   GET /
  src/main/scala/models/        empty
  .gitignore
```

## Related

[Configuration](configuration.md) is what the commands read from the environment.
[Migrations](migrations.md) is the files the schema commands write and the ledger they keep.
