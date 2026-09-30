<!-- draft -->
# Schema, drift and migrations

How the model proves the database: the snapshot, the diff, the fingerprinted migration, and the order things run in on deploy.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- the model is the schema; the database is what drifted
- `status`: introspection against the catalog, the three change classes (additive, destructive, risky)
- `freeze`: the snapshot `db/schema.json`, the diff since the last freeze, the SQL file and its fingerprint
- `migrate`: the ledger, verification after apply, refusing an edited file
- `sync`: the shortcut and what it blocks on
- the drift gate under `eezo dev`: additive serves with a banner, destructive answers 503 with the page
- on deploy: migrations in a one-off machine before traffic moves, the failure that keeps the old version
- dialects: what is Postgres only today

## Where the material is

- `research/migrations.md`
- `modules/db/src/main/scala/io/eezo/db/migrate/*`, `schema/*`, `modules/eezo/.../DriftGate.scala`
- `examples/todo/README.md`
