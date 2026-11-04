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

When the build names no class, nothing reads the file, and a file there has no effect.
[Configuration from one file](../explanation/configuration.md) tells why the rules below are
what they are.

### Rules of the file

- Boot reads the first `application.conf` on the classpath, which is the application's own. It
  does not merge `reference.conf`, the system properties, `-Dconfig.file`, or an
  `application.conf` from a dependency.
- The file is HOCON. A field is read at its full dotted path, and the fields of a section are in
  the block under the section's name.
- A value is stated in the file, or fed from a variable through a substitution. Nothing else
  feeds a value.
- A substitution must not name a variable under `EEZO_`. That prefix is the framework's, and the
  framework's own variables, in the table above, stay out of the file.
- A `Redacted` field, or an `Option` of one, is never a literal in the file, also when the file
  copies the text in from another of its paths. It comes from a variable only.
- The class is in a named package. The plugin writes an object in `io.eezo.generated` that names
  the class, and code in a named package cannot name a class in the empty package.
- A field the file does not state, or states as `null`, is absent. A field fed through `${?VAR}`
  from an unset variable reads the literal that the file writes for the same path before the
  substitution, and with no such literal it is absent. An absent field takes its default when it
  has one, an absent `Option` field is `None`, and any other absent field is missing. Only the
  class decides this; what the field's `Parser` says of absent text is not used.
- A section is a field whose type has a `Config` of its own. It has no default, it is not an
  `Option`, and its type is at one path of the class only.

### The `${?VAR}` idiom

`path = ${?VAR}` feeds the path from the variable `VAR`. The question mark makes the
substitution optional: when `VAR` is unset, the literal written for the path before the
substitution applies, and with no literal the path is absent and the rules above decide. The
file names each variable. eezo makes no variable name from a path, so a variable that the file
does not name never reaches a value. The shop feeds its Stripe section this way:

```hocon
stripe {
  secretKey = ${?STRIPE_SECRET_KEY}
  webhookSecret = ${?STRIPE_WEBHOOK_SECRET}
}
```

At compile time a `${?VAR}` counts as a value for its path, whether or not `VAR` is set. Whether
`eezo config` lists a variable as `required`, `defaulted` or `optional` comes from the field in
the class alone. A literal that the file writes for the same path does not change it.

### When boot reads it

Boot reads the file for `sbt run` with no arguments, and for `dev`. The read occurs before the
database layer builds a `Database`, before the drift check, and before the server binds its
port. Boot does these steps:

1. It parses the file, as the rules above say.
2. It resolves each substitution from the process environment.
3. It installs the value. A handler reads it with `Config[ShopConfig].current`, and one section
   with `Config[Stripe].current`. A suite sets its own value for one block with
   `Config[ShopConfig].installed(value) { ... }`.

`current` before any install throws an `IllegalStateException` that names `installed`.

When the read fails, boot writes one line per offender to stderr and exits with code 1. It
writes no stack trace and starts nothing. A substitution under `EEZO_` comes first, then each
substitution that does not resolve, and the other fields follow in the order the class declares
them. Under `eezo dev` a refused read shows the same lines in the log. Each save restarts the
application, and the restart reads the file again.

The other commands do not read the file's values: `routes`, `config`, `help` and the `db` commands
work when a variable the file needs is unset.

### Offender lines

Each line names a path, and a variable where there is one, and never a value. `<path>` is the
dotted path and `<VAR>` a variable. With `--json`, each line is a `{"error": "..."}` object
instead, with the same text after `[eezo] `.

| when | line |
|---|---|
| a required field the file does not name | `[eezo] <path> is missing: add it to application.conf` |
| a required field fed from an unset variable | `[eezo] <path> is missing: set <VAR>, which application.conf names for it`; two or more variables are joined with ` or ` |
| a value its `Parser` does not read | `[eezo] <path> <reason>`, with the reason of the field's `Parser` |
| an object where one value is expected | `[eezo] <path> is an object, and one value was expected` |
| a list where one value is expected | `[eezo] <path> is a list, and one value was expected` |
| one value where a section is expected | `[eezo] <path> is a value, and a section was expected` |
| a substitution that does not resolve | `[eezo] <path> cannot be resolved: it joins a list or an object with a single value, or substitutes itself`; `application.conf` in place of the path when no one path is to blame |
| a variable under `EEZO_` | `[eezo] <path> is fed from <VAR>, and EEZO_ is the framework's prefix for its own variables: name another variable in application.conf` |
| a `Redacted` stated as a literal | `[eezo] <path> is stated as a literal in application.conf, and it is a Redacted: a Redacted value reaches the file only through a variable` |
| the build names a class and the file is not on the classpath | `[eezo] application.conf is missing: the build names a Config class with eezoConfigClass, and boot reads it from src/main/resources/application.conf` |
| the file does not parse | `[eezo] application.conf does not parse at line <n>: fix its syntax there.`; without ` at line <n>` when the parser gives no line |

The reasons of the `Parser`s that eezo ships. The `Parser`s of `String` and `Redacted` read any
text:

| type | reason |
|---|---|
| `Int`, `Long`, `Double` | `is not a number` |
| `Boolean` | `is not true or false` |
| `UUID`, `Id` | `is not an id` |
| `LocalDate` | `is not a date` |
| `LocalDateTime`, `Instant` | `is not a date and time` |
| `Url` | `is not a url` |
| `Password`, from `eezo-auth` | `is required` for empty text, `is longer than the 72 bytes bcrypt reads` |

The shop, started with neither Stripe variable set, prints:

```
[eezo] stripe.secretKey is missing: set STRIPE_SECRET_KEY, which application.conf names for it
[eezo] stripe.webhookSecret is missing: set STRIPE_WEBHOOK_SECRET, which application.conf names for it
```

### `eezo config`

`eezo config` lists the variables the file names, in
file order, and [the CLI reference](cli.md#commands) gives the format of its lines. It reads the
file and never the environment, so it never reports a value as missing, and it prints the same list
with every variable set or none. It refuses only what the file alone can get wrong: a missing file,
a file that does not parse, a substitution under `EEZO_`, and a `Redacted` stated as a literal. Then
it lists nothing, writes the lines of the table above to stderr, and exits with code 1. When the
build names no class, it prints nothing and exits with code 0.

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
