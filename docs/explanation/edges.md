# The two edges

An eezo application talks to a database, serves HTTP, or does both, and it says which by the
artifact it depends on. This page is about what that choice buys you, what it forbids, and how
the two halves fit together when you have both.

## What an edge is

An edge is one side of the application that eezo brings up before your code runs and takes
down after:

- **The database edge** is the connection pool and the schema commands. eezo builds the pool
  from your settings, installs it where `transact` and `read` can find it, runs your program,
  and closes it afterwards.
- **The http edge** is the server and the routes. eezo starts Jetty on your port, serves your
  route table, and on shutdown refuses new requests, finishes the ones in flight, and only
  then returns, so the database edge closes after the last request.

A derivation belongs to one edge. `Table` is the database edge's. `Form` and `Resource` are the
http edge's. If your application doesn't have an edge, that edge's derivations aren't on the
classpath, and asking for one is a compile error.

## Three artifacts, three entry traits

| artifact | entry trait | edges |
|---|---|---|
| `eezo-http` | `HttpApp` | http |
| `eezo-db` | `DbApp` | database |
| `eezo` | `EezoApp` | both |

`eezo`, the umbrella, is the default. It's what `eezo new` puts in your build, and `EezoApp` is
one trait that has everything: `routes` and the server's overrides from `HttpApp`, `schema` and
the database lifecycle from `DbApp`, and the live socket from `LiveApp` in between. An
application that only needs one edge depends on that edge's artifact and extends its trait. The
examples in the repository cover all three: `hello` is `eezo-http` alone, `reminders` is
`eezo-db` alone, `blog` and `todo` are the umbrella.

## What the missing edge looks like

The `hello` example has the http edge only. Its README tries a model deriving `Table` and shows
the compiler's answer:

```
[error] -- [E008] Not Found Error: hello/src/main/scala/models/Note.scala:4:15
[error] 4 |import io.eezo.db.Table
[error]   |       ^^^^^^^^^^
[error]   |       value db is not a member of io.eezo
```

The `reminders` example is the mirror image: `eezo-db` alone, and `derives Form` fails on the
import of `io.eezo.http.Form` the same way. There's no flag to flip. The package isn't there
because the artifact isn't there, and the cure is one line in `build.sbt`: depend on `eezo` and
extend `EezoApp`.

The commands follow the same rule. `main` is inherited from a small dispatch trait both edges
share, and each edge adds its own commands in front of it. On the umbrella, `help` lists all of
them:

```
eezo
  status                    code vs live database
  sync [--apply] [--force]  apply the code/db diff directly (dev only)
  freeze <name>             write a migration from schema.json -> code
  migrate [--apply]         apply pending migrations, then verify
  reset                     drop everything and recreate from the model
  drop                      drop everything
  dump                      print the derived snapshot
  ddl                       print full DDL
  dev                       serve with the route listing and the reload client on
  routes                    the mounted table, with boot's warnings
  help                      this list

freeze takes --accept-all / --skip-destructive in place of the prompt
(bare --json implies --skip-destructive)

every command takes --json for machine-readable output (same exit codes);
no arguments runs the application
```

The first eight are the database edge's, the next two are the http edge's. On `eezo-http`
alone, `status` is an unknown command, and on `eezo-db` alone so is `dev`. Every command takes
`--json`, with the same exit codes, so a script gates on the code and parses the body.

## Stacking the two

Each edge has a `program` method, which is what running with no arguments does. On the http
edge it's `boot()`, and `boot` defaults to serving the routes. On the database edge it's
`withDatabase(boot())`, and `boot` is abstract because a database-only application is its job.

Both edges implement `program` concretely, and neither marks it `override`. That's what makes
stacking them by hand a compile error:

```scala
object Main extends DbApp with HttpApp   // does not compile
```

The compiler sees two concrete `program`s and refuses until something says which one it means.
If the edges had written `override`, the later mixin would win silently, and `extends DbApp
with HttpApp` would serve with no database installed, in whichever order happened to put
`HttpApp` last. `EezoApp` is the one place that resolves it, and it resolves it in words:

```scala
override protected def program(): Unit = withDatabase(boot())
```

The http edge's `boot`, under the database edge's lifecycle. `dev` composes the same way: the
umbrella runs the drift check first, under the database, and serves the drift page instead of
the application while the drift is dangerous.

## The modules around the edges

- **`eezo-auth`** sits on the http edge and never depends on `eezo-db`. A guard
  reads the session and calls two functions you supply, so it never opens a connection or knows
  a table exists. The one place a `Password` meets storage is the `Column[Password]` you write
  beside your own user model, where both modules are already visible. An application with a
  login page and no database doesn't get a JDBC driver on its classpath.
- **`eezo-live`** sits on the http edge too. `LiveApp` adds the live socket and client script
  to whatever the application serves. `EezoApp` includes it, and an application on `eezo-http`
  alone writes `extends LiveApp` instead of `extends HttpApp` to get it.
- **`eezo-testkit`** sees all of them. It boots a real server against a real database and
  drives it over HTTP and WebSocket, for tests.

## Where to go next

The http edge's routes come from your file layout, which is [routing](routing.md). The database
edge's pool, scopes and query layer are [the database edge](the-database-edge.md).
