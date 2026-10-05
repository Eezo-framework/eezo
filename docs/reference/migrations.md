# Migrations

The files under `db/`, the header a migration carries, the ledger table, and the output of the
commands over them. The files on this page are the bookshelf's from
[the first model](../tutorials/first-model.md).

## `db/schema.json`

The snapshot of the model as of the last `freeze`. `freeze` diffs the model against it and
rewrites it; nothing else touches it. Commit it.

```json
{
  "version": 1,
  "tables": [
    {
      "name": "book",
      "columns": [
        {
          "name": "author",
          "type": "text",
          "nullable": false,
          "primaryKey": false,
          "checks": [],
          "references": null
        },
        ...
      ],
      "indexes": [
        {
          "name": "uq_book_title",
          "columns": ["title"],
          "unique": true
        }
      ]
    }
  ]
}
```

Tables are sorted by name, columns by name, indexes by name. A column's `type` is the rendered
Postgres type. `checks` are the constraint expressions over the unquoted column name.
`references` is the target table's name for a `Ref` column. An index is named
`uq_<table>_<columns>` when unique and `idx_<table>_<columns>` otherwise, columns joined with
`_`.

## `db/migrations/NNNN_name.sql`

```sql
-- eezo migration 1
-- initial_schema
-- fingerprint: afd57a88af6edda1
-- GENERATED. Do not edit; change the model and re-freeze.

create table "book" (
  "author" text not null,
  "id" uuid primary key,
  "pages" integer not null,
  "title" text not null
);

create unique index "uq_book_title" on "book" ("title");
```

- The number is four digits, one more than the highest file already there, starting at 1.
- The name is the one you gave, lower-cased, with every run of non-alphanumerics made one `_`.
- The fingerprint is the first eight bytes of the SHA-256 of the statements joined by newlines,
  as hex. `migrate` recomputes it before running anything and refuses the batch on a mismatch.
- Statements are split on `;`. A semicolon inside a string literal splits a statement in two,
  so a hand-written statement avoids one.

## The ledger

```sql
create table if not exists "eezo_migrations" (
  number integer primary key,
  name text not null,
  fingerprint text not null,
  applied_at timestamptz not null default now()
)
```

Created on first use. `migrate --apply` inserts one row per file it ran, in the same
transaction as the file's statements. `drop` and `reset` drop it. `status` never lists it as
drift.

## Change classification

| change | describe | class |
|---|---|---|
| create table | `create table t` | additive |
| drop table | `drop table t` | destructive |
| add column | `+ t.c type null` or `not null` | additive |
| drop column | `- t.c` | destructive |
| change column type | `~ t.c from -> to` | risky |
| drop not null | `~ t.c drop not null` | additive |
| set not null | `~ t.c set not null` | risky |
| add check | `+ check t.c: expr` | risky |
| drop check | `- check t.c: expr` | additive |
| add foreign key | `+ fk t.c -> target` | additive |
| drop foreign key | `- fk t.c` | additive |
| create index | `+ index name on t` | additive |
| drop index | `- index name on t` | additive |

Destructive loses data. Risky can fail on the rows already there. `sync --apply` refuses both
without `--force`; `freeze` prompts on destructive and accepts risky; the dev server's drift
page refuses to serve over either. A renamed column is a drop and an add.

## The DDL per change

Identifiers are double-quoted. The order within one freeze is: new tables, their foreign keys,
alterations to existing tables, new tables' indexes, dropped tables. Within one table: added
columns, their foreign keys, type and nullability changes (dropped checks first, then the type,
then nullability, then added checks), dropped indexes, created indexes, dropped columns.

| change | SQL |
|---|---|
| create table | `create table "t" ("c" type [not null] [primary key] [check (...)], ...)` |
| drop table | `drop table "t"` |
| add column | `alter table "t" add column "c" type [not null] [check (...)]` |
| drop column | `alter table "t" drop column "c"` |
| change type | `alter table "t" alter column "c" type to using "c"::to` |
| nullability | `alter table "t" alter column "c" drop not null` / `set not null` |
| add check | `alter table "t" add constraint "ck_t_c_<hash>" check (expr)` |
| drop check | `alter table "t" drop constraint "ck_t_c_<hash>"` |
| add foreign key | `alter table "t" add constraint "fk_t_c" foreign key ("c") references "target" ("id")` |
| drop foreign key | `alter table "t" drop constraint "fk_t_c"` |
| create index | `create [unique] index "name" on "t" ("c", ...)` |
| drop index | `drop index "name"` |

## `freeze`

| flag | effect |
|---|---|
| none | destructive changes prompt: `[d] drop (data lost)   [k] keep, skip` |
| `--accept-all` | every change is written |
| `--skip-destructive` | destructive changes are left out of the file |
| `--json` | implies `--skip-destructive` |

A skipped change is still recorded in the new snapshot, so it isn't offered again. When the
model matches the snapshot the command says `nothing to freeze` and writes nothing. Text
output:

```
2 change(s) since last freeze:

  create table book
  + index uq_book_title on book

wrote db/migrations/0001_initial_schema.sql
```

## `migrate`

| outcome | text | exit |
|---|---|---|
| pending, no `--apply` | the files, then `--apply to execute` | 0 |
| applied | the files, `applied ✓`, then the verification | 0, or 1 when drift remains |
| nothing pending | `no pending migrations`, then the verification | 0, or 1 when drift remains |
| tampered | each problem, then `migration integrity check failed.` | 1 |

The verification line is `database matches model ✓`, or the remaining changes and
`database does not match model (N difference(s))`. The JSON form:

```
$ eezo migrate --json
{
  "command": "migrate",
  "outcome": "upToDate",
  "drift": [
    {
      "describe": "- book.notes",
      "destructive": true,
      "risky": false,
      "sql": "alter table \"book\" drop column \"notes\""
    }
  ]
}
```

## `status`

`in sync ✓` with exit 0, or `N difference(s) between model and database:` and the list, each
line tagged `[destructive]` or `[risky]` where it applies, with exit 1.

## Related

[Schema, drift and migrations](../explanation/schema-and-migrations.md) is how the pieces fit.
[Changing the schema](../how-to/evolve-the-schema.md) walks a destructive change through.
