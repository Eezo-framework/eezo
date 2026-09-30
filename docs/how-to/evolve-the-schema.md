<!-- draft -->
# Change the schema

Add, rename or drop a column or a table, and get the change into every database the application runs against.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- `eezo status`: model versus live database, exit code 1 on drift
- the additive path: change the model, `freeze <name>`, `migrate --apply`
- destructive drift: what `freeze` prompts for (keep, skip, drop), `--accept-all`, `--skip-destructive`
- what `sync --apply` blocks on and what `--force` lets through, and why not to use it on data you want
- the drift page under `eezo dev`: the same decisions in the browser
- a `not null` column over existing rows: the risky class, and the two step workaround
- reading a migration file: the fingerprint header, why editing it by hand is refused
- gating CI on `status --json`
- starting over: `drop`, `reset`, deleting `db/`

## Where the material is

- `examples/todo/README.md` §3, §6, §7, §8
- `examples/blog/README.md`, the paragraph on adding `author` over existing rows
- `modules/db/src/main/scala/io/eezo/db/migrate/Freeze.scala`, `Migrator.scala`, `schema/Differ.scala`, `schema/Change.scala`
