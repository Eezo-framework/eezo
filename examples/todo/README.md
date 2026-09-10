# The todo app — a guided tour of the eezo CLI

| | |
|---|---|
| artifact | `"io.eezo" %% "eezo"` (the umbrella) |
| entry trait | `io.eezo.EezoApp` |
| edges | database and http |

One model deriving `Table, Form, Resource`, an `AppSchema` with two indexes, two handwritten
routes, and a Postgres schema of its own. Everything the CLI currently does can be exercised from
this directory. Every output block below was captured from a real run.

Because `Todo` carries a `Table`, the derived CRUD pages read and write rows in Postgres through a
`JdbcStore`, and the schema commands manage the table those rows live in. The table has to exist
before the first request, which is what section 3 does; a todo created in the browser is still
there after a restart.

## 0. Setup

You need Docker (for Postgres) and the locally published eezo:

```bash
# a dev Postgres on the port eezo defaults to (skip if eezo-pg already runs)
docker run -d --name eezo-pg -p 5442:5432 \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=eezo postgres:17

# at the repository root: publish eezo + sbt-eezo locally, record the version
sbt publishLocalForExample
```

Then work from `examples/`. An interactive session is nicer (`sbt`, then the commands without
quotes), but every command below also works as a one-shot `sbt -batch "..."`.

```bash
cd examples
sbt
```

The app serves on **8090** (`Main.scala` overrides `port` so the tour never collides with
whatever your machine runs on 8080) and keeps its tables in a Postgres schema named `todo`
(`databaseSchema` + the `databaseInit` hook — see `Main.scala` for why both halves exist).

## 1. The code

- `src/main/scala/models/Todo.scala` — the model. `derives Table, Form, Resource` is the whole
  declaration: table, HTML form, seven CRUD routes.
- `src/main/scala/AppSchema.scala` — registers the table and adds what types alone cannot say:
  `table[Todo].index(_.done).unique(_.title)`. The selectors are typed; rename a field and the
  index breaks at compile time.
- `src/main/scala/app/Index.scala` — `GET /` by filename convention. A handler is a plain
  `Request => Response` on a virtual thread.
- `src/main/scala/app/Health.scala` — a custom name mounts a GET at its own segment:
  `GET /health`.
- `src/main/scala/Main.scala` — the entry point: name `schema` and `routes`, done. `main` is
  inherited from `EezoApp` and dispatches. `EezoApp` is the umbrella's trait, both edges stacked;
  an application with one edge extends that edge's trait instead (`../hello`, `../reminders`).

## 2. The route table — no database needed

```
sbt:examples> todo/run routes
```

```
9 routes:
  GET /health
  GET /
  GET /todos
  GET /todos/new
  GET /todos/:id
  GET /todos/:id/edit
  POST /todos
  PUT /todos/:id
  DELETE /todos/:id
```

Handwritten routes first (most-static-first, so `/todos/new` beats `/todos/:id`), then the seven
derived ones. This command never opens a database connection — stop Postgres and it still answers.
`todo/eezoRoutes` is the same thing as an sbt task.

## 3. Status → freeze → migrate: the reviewed path

The `todo` Postgres schema starts empty, so the model *is* the drift:

```
sbt:examples> todo/run status
```

```
3 difference(s) between model and database:

  create table todo
  + index idx_todo_done on todo
  + index uq_todo_title on todo
```

(The task "fails" — drift is exit code 1 on purpose, so CI can gate on it.)

Freeze it as the first migration. Everything after `freeze` that is not a flag is the name — no
quoting needed:

```
sbt:examples> todo/run freeze initial schema
```

```
3 change(s) since last freeze:

  create table todo
  + index idx_todo_done on todo
  + index uq_todo_title on todo

wrote db/migrations/0001_initial_schema.sql
```

Open `todo/db/migrations/0001_initial_schema.sql` — committed SQL with a fingerprint header (edit
it by hand and `migrate` will refuse, telling you to change the model and re-freeze instead).
`todo/db/schema.json` is the snapshot the *next* freeze diffs against.

Preview, then apply:

```
sbt:examples> todo/run migrate
```

```
  0001  0001_initial_schema.sql  (3 statements)

--apply to execute
```

```
sbt:examples> todo/run migrate --apply
```

```
  0001  0001_initial_schema.sql  (3 statements)

applied ✓
database matches model ✓
```

```
sbt:examples> todo/run status
```

```
in sync ✓
```

`todo/eezoFreeze initial schema` and `todo/eezoMigrate --apply` are the same commands as sbt
tasks; `sync --apply` is the no-migration-file shortcut for throwaway prototyping.

## 4. Serve it

```
sbt:examples> todo/run
```

Open <http://localhost:8090>. Click through: the homepage (try `/?name=you`), `/health`, and
`/todos` — create a couple of todos, edit one, delete one. That whole CRUD flow is derived from
the case class; the form's optional `notes` and the `done` checkbox come from `Option[String]` and
`Boolean`. Ctrl+C (or `enter` in the sbt shell) stops it.

## 5. The dev loop

```
sbt:examples> project todo
sbt:todo> eezoDev
```

First run compiles and starts the app (`eezo dev: started Main`), with the drift check and route
listing on. Now, **without stopping anything**:

1. Edit `app/Index.scala` — change the `h1` text — and save.
2. Watch the console: recompile, restart.
3. Refresh <http://localhost:8090> — your change is live. Budget is well under a second of
   compile plus ~a quarter second of JVM boot.

Break the file on purpose (delete a `)`) and save: the compile fails, **the old server keeps
serving**. Fix it and save: back in the loop. `enter` stops the watch; `eezoStop` kills the app if
you want it gone before then.

## 6. Additive drift: banner, but serving

With `eezoDev` still running, add a field to `Todo`:

```scala
    done: Boolean,
    priority: Option[Int]
```

Save. The restarted server prints a warning but serves — a column the database lacks breaks
nothing until code touches it:

```
[eezo] ⚠ the database does not match your models; additive only, serving anyway
1 difference(s) between model and database:

  + todo.priority integer null
```

Keep it: `todo/run freeze add priority` then `todo/run migrate --apply` from a second terminal
(or the drift page below does both in one submit). Next restart boots clean.

## 7. Destructive drift: the interactive refusal page

Now *remove* the `notes` field from `Todo` and save. The restart finds destructive drift and
refuses to route: every path on <http://localhost:8090> answers **503** with the drift page:

- the change list, flagged (`- todo.notes  [destructive]`)
- per destructive change, the decision the CLI's freeze prompt would ask in the terminal:
  **keep, skip** (default) or **drop (data lost)**
- two buttons: **apply to the dev database** (sync, no migration written) or
  **freeze as a migration and apply it** (name field + your decisions → `000N_*.sql` + applied)

Pick either. The page re-checks on every refresh, so it flips to *"resolved ✓ — save any file to
restart"* — save a file (or press `enter` and rerun `eezoDev`) and the app serves again. If an
action fails, the database's own error renders on the page — for example, adding a non-null
column over existing rows is a `[risky]` change that Postgres may refuse; that refusal is the
flag doing its job.

The decisions are on the page rather than in the terminal because under `eezoDev` the forked app
shares stdin with sbt's watch — a console prompt would race it for every keystroke.

## 8. Start over

```
sbt:examples> todo/run drop          # drops the todo tables and the migration ledger
```

then delete `todo/db/` and revert your model edits. (`reset` is the one-step alternative to
drop + sync when you don't care about migrations.)

## Command reference (current shape)

| command | needs db | what it does |
|---|---|---|
| `todo/run` | no* | serve on 8090 |
| `todo/run dev` / `eezoDev` | no* | serve + drift check + listing; `eezoDev` adds restart-on-save |
| `todo/run routes` / `eezoRoutes` | no | the table, with shadow/override/orphan warnings |
| `todo/run status` / `eezoStatus` | yes | model vs database diff; exit 1 on drift |
| `todo/run sync --apply [--force]` / `eezoSync` | yes | apply the diff directly; destructive needs `--force` |
| `todo/run freeze <name>` / `eezoFreeze <name>` | no | write drift-since-last-freeze as a migration |
| `todo/run migrate [--apply]` / `eezoMigrate` | yes | list/apply pending migrations, then verify |
| `todo/run drop` / `reset` | yes | drop everything / drop and recreate from the model |
| `todo/run dump` / `ddl` | no | the derived snapshot / full DDL |
| `todo/run help` | no | the list above |

\* serves without Postgres; the dev drift check just logs "skipped, database unreachable".

**The launcher:** `bin/eezo` (at the repo root) wraps all of this for a standalone project:
`eezo new myapp` scaffolds one against the locally published eezo, and inside it `eezo routes`,
`eezo status --json`, `eezo dev` forward to the same dispatch these `todo/run ...` commands hit.

**For agents and scripts:** every command takes `--json` — same exit codes, machine-readable
body, each change carrying its `sql` alongside the `destructive`/`risky` classification. Try
`todo/run status --json`. `freeze` swaps its prompt for a policy flag: `--accept-all` or
`--skip-destructive` (bare `--json` implies the latter, so a machine caller never hangs on a
prompt).
