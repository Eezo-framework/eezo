<p align="center">
  <img src="https://eezo.io/assets/mascot.png" width="112" height="112" alt="">
</p>
<h1 align="center">eezo</h1>
<p align="center">
  A Scala 3 web framework where the case class is the source of truth.<br>
  Direct style on virtual threads. Deploy with one command.
</p>
<p align="center">
  <img src="https://img.shields.io/badge/Scala%203-%23de3423.svg?logo=scala&logoColor=white" alt="Made for Scala 3">
  <a href="https://github.com/Eezo-framework/eezo/actions/workflows/ci.yml"><img src="https://github.com/Eezo-framework/eezo/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://central.sonatype.com/artifact/io.eezo/eezo_3"><img src="https://img.shields.io/maven-central/v/io.eezo/eezo_3.svg?label=maven%20central" alt="Maven Central"></a>
  <a href="https://javadoc.io/doc/io.eezo/eezo_3"><img src="https://javadoc.io/badge2/io.eezo/eezo_3/javadoc.svg" alt="javadoc"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License: MIT"></a>
</p>
<p align="center">
  <a href="https://eezo.io">eezo.io</a> ·
  <a href="https://eezo.io/docs/tutorials">Tutorials</a> ·
  <a href="https://eezo.io/docs">Docs</a> ·
  <a href="https://eezo.io/api/">API</a>
</p>

---

## What eezo is

A model declares what it is, and eezo derives the rest from that one declaration: its table in
Postgres, its HTML form, and the routes that serve it.

```scala
case class Post(
    id: Id[Post],
    author: Id[User],
    title: String,
    body: String,
    published: Boolean
) derives Table, Form, Resource
```

Those three words mount seven CRUD routes, index, new, create, show, edit, update and destroy,
served in a browser over rows in Postgres, with no controller, no repository and no template
written by hand. A handwritten route is a plain `def` in a file whose name is the path, and it
wins over a derived route on the same path, so you take over exactly the pages you need and keep
the rest derived.

Handlers are ordinary functions from a request to a response. There is no effect system to learn:
eezo runs Jetty on JDK virtual threads, so a handler blocks on the database or on an external API
and the thread it holds costs nothing.

Everything an application has is a value it names in `Main`: the schema, the route table, the
layout every HTML reply comes back in. Nothing is found by reflection, so a missing plugin, or a
derivation for an edge the application does not have, is a compile error rather than a message at
runtime.

## Requirements

- **JDK 25 or newer.** JEP 491, delivered in JDK 24, removed virtual thread pinning on
  `synchronized`. JDK 25 is the first LTS release carrying it, and the server design depends on
  it. The build refuses to load on anything older, with the reason.
- **Scala 3.8.4.** eezo tracks Scala 3.8.x. The framework is written with braces rather than
  significant indentation, and `-no-indent` is part of the recommended compiler flags.
- **sbt 1.5.8 or newer, or sbt 2.0.6 or newer.** The plugin is published for both.
- **Postgres**, for an application with a database edge. The default connection is a local
  Postgres on port 5442; [Configure the database](docs/how-to/configure-the-database.md) has the
  one Docker command that starts it.

## Install

`project/plugins.sbt`:

```scala
addSbtPlugin("io.eezo" % "sbt-eezo" % "0.1.0")
```

`build.sbt`:

```scala
scalaVersion := "3.8.4"

enablePlugins(EezoPlugin)

libraryDependencies += "io.eezo" %% "eezo" % "0.1.0"

scalacOptions ++= Seq("-release", "25", "-deprecation", "-feature", "-unchecked", "-no-indent")

// The application forks, so it runs on the JDK sbt was told about rather than the one sbt runs on.
run / fork := true
```

Every artifact is published at one version, under the `io.eezo` group:

| artifact | what it carries |
|---|---|
| `eezo` | the umbrella and the default dependency: both edges and `EezoApp` |
| `eezo-http` | the http edge: Jetty, requests and responses, routing, `Form`, `Resource`, `Layout`, `HttpApp` |
| `eezo-db` | the database edge: the pool, the `sql` interpolator, transactions, `Table`, schema commands and migrations, `DbApp` |
| `eezo-auth` | sessions, email and password sign in, CSRF, route guards and row ownership |
| `eezo-live` | state held on the server and a thin browser: the diff and patch protocol, the client runtime, PubSub |
| `eezo-testkit` | a real server against a real database, driven over HTTP and WebSocket from a test |
| `eezo-core` | what the others build on: configuration, the HTML tree and DSL, the model key `Id[T]` |
| `sbt-eezo` | the sbt plugin that generates the route table, for sbt 1 and sbt 2 |

An application has one or two edges, the http edge and the database edge, and depends on the
artifact of the edges it has. `eezo-http` alone carries `HttpApp`, `eezo-db` alone carries
`DbApp`, and the umbrella carries both and `EezoApp`. [Edges](docs/explanation/edges.md) explains
the split.

## Hello, eezo

Two files. `src/main/scala/Main.scala`:

```scala
import io.eezo.generated.Routes
import io.eezo.http.HttpApp
import io.eezo.http.RouteTable

object Main extends HttpApp {
  override def routes: RouteTable = Routes.table()
}
```

`src/main/scala/app/Hello.scala`:

```scala
package app

import io.eezo.core.html.*
import io.eezo.http.Request
import io.eezo.http.Response

object Hello {

  def hello(request: Request): Response =
    Response.Ok(
      Html.doctype ++ html(
        head(title("hello, eezo")),
        body(h1("hello, eezo"), p("served at ", code(request.path)))
      )
    )
}
```

The file is the route. The plugin reads `app/Hello.scala`, mounts `GET /hello` calling
`def hello`, and writes the table to a generated `io.eezo.generated.Routes`, which `Main` names as
its `routes`. A page is a value of eezo's own HTML DSL, not a template. `HttpApp` brings `main`
with it:

```bash
sbt run              # serves on http://localhost:8080
sbt eezoDev          # serves, restarting on every save, with the route listing
sbt "run routes"     # prints the table
```

[Your first eezo application](docs/tutorials/getting-started.md) walks through this in twenty
minutes, and [Routing](docs/explanation/routing.md) says how a file name becomes a path.

## From a model to an application

Add the database edge by depending on the umbrella, naming a schema, and deriving `Table`:

```scala
import io.eezo.EezoApp
import io.eezo.db.Schema
import io.eezo.generated.Routes
import io.eezo.http.RouteTable

object Main extends EezoApp {
  override def schema: Schema     = AppSchema
  override def routes: RouteTable = Routes.table()
}
```

`AppSchema` registers the models that derive `Table`, and the schema commands run against it:
`status` shows the drift between the case classes and the database, `sync` applies it in
development, `freeze` writes a migration, `migrate` runs the migrations in production. A model
that derives `Table` beside `Form` and `Resource` gets its seven routes backed by Postgres, and
the posts you create in the browser survive a restart.
[The case class](docs/explanation/the-case-class.md) and
[Schema and migrations](docs/explanation/schema-and-migrations.md) are the two pages behind this.

One line in the model's companion guards its routes:

```scala
object Post {
  given Owned[Post, User] =
    User.guard.required[Post].owning(_.author).except(Action.Index, Action.Show)
}
```

Every route now needs a signed in user, five of them reach only the author's own rows, and the
login page travels into the route table with the declaration.
[Guards and ownership](docs/explanation/guards-and-ownership.md) has the rest.

The route table is a value, so `Main` can transform it before serving it. `Route.under("/admin")`
moves a set of routes and every URL their pages emit, so the derived pages link to each other
without ever naming the prefix. [URLs and mounts](docs/explanation/urls-and-mounts.md) explains
why an emitted URL is a value and a string is absolute.

A live component holds its state on the server and the browser receives patches over one socket:

```scala
final class Board extends Component[List[Order]] {
  def init(ctx: Init[List[Order]]): List[Order] = {
    ctx.subscribe(Sales.paid)((sale, sales) => sale :: sales)
    read(Table[Order].where(_.paid === true).orderBy(_.at.desc).list())
  }
  def handle(event: Event, sales: List[Order]): List[Order] = sales
  def render(sales: List[Order]): Html = ul(sales.map(s => li(Key(s.id.show), s.name)))
}
```

[The live layer](docs/explanation/the-live-layer.md) is the model and
[A live page](docs/tutorials/a-live-page.md) builds one.

## The command line

```
eezo new <name>       scaffold a new application in ./<name>
eezo dev              serve, restarting on every save
eezo routes           what is mounted
eezo status           the drift between the models and the database
eezo sync             apply that drift to the development database
eezo freeze           write the drift as a migration
eezo migrate          run the migrations
eezo build            stage the jars, the migrations and a Dockerfile
eezo deploy           build, deploy to Fly.io, migrate, wait for health
```

The launcher is [`bin/eezo`](bin/eezo) in this repository. Every command but `new` forwards to
the application's own dispatch, `sbt "run <command>"`, or to a plugin task, so an application
needs nothing beyond sbt and the commands work without the launcher on the path.
[The eezo command line](docs/reference/cli.md) is the reference, and
[Deploy](docs/how-to/deploy.md) says what one deploy does.

## Examples

| example | artifact | entry trait | what it shows |
|---|---|---|---|
| [`hello`](examples/hello) | `eezo-http` | `HttpApp` | one handwritten route and no database |
| [`reminders`](examples/reminders) | `eezo-db` | `DbApp` | one `Table` and a nightly job over its rows, no server |
| [`blog`](examples/blog) | `eezo`, `eezo-auth` | `EezoApp` | a model deriving all three, guarded and mounted under `/admin` |
| [`todo`](examples/todo) | `eezo` | `EezoApp` | a guided tour of the command line, every output captured from a real run |
| [`shop`](examples/shop) | `eezo`, `eezo-auth` | `EezoApp` | a shop paying through Stripe, with a live sales board |

The examples build against the working tree rather than a release, so run them from a clone:

```bash
sbt publishLocalForExample      # publishes eezo and sbt-eezo locally and records the version
cd examples
sbt hello/run                   # http://localhost:8080/hello
sbt reminders/run               # runs the job once against the development Postgres
sbt "blog/run sync --apply"     # creates the tables, then:
sbt blog/run                    # http://localhost:8080, posts under /admin/posts
```

Each example has a README that explains its shape and shows the derivation of the edge it does
not have failing to compile.

## Documentation

The documentation lives at [eezo.io/docs](https://eezo.io/docs) and its sources are in
[`docs/`](docs). It is organised in four pillars:

- [Tutorials](https://eezo.io/docs/tutorials), from
  [your first application](docs/tutorials/getting-started.md) through
  [the first model](docs/tutorials/first-model.md), [sign in](docs/tutorials/sign-in.md),
  [a live page](docs/tutorials/a-live-page.md) and [deploying to Fly](docs/tutorials/deploy-to-fly.md).
- [How to guides](https://eezo.io/docs/how-to), one recipe per task:
  [create a CRUD app](docs/how-to/create-a-crud-app.md),
  [evolve the schema](docs/how-to/evolve-the-schema.md),
  [add authentication](docs/how-to/add-authentication.md),
  [test an application](docs/how-to/test-an-application.md), and the rest.
- [Explanation](https://eezo.io/docs/explanation), the model behind each part of the framework:
  [the server](docs/explanation/the-server.md), [the dev loop](docs/explanation/the-dev-loop.md),
  [sessions and CSRF](docs/explanation/sessions-and-csrf.md),
  [deployment](docs/explanation/deployment.md).
- [Reference](https://eezo.io/docs/reference): [the derivations](docs/reference/derivations.md),
  [routing](docs/reference/routing.md), [configuration](docs/reference/configuration.md),
  [the sbt plugin](docs/reference/sbt-plugin.md), [the live protocol](docs/reference/live-protocol.md).

The API reference, generated from the sources, is at [eezo.io/api](https://eezo.io/api/).
[`CONTEXT.md`](CONTEXT.md) is the vocabulary the framework and its documentation use, and
[`docs/adr`](docs/adr) records the decisions behind its shape.

## Contributing

Issues and pull requests are welcome at
[github.com/Eezo-framework/eezo](https://github.com/Eezo-framework/eezo). The build is what CI
runs:

```bash
sbt scalafmtCheckAll scalafmtSbtCheck   # one formatter, one configuration
sbt +compile Test/compile               # the plugin is built for sbt 1 and sbt 2
sbt +test
```

The `db` suite starts a real Postgres through testcontainers, so it needs a Docker daemon.
`sbt check` runs the regression half of that suite, and `sbt backlog` runs the tests that
describe open work and are expected to fail until it lands. The decisions behind the framework's shape
are recorded in [`docs/adr`](docs/adr); read the one a change touches before changing what it decided.

## Licence

eezo is released under the [MIT License](LICENSE), copyright 2026 Riccardo Cardin and Daniel
Ciocîrlan. An application that depends on eezo carries no obligation beyond preserving the
copyright notice, and eezo takes on no dependency that would add one. Attribution notices for
third party components are collected in [NOTICE](NOTICE).
