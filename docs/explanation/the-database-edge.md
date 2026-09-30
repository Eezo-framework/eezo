<!-- draft -->
# The database edge

The connection, the pool, the two scopes a handler opens, the SQL interpolator, and the capture checking that keeps a connection from escaping.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- `Database` installed around `boot`, reaching every thread
- `transact` and `read`: what each opens, the savepoint, `attempt[E]`
- why a connection cannot escape a scope, and what `Tx^` costs downstream (nothing) and gives (a compile error)
- the pool: HikariCP, starting empty, the acquire timeout, the floor version and the reason for it
- the `sql` interpolator, `Query`, `Expr`, `Ref`: the typed layer and what it does not try to be
- `JdbcStore` behind a derived resource; `Crud` returning counts, not exceptions
- why `db` never sees `http`, and what that keeps out of domain code

## Where the material is

- `modules/db/src/main/scala/io/eezo/db/engine/Database.scala`, `Run.scala`, `Scope.scala`, `Pool.scala`, `capability/Capability.scala`
- `research/db-query-layer.md`, `research/capture-checking.md`
- `docs/adr/0001-http-errors-live-in-http-and-carry-no-status.md`
