<!-- draft -->
# Configure the database connection

Tell the application where its Postgres is, size the pool, and keep several applications apart in one database.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- the variables: `DATABASE_URL`, or `EEZO_DB_URL`, `EEZO_DB_USER`, `EEZO_DB_PASS`
- the development default, and the port the examples' Docker command uses
- TLS: a platform URL requires it unless the URL says `sslmode` itself; a clear text database elsewhere
- `EEZO_DB_POOL_SIZE`, `EEZO_DB_ACQUIRE_TIMEOUT`, and the 500 a full pool answers with
- `databaseSchema` and `databaseInit`: a Postgres schema per application, both halves and why
- what `sbt run` versus a forked run sees of the environment

## Where the material is

- `docs/tutorials/deploy-to-fly.md` §5
- `examples/blog/src/main/scala/Main.scala`, `examples/reminders/README.md`
- `modules/db/src/main/scala/io/eezo/db/DbInit.scala`, `engine/Pool.scala`, `cli/Conn.scala`
