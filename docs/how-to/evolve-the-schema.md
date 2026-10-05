# Change the schema

Add, rename or drop a column or a table, and get the change into every database the
application runs against.

## The loop

Every schema change is the same three commands:

```bash
eezo status                  # what differs between the model and the database
eezo freeze <name>           # write the difference as a migration file
eezo migrate --apply         # run the pending migrations, then verify
```

`status` exits 1 when there's drift, so a CI job can gate on it. `freeze` never opens the
database: it diffs your code against the snapshot it keeps in `db/schema.json`. `migrate`
records what it applied in a ledger table and checks the result against the model.

## Adding things

A new field, a new table, a new index: the easy case.

```scala
case class Book(
    id: Id[Book],
    title: String,
    author: String,
    pages: Int,
    notes: Option[String]   // added just now
) derives Table, Form, Resource
```

```
$ eezo status

1 difference(s) between model and database:

  + book.notes text null
```

```
$ eezo freeze add notes

1 change(s) since last freeze:

  + book.notes text null

wrote db/migrations/0002_add_notes.sql
```

```
$ eezo migrate --apply

  0002  0002_add_notes.sql  (1 statements)

applied ✓
database matches model ✓
```

Under `eezo dev`, additive drift serves with a warning in the console, since a column the
database lacks breaks nothing until code touches it.

## Dropping things

Remove the field and `status` flags the change as destructive:

```
$ eezo status

1 difference(s) between model and database:

  - book.notes  [destructive]
```

`freeze` won't write a destructive change without a decision. Interactively it asks, per change,
one of **keep**, **skip** (the default) or **drop (data lost)**. Non-interactively, pass the
policy:

- `--skip-destructive`: the change is left out of the migration. The snapshot moves on without
  it, the column stays in the database, and `status` keeps reporting it until you decide.
- `--accept-all`: the drop is written into the migration and the data goes with it on apply.

```
$ eezo freeze drop notes --skip-destructive

1 change(s) since last freeze:

  - book.notes  [destructive]  (skipped)

wrote db/migrations/0004_drop_notes.sql
```

A bare `--json` implies `--skip-destructive`, so a script never hangs on the prompt.

Under `eezo dev`, destructive drift is different from additive: the application refuses to
route and every page answers 503 with the drift page. It lists the changes, offers the same
keep, skip or drop decision per destructive one, and has two buttons: apply straight to the dev
database, or freeze as a migration and apply that. The page re-checks on refresh and tells you
when the drift is resolved.

## A `not null` column over existing rows

Adding `owner: Id[User]` to a table that already has rows is the one additive change Postgres
refuses, because the existing rows have nothing to put in the column. `status` classes it as
risky. Two ways through:

- On a database with nothing worth keeping: delete the rows, or `eezo reset`, which drops every
  table and recreates them from the model.
- To keep the rows: add the column as `Option[Id[User]]` first, freeze and migrate, fill it with
  SQL by hand, then change it to `Id[User]`, and freeze and migrate again. The second migration
  narrows the column, which is a destructive-class change, so freeze it with `--accept-all` or
  answer the prompt.

## Renaming

eezo sees a rename as a drop and an add, so renaming a column through `freeze` loses the data
in it. Do a rename as two steps around a hand-written `alter table ... rename column`: migrate the
SQL by hand first, then freeze, and the diff will be empty.

## sync, for databases you don't keep

```bash
eezo sync --apply            # apply the diff directly, no migration file
eezo sync --apply --force    # include the destructive changes too
```

`--force` isn't selective: it applies every change `sync` was blocking, dropped tables and
columns included, so read what a plain `eezo sync` lists first.

## Starting over

```bash
eezo drop     # drops the tables and the migration ledger
eezo reset    # drop, then recreate everything from the model
```

Then delete `db/` if you want the migration history gone too.

## Don't edit a migration

Each file carries a fingerprint of the model it was frozen from, and `migrate` refuses a file
whose fingerprint doesn't match. Change the model and freeze again instead.
