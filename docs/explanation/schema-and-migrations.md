# Schema, drift and migrations

The model is the schema, and the database is what drifted. This page is about how eezo proves
that: the snapshot, the diff, the fingerprinted migration file, and the order things run in on
a deploy.

## The model is the schema

A `Schema` object says which models the application has, plus what a type can't say:

```scala
object AppSchema extends Schema {
  val books = table[Book].unique(_.title).index(_.author)
}
```

From that and the `Table` instances, eezo derives a snapshot: every table, its columns with
their Postgres types and nullability, its indexes, its foreign keys. The snapshot is a value,
and every command below is a diff between two of them. `dump` prints it, `ddl` prints the SQL
that would create it from nothing.

The DDL comes out of one renderer, driven by a list of changes, and the fresh-database path is
"the diff from an empty snapshot to this one". So `reset` and `migrate` can't produce
different databases.

## Drift, in three classes

`status` introspects the live database through the catalog and diffs it against the model:

```
2 difference(s) between model and database:

  create table book
  + index uq_book_title on book
```

Each change is one of three kinds:

- **Additive.** A new table, a new column, a new index, a dropped `not null`. Nothing existing
  can break.
- **Destructive.** A dropped table or column. Data goes away.
- **Risky.** A change that can fail on the rows already there: `set not null` over a column
  with nulls, a type change, a new check constraint.

The class decides what happens next. `sync --apply` runs additive changes directly and refuses
to run the other two without `--force`. `freeze` asks you about each destructive change, and
accepts the rest. The dev server banners additive drift and refuses to serve over the other
two.

## Freeze: the snapshot and the file

`freeze <name>` doesn't look at the database at all. It diffs the model against
`db/schema.json`, the snapshot from the last freeze, and writes two files: the next numbered
migration under `db/migrations/`, and the new snapshot. So you can freeze on a plane, and so
two developers who both froze get two files whose numbers collide in the merge, which is the
collision you want to see.

The migration file:

```sql
-- eezo migration 2
-- add_notes
-- fingerprint: c997cd02b5395c97
-- GENERATED. Do not edit; change the model and re-freeze.

alter table "book" add column "notes" text;
```

The fingerprint is a hash of the statements. `migrate` verifies every file against its header
before running anything, and refuses the whole batch if one has been edited:

```
fingerprint mismatch: file says c997cd02b5395c97, content hashes to ...
```

The migration is SQL you can read and commit, and it's also a file you don't edit, because the
model is where the change goes. A change that eezo can't express, a rename that should keep
its data, is a hand-written migration with its own fingerprint, written as a `Manual` decision
in the freeze.

A destructive change gets a prompt, or a flag in place of one: `--accept-all` writes the drop,
`--skip-destructive` leaves it out, and `--json` implies skip, because a machine caller must
never hang on a prompt and skip is the choice that loses nothing.

## Migrate: the ledger

`migrate` keeps a table, `eezo_migrations`, with one row per applied file: its number, its
name, its fingerprint and when it ran. The command compares the directory against the ledger.
A file whose number is in the ledger with a different fingerprint, or a ledger row with no file
behind it, is a tampered history and stops everything. Otherwise the pending files are listed,
and with `--apply` they run, in one transaction with their ledger rows, so a failed batch rolls
back including the record of itself.

After applying, `migrate` verifies: it diffs the live database against the model one more time
and says `database matches model ✓` or lists what still differs. That last check is the one
that catches a migration written by hand that didn't do what the model says.

## Sync: the shortcut

`sync --apply` runs the diff straight into the database and writes no file. It's for a database
you don't care about: a prototype, a throwaway. It blocks on destructive and risky changes
unless you say `--force`, and `--force` isn't selective. It applies everything sync was
blocking, so read the list first.

## The drift gate under `eezo dev`

The dev server checks drift before it serves, and it checks again on every `GET`:

- **Additive drift** prints a banner and serves anyway. A column the database doesn't have yet
  breaks nothing until code touches it.
- **Destructive or risky drift** serves a page instead of the application, with a 503 so a
  poller sees "not serving" and doesn't mistake the page for content. The page lists the
  drift and offers the same two actions the terminal does: apply to the dev database, or decide
  each destructive change and freeze it as a named migration, which is then applied in the same
  submit. Resolving it from the page or from a second terminal turns the next refresh into
  "resolved, save a file to restart".
- **A database that can't be reached** is a warning, not a refusal. `eezo dev` with Postgres
  down serves whatever doesn't need it.

The decisions are on the page and not at the console, because under the dev loop the
application shares stdin with sbt's watch and a prompt would race it for every keystroke. The
page's forms carry the CSRF token like any other, because the buttons apply destructive changes
and any open tab can `POST` to a localhost server.

An application with no schema skips the check entirely, even against a database with tables in
it. Refusing to serve over tables the application never declared would block every schema-less
application that shares a Postgres with something else.

## On deploy

`eezo deploy` writes `release_command = 'migrate --apply'` into `fly.toml`. Fly runs that in a
one-off machine with the new image before any serving machine is touched. A failed migration
exits nonzero, the deploy stops, and the old version keeps serving. So the schema converges
before new code takes traffic, and the ordering comes from the platform, which is better at it than a
script would be. The staged image carries `db/` for exactly this reason: the
migrations the release command runs are the ones `freeze` wrote.

## Postgres only

The type mapping, the catalog introspection, the DDL and the savepoint behaviour are written
against Postgres and nothing else. There's no dialect switch, and `jsonb`, `timestamptz` and
`uuid` are used as themselves. Another database would be a second renderer and a second
introspector, and nothing is designed to stop that; it's work nobody has done.

## Where to go next

The pool and scopes the migrations run through are [the database edge](the-database-edge.md).
The deploy that runs them is [deployment](deployment.md).
