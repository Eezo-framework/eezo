# Configuration

Every environment variable eezo reads, and every override the entry traits offer, with their
defaults. The types are in [the API](/api/); this page is the values.

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
| `boot(): Unit` | abstract on `DbApp`; `EezoApp` supplies the http edge's | the program |
| `databaseSchema: String` | `"public"` | by the commands that introspect |
| `databaseUrl: String` | from the environment, above | when a `Database` is built |
| `databaseUser: String` | from the environment | same |
| `databasePassword: String` | from the environment | same |
| `databasePoolSize: Int` | from the environment, 10 | same |
| `databaseAcquireTimeout: Duration` | from the environment, 5 s | same |
| `databaseInit: Connection -> Unit` | nothing | on every physical connection the pool opens |

A `Database` is built per command and per program run, installed around it, and closed after.
`databaseInit` is where `search_path`, `application_name` and statement timeouts go. An
application that keeps its tables in a schema other than `public` sets both `databaseSchema`
and a `databaseInit` that sets `search_path`, because the first is what the drift commands
read and the second is what every query runs under.

## `LiveApp`

| member | default | read |
|---|---|---|
| `allowedOrigins: Set[String]` | empty | at boot; an entry that isn't an origin fails the boot |

Each entry is `scheme://host` or `scheme://host:port`, matched exactly. The server's own origin
is always admitted and the list only widens it.

## `EezoApp`

All of the above. `program` is the http edge's `boot` under `withDatabase`, and `devServer`
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

[The CLI](cli.md) is what reads these from a terminal. [Deployment files](deploy-files.md) is
where the secrets go on Fly.
