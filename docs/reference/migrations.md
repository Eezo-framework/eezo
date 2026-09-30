<!-- draft -->
# Migrations

The files under `db/`, the header a migration carries, the ledger table, and the commands over them.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- `db/schema.json`: the snapshot and its fields
- `db/migrations/NNNN_name.sql`: numbering, the fingerprint header, statements
- the ledger table: name, columns, what `migrate` writes
- change classification: additive, destructive, risky, per change kind
- the DDL emitted per change kind, with identifier quoting
- `freeze` decisions and flags; `migrate` output; `status` output; the `--json` shapes

## Where the material is

- `modules/db/src/main/scala/io/eezo/db/migrate/*`, `schema/Ddl.scala`, `schema/Change.scala`, `schema/Snapshot.scala`, `internal/SnapshotJson.scala`
- `examples/todo/db/` after running the tour
