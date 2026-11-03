# Configuration

Every environment variable eezo reads, the application's own `application.conf`, and every
override the entry traits offer, with their defaults. The types are in [the API](/api/); this page is the values.

## Environment

| variable | read by | default | notes |
|---|---|---|---|
| `EEZO_SECRET` | `HttpApp.secret` | a throwaway, with a WARNING | at least 32 bytes; `openssl rand -base64 32` |
| `EEZO_DB_URL` | `DbInit.databaseUrl` | see `DATABASE_URL` | a JDBC URL, passed to the driver as written |
| `EEZO_DB_USER` | `DbInit.databaseUser` | see `DATABASE_URL` | |
| `EEZO_DB_PASS` | `DbInit.databasePassword` | see `DATABASE_URL` | |
| `DATABASE_URL` | the three above, when they're unset | `jdbc:postgresql://localhost:5442/eezo`, `postgres`, `postgres` | the `postgres://user:pass@host/db` form a platform injects |
| `EEZO_DB_POOL_SIZE` | `DbInit.databasePoolSize` | 10 | below 1, or not a number, falls back to the default |
| `EEZO_DB_ACQUIRE_TIMEOUT` | `DbInit.databaseAcquireTimeout` | 5000 | milliseconds; below 250 falls back to the default |

The `EEZO_DB_*` variables always win. When all three are unset and `DATABASE_URL` is set, it's
parsed into them. The rules of that parse:

- The scheme is `postgres` or `postgresql`, and a host and a database name are required.
  Anything else is ignored and the defaults apply.
- User and password come out of the URL percent-decoded. No user info means `postgres` with an
  empty password.
- `sslmode=require` is appended when the query names no `sslmode`. A query that names one, with
  any value, travels byte for byte.

Under `eezo dev` the sbt plugin sets `EEZO_SECRET` to one value per sbt session, unless sbt's
own environment already has one.

## `application.conf`

The application reads its own values from `src/main/resources/application.conf` into a case
class that derives `Config`. The build names the class, fully qualified:

```scala
eezoConfigClass := Some("shop.ShopConfig")
```

The case class must be in a named package. The plugin writes an object in `io.eezo.generated`
that names the class, and code in a named package cannot name a class in the empty package.

Boot reads the file before the application starts: for `sbt run` with no arguments, and for
`dev`. The read occurs before the database layer builds a `Database`, before the drift check,
and before the server binds its port. Boot does these steps:

1. It parses the application's own `application.conf`, the first one on the classpath. It does
   not merge `reference.conf`, the system properties, `-Dconfig.file`, or an `application.conf`
   from a dependency.
2. It resolves each substitution from the process environment.
3. It installs the value. A handler reads it with `Config[ShopConfig].current`, and one section
   with `Config[Stripe].current`. A suite sets its own value for one block with
   `Config[ShopConfig].installed(value) { ... }`.

When the read fails, boot writes one line per offender to stderr and exits with code 1. It
writes no stack trace and starts nothing. Each line names a path and a variable, never a value:

```
[eezo] port is missing: add it to application.conf
[eezo] stripe.secretKey is missing: set STRIPE_SECRET_KEY, which application.conf names for it
```

When the build names a class and the file is not on the classpath, boot exits with code 1 and
one line, `[eezo] application.conf is missing: ...`. When the build names no class, boot reads
nothing, and a file there has no effect.

Under `eezo dev` a refused read shows the same lines in the log. Each save restarts the
application, and the restart reads the file again.

The other commands do not read the file's values: `routes`, `config`, `help` and the `db`
commands work when a variable the file needs is unset. [`eezo config`](cli.md) lists the variables the file names, and checks
the two things the file alone can get wrong: a substitution of a variable under `EEZO_`, and a
`Redacted` path stated as a literal. The framework's own variables, in the table above, stay
out of the file.

## `HttpApp`

| member | default | read |
|---|---|---|
| `routes: RouteTable` | abstract | when `serve` runs |
| `port: Int` | 8080 | at boot |
| `maxBodySize: Long` | 1 MiB | at boot; also the WebSocket text message cap |
| `secret: Secret` | `Secret.fromEnv()` | once, when `serve` boots |
| `problems: PartialFunction[Throwable, Problem]` | empty | per failure |
| `boot(): Unit` | `serve(routes)` | the program |
| `frameworkRoutes: Seq[Route]` | empty | at boot; `LiveApp` adds its two |

`serve(table, dev)` is `protected final` and is the only way to start the server. An
overridden `boot` that still wants to serve calls it.

## `DbApp`, through `DbInit`

| member | default | read |
|---|---|---|
| `schema: Schema` | abstract | per command |
| `boot(): Unit` | abstract on `DbApp`; `EezoApp` supplies the http layer's | the program |
| `databaseSchema: String` | `"public"` | on every physical connection the pool opens, and by the commands that introspect |
| `databaseUrl: String` | from the environment, above | when a `Database` is built |
| `databaseUser: String` | from the environment | same |
| `databasePassword: String` | from the environment | same |
| `databasePoolSize: Int` | from the environment, 10 | same |
| `databaseAcquireTimeout: Duration` | from the environment, 5 s | same |
| `databaseInit: Connection -> Unit` | nothing | on every physical connection the pool opens, after the schema step |

A `Database` is built per command and per program run, installed around it, and closed after.
`databaseSchema` is where the application's tables live and what the drift commands read. Any
name but `public` makes every physical connection create that schema when the catalog says it is
missing and set `search_path` to it alone, so the DDL, the migration ledger and every query land
where `status` looks; `public` runs nothing and leaves Postgres's own `search_path`. The name is
quoted as an identifier, so it is used exactly as written. `databaseInit` is where
`application_name`, statement timeouts and any other per connection setting go; it runs after the
schema step and needs no `super` call to keep it.

## `LiveApp`

| member | default | read |
|---|---|---|
| `allowedOrigins: Set[String]` | empty | at boot; an entry that isn't an origin fails the boot |

Each entry is `scheme://host` or `scheme://host:port`, matched exactly. The server's own origin
is always admitted and the list only widens it.

## `EezoApp`

All of the above. `program` is the http layer's `boot` under `withDatabase`, and `devServer`
runs the drift check first.

## Build settings

| setting | default | notes |
|---|---|---|
| `run / fork` | `true` in the scaffold | the application needs the JDK floor |
| `run / connectInput` | `true`, set by the plugin | so `freeze` can prompt |
| `Compile / run / mainClass` | sbt's choice | set it to `Main` once a second `main` exists, such as a `CreateUser` |
| `eezoJavaVersion` | `"25"` | the JRE image `eezoStage` writes into the Dockerfile |

## Fixed limits

| limit | value |
|---|---|
| request body and WebSocket text message | `maxBodySize`, 1 MiB |
| session cookie, encoded | 3800 bytes; past it, encoding throws |
| CSRF token | 32 bytes, base64url |
| sign-in lifetime | 14 days from the sign in, `Guard(lifetime = ...)` to change |
| WebSocket idle timeout | 5 minutes |
| outgoing WebSocket frames queued | 64 |
| drain on stop | 3 seconds |
| live page registry | 10000 pages |
| live page grace after a dropped socket | 60 seconds |
| live page never connected | reaped after 30 seconds |

## Related

[The CLI](cli.md) is what reads these from a terminal, and `eezo config` lists the variables
`application.conf` names. [Deployment files](deploy-files.md) is
where the secrets go on Fly.
