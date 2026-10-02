# Configure the database connection

Tell the application where its Postgres is, size the pool, and keep several applications apart
in one database.

## The defaults

With nothing set, eezo connects to `jdbc:postgresql://localhost:5442/eezo` as `postgres` with
password `postgres`. This Docker command starts exactly that:

```bash
docker run -d --name eezo-pg -p 5442:5432 \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=eezo postgres:17
```

Port 5442 rather than 5432 so it doesn't collide with a Postgres you may already have.

## Pointing it elsewhere

Two ways, and the first one wins when both are set:

- `EEZO_DB_URL`, `EEZO_DB_USER`, `EEZO_DB_PASS`: a JDBC URL and the credentials, separately.
- `DATABASE_URL` in the `postgres://user:password@host/db` form that Fly, Heroku and Render
  inject. eezo parses it into the three values above.

```bash
EEZO_DB_URL=jdbc:postgresql://db.internal:5432/bookshelf EEZO_DB_USER=app EEZO_DB_PASS=... eezo dev
```

## TLS

A `DATABASE_URL` is treated as a platform URL, and eezo requires TLS on it unless the URL says
otherwise with its own `sslmode`. Fly's `postgres attach` writes `sslmode=disable` on purpose,
since its traffic stays on a private network. A database elsewhere that only speaks clear text
needs `sslmode=disable` appended to the URL, or a raw JDBC URL in `EEZO_DB_URL`, which is taken
as written.

## The pool

- `EEZO_DB_POOL_SIZE`: connections in the pool, default 10.
- `EEZO_DB_ACQUIRE_TIMEOUT`: milliseconds a request waits for a connection before it fails,
  default 5000. A request that waits longer answers 500.

The pool starts empty and grows to the size as connections are needed.

## One database, several applications

Each application can keep its tables in a Postgres schema of its own, so two applications can
share the dev database without their drift bleeding into each other. Two overrides on `Main`,
told once each:

```scala
import java.sql.Connection

object Main extends EezoApp {

  override def databaseSchema: String = "bookshelf"

  override def databaseInit: Connection => Unit = { c =>
    val st = c.createStatement()
    try {
      st.execute("""create schema if not exists "bookshelf"""")
      st.execute("""set search_path to "bookshelf"""")
    } finally st.close()
  }
  // ...
}
```

`databaseInit` runs on every pooled connection, because `search_path` is per connection.
`databaseSchema` points the schema commands' catalog reads at the same name, so `status` compares
your model against the right tables. Both halves are needed; the examples under `examples/` each
do this.

## In tests

Point a second `DbApp` at a throwaway Postgres, such as one from testcontainers, by overriding
`databaseUrl`, `databaseUser` and `databasePassword`. [Test an application](test-an-application.md)
shows the shape.
