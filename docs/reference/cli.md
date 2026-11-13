# The eezo command line

Every command `bin/eezo` and `sbt "run <command>"` answer, with flags, exit codes and what each
one touches. The outputs on this page were captured from the bookshelf application the
tutorials build; the version string in them changes with every commit.

## Two layers

`bin/eezo` is a shell script. Four of its commands are its own: `new`, `build`, `deploy` and
`upgrade`. `dev` hands the terminal to the sbt command `eezoDev`, the dev console that the plugin
draws: the log, the compile errors, the route table, the schema drift and the Config's variables
as panes, and the schema commands on keys ([the dev loop](../explanation/the-dev-loop.md) describes it). Everything else
forwards to the application's own `main`. Standing in a module, that is
`sbt "<id>/run <command> <args>"`. A build with one project keeps `sbt "run <command> <args>"`.
`eezo db status` and `sbt "run db status"` are the same code path, and so is the sbt task
`eezoStatus`. A module directory is a valid place to stand: the launcher walks up to the build
root, selects the project that contains that directory, and runs sbt from the build root. At a
build root with several projects, name one with `--project`.

The application's `main` is inherited from its entry trait. Each layer adds its commands: the
http layer's two at the top level, and the database layer's eight under one namespace, `db`, so
that `eezo db sync --apply` reads as what it touches. `config` belongs to both layers, so an
application on either one answers it, once. `help` is matched before all of them, so no layer
can shadow it. No arguments runs the application, after it reads `application.conf` when the
build names a Config class.

## Commands

| command | layer | needs | what it does |
|---|---|---|---|
| `new <name> [--tailwind]`, `new . [--tailwind]` | launcher | `~/.eezo/eezo-version` | scaffolds `./<name>`, or the current directory, named after it, with an `AGENTS.md` that tells a coding agent how to drive the dev loop; `--tailwind` adds a `tailwind.css` and links the stylesheet from the page |
| `dev [--no-tui\|--json] [--project <id>]` | sbt command | a terminal, or not | the dev console: restart on save, the log, the errors, the routes, the drift and the config as panes, `s` `f` `m` for the schema commands, `e` for a value boot refused; `q` or Ctrl-C stops it. `--no-tui` is the same loop printed as lines (`eezoDev --plain`) and `--json` as one JSON object per line (`eezoDev --json`), for a pipe, a log or an agent; without a terminal and without a flag only `eezoDev --once` runs. `--project` names the module when the directory does not |
| `routes [--json]` | http layer | | the mounted table and boot's warnings |
| `config [--json]` | both layers | | the variables `application.conf` names, in file order, without reading them; nothing when the build names no Config class |
| `db` | database layer | | the eight schema commands, listed; `db help` is the same |
| `db status [--json]` | database layer | a database | the model against the live database |
| `db sync [--apply] [--force] [--json]` | database layer | a database | the diff, applied directly with `--apply` |
| `db freeze <name...> [--accept-all\|--skip-destructive] [--json]` | database layer | | writes a migration from the snapshot diff |
| `db migrate [--apply] [--json]` | database layer | a database | lists pending migrations, applies them, verifies |
| `db reset [--json]` | database layer | a database | drops every table and recreates from the model |
| `db drop [--json]` | database layer | a database | drops every table and the ledger |
| `db dump [--json]` | database layer | | prints the model's snapshot |
| `db ddl [--json]` | database layer | | prints the full DDL, one statement per line |
| `build` | launcher | | stages `target/eezo/stage/` |
| `deploy [--app <name>] [--target fly]` | launcher | the Fly CLI | builds, deploys, migrates, waits for health |
| `upgrade` | launcher | Maven Central | moves the command line, what `new` scaffolds, and the project in the current directory to the latest release |
| `help` | both layers | | the command table |

`config` prints one line per substitution in `application.conf`, four fields separated by a tab
and no header: the variable, the path it feeds, `redacted` or `plain`, and `required`,
`defaulted` or `optional`. The words are fixed, so a script reads the lines with
`while IFS=$'\t' read -r variable path redaction need` and needs no JSON parser. The shop's:

```
$ eezo config
STRIPE_SECRET_KEY	stripe.secretKey	redacted	required
STRIPE_WEBHOOK_SECRET	stripe.webhookSecret	redacted	required
SHOP_URL	shopUrl	plain	defaulted
SHOP_EMAIL	createUser.email	plain	optional
SHOP_PASSWORD	createUser.password	plain	optional
```

It reads no environment, so the output is the same with every variable set or none, and it never
reports a value as missing. It checks what the file alone can get wrong: a path fed from a
variable under `EEZO_`, and a `Redacted` path stated as a literal. On either it lists nothing,
writes one line per offender to stderr, and exits 1. When the build names a Config class and
`application.conf` is missing, it exits 1 with one line.

`db freeze` takes everything after it that isn't a flag as the migration's name, joined with
spaces, so `eezo db freeze add isbn to book` names the migration `add_isbn_to_book`. Without a
flag it prompts on each destructive change, `[d]` to drop, `[k]` to keep and skip.

The application also answers the eight without the `db` in front, and `help` doesn't list that
spelling. It exists for one caller: a `fly.toml` written by `eezo deploy` before the namespace,
whose release command says `migrate --apply` and runs inside the new image. The launcher
refuses the bare form and names the `db` one.

`deploy` on a first run, with no `fly.toml`, runs `fly launch`, and in a session with no
terminal that needs `--app <name>`. Before deploying an application with migrations it checks
that a `DATABASE_URL` or `EEZO_DB_URL` secret exists. Then it runs `config` and checks each
variable against the names Fly has. A required variable must be in `fly secrets` or in the
`[env]` table of `fly.toml`; a redacted one must be in `fly secrets`. A redacted variable in
`[env]` is refused even when `fly secrets` has it too, because `fly.toml` is committed. A
defaulted or optional variable may be in neither. The deploy names every offender in one
message and exits 1 before it builds. It reads names only, never a value, and when `config`
itself refuses, its lines are what the deploy prints. After deploying it polls
`/eezo/health` for up to ninety seconds.

## Which commands open a database

`status`, `sync`, `migrate`, `reset` and `drop` run with a database installed, and so does the
application itself. `freeze`, `dump`, `ddl`, `routes`, `config` and `help` never touch one: `freeze`
diffs the model against `db/schema.json`, and the rest read the model alone. `dev` on the
umbrella runs the drift check first, and serves with the database down after a warning. A
refused `application.conf` stops the application and `dev` before either opens a database.

## `--json`

Every layer command takes `--json`, which renders the same result through the JSON renderer.
Exit codes are the same in both modes. Each body carries a `"command"` discriminator. A failure
line goes to stderr as `{"error": "..."}`.

```
$ eezo db status --json
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
| `config` | `variables`, in file order, empty when the build names no Config class; a variable is `variable`, `path`, `redacted` (a boolean) and `need` (`required`, `defaulted`, `optional`) |
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
| 1 | the application or `dev` refused `application.conf`; `config` found a variable under `EEZO_` or a `Redacted` stated as a literal, or `application.conf` is missing; `db status` found drift; `db sync --apply` refused over destructive or risky changes; `db migrate` found the files tampered, or drift remains after the ledger is settled; a schema error; a failed deploy or build |
| 2 | unknown command, or unknown `db` command |

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
  `jdkFloor` and `sbt.version` that `new` writes into a scaffold. The installer reads all four
  off Maven Central: the latest release from the artifact's metadata, the Scala version from
  the release's POM, and the JDK floor and sbt version from two properties the POM carries.
  `upgrade` runs the installer again and then rewrites the versions in the `build.sbt` and
  `project/plugins.sbt` of the current directory, when they name an eezo artifact. In a
  checkout of the repository, `.eezo-version` at its root wins, written by
  `sbt publishLocalForExample` with the locally published version, and `sbt.version` comes from
  `examples/project/build.properties`; `upgrade` refuses to run there.
- **`~/.eezo/tools/`** is the sbt plugin's rather than the launcher's: the tools it fetches,
  one directory per version. Today that is the Tailwind CLI, `tailwindcss-<version>/`, which
  `eezoTailwind` downloads once and every project on the machine shares. `EEZO_HOME` moves it with
  the version file.
- **`SBT_OPTS`**, to which it appends `--enable-native-access=ALL-UNNAMED
  --sun-misc-unsafe-memory-access=allow`, so sbt's own JVM prints no native-access warnings.
  The forwarded commands run sbt with `-error -batch`, so only compile errors and the
  application's output reach the terminal. `dev` runs it with `--server -error` and the terminal
  attached, `-Dsbt.supershell=false` added so that sbt draws no progress while the build loads,
  since the console needs the keyboard and draws the screen itself (once running, the console
  keeps sbt's progress off on its own, so a bare `sbt eezoDev` is clean too); `--server` is what
  keeps an sbt 2 project out of sbt's thin client, which the console refuses to run behind. The forked application gets `run / javaOptions`, not `SBT_OPTS`.
- **`project/plugins.sbt`**, for `dev` alone: the `sbt-eezo` version it pins, read from the build
  root, so a module directory is a valid place to stand. A project on a
  plugin from before the console (0.3.0 and earlier) has only the `~eezoRestart` alias, and for
  it the launcher builds the older line console from sbt's output, as it did before.
- **`fly.toml`**, for the app name and region on deploy.

## What `new` writes

```
<name>/
  project/build.properties      sbt.version
  project/plugins.sbt           addSbtPlugin("io.eezo" % "sbt-eezo" % <version>)
  build.sbt                     the umbrella dependency, EezoPlugin, run / fork := true
  src/main/scala/Main.scala     object Main extends EezoApp, Schema.empty, Routes.table()
  src/main/scala/app/Index.scala   GET /, a starter page that links into the docs
  src/main/resources/assets/welcome.css   the starter page's stylesheet; --tailwind writes tailwind.css instead
  src/main/scala/models/        empty
  .gitignore
  AGENTS.md                     how a coding agent follows the dev loop; an existing one is kept
```

`eezo new .` writes the same files into the current directory.
It refuses if the directory already has a build definition (`build.sbt`, `build.sc`, `build.mill`), a `project/` or a `src/` directory.
An existing `.gitignore` keeps its lines; the entries it lacks are appended to it, and `new` names them.
It refuses a `.gitignore` that is a symlink or that it cannot append to.

## Related

[Configuration](configuration.md) is what the commands read from the environment.
[Migrations](migrations.md) is the files the schema commands write and the ledger they keep.
