<p align="center">
  <img src="https://eezo.io/assets/mascot.png" width="112" height="112" alt="">
</p>
<h1 align="center">eezo</h1>
<p align="center">
  A Scala 3 web framework where your domain model is the source of truth.<br>
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

Declare your data model, and eezo derives the rest from that one declaration: its table in
Postgres, its HTML form, and CRUD routes that serve it.

```scala
case class Post(
    id: Id[Post],
    author: Id[User],
    title: String,
    body: String,
    published: Boolean
) derives Table, Form, Resource
```

A case class automatically derives its Postgres table and a type-safe query API.
As you change your data model, Eezo detects schema changes. When you're ready, you can commit them to a fingerprinted SQL migration.

A case class also gets 7 CRUD routes: index, new, create, show, edit, update and destroy,
served in a browser over rows in Postgres, with no need for a controller, repository or template
written by hand. You can add your own routes as a plain `def` in a file, and the file path becomes the HTTP route path.
You can also override a derived route on the same path.

Handlers are ordinary functions from a request to a response.
Eezo runs on virtual threads, so blocking on the database or on an external API is cheap.

UIs are server-side rendered, with minimal functional Scala. A `Component[State]` has `init`, `update` and `render`, and can return HTML with Scala functions.
For the frontend, the browser gets a thin JS client which sends events to the server, and the server renders DOM diffs that get patched on the UI.

No need for
- effect systems
- runtime reflection
- plugins
- JavaScript
- configurations

Deploying is done with `eezo deploy` on Fly.io.

## Requirements

- **JDK 25 or newer.** Eezo needs virtual threads to not be pinned on `synchronized`.
- **Scala 3.8.4.**
- **sbt 1.5.8 or newer, or sbt 2.0.6 or newer.** The plugin is published for both.
- **Postgres**, for an application with a database layer. The default connection is a local
  Postgres. One of the few Eezo configurations is for [the database](docs/how-to/configure-the-database.md).

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

Every artifact is published at the same version, under the `io.eezo` group:

| artifact | what it carries                                                                                                      |
|---|----------------------------------------------------------------------------------------------------------------------|
| `eezo` | the umbrella and the default dependency                                                                              |
| `eezo-http` | the http layer: servers, requests and responses, routing, `Form`, `Resource`, `Layout`, `HttpApp`                    |
| `eezo-db` | the database layer: the pool, the `sql` interpolator, transactions, `Table`, schema commands and migrations, `DbApp` |
| `eezo-auth` | sessions, email and password sign in, CSRF, route guards and row ownership                                           |
| `eezo-live` | server-side rendered UI with the thin browser client: the diff and patch protocol, the client runtime, PubSub        |
| `eezo-core` | what the others build on: configuration, HTML tree and DSL, derivation, model keys                                   |
| `sbt-eezo` | the sbt plugin that generates the route table, for sbt 1 and sbt 2                                                   |

An application has one or two layers, the http layer and the database layer. Use a layer by extending the appropriate trait in your app:
- `DbApp` from `eezo-db`
- `HttpApp` from `eezo-http`
- `EezoApp` from `eezo` which mixes both

The [Layers](docs/explanation/layers.md) doc explains more.

## Hello, eezo

To start, you need two files. In `src/main/scala/Main.scala`:

```scala
import io.eezo.generated.Routes
import io.eezo.http.HttpApp
import io.eezo.http.RouteTable

object Main extends HttpApp {
  override def routes: RouteTable = Routes.table()
}
```

Then in `src/main/scala/app/Hello.scala`:

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

The `Hello` file is also the HTTP route. The plugin reads `app/Hello.scala`, mounts `GET /hello` calling
`def hello`, and writes the table to a generated `io.eezo.generated.Routes`, which `Main` contains as
its `routes`. A page is a value of Eezo's own HTML DSL. `HttpApp` brings `main`
with it:

```bash
sbt run              # serves on http://localhost:8080
eezo dev             # serves, restarting on every save, with the route listing
eezo routes          # prints the table
```

[Your first eezo application](docs/tutorials/getting-started.md) walks through this in about 20
minutes, and [Routing](docs/explanation/routing.md) says how a file name becomes a path.

## From a model to an application

Add the database layer by depending on either `eezo` or just `eezo-db`. 
Make your case class derive `Table`, then add your tables (one line) to your schema:

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
`eezo status` shows the drift between the case classes and the database, `sync` applies it in
development, `freeze` writes a migration, `migrate` runs the migrations in production. A model
that derives `Table` beside `Form` and `Resource` gets its seven routes backed by Postgres, and
the posts you create in the browser survive a restart.
Check out the [the case class](docs/explanation/the-case-class.md) and the
[schema and migrations](docs/explanation/schema-and-migrations.md) pages for more details.

One line in the model's companion guards its routes:

```scala
object Post {
  given Owned[Post, User] =
    User.guard.required[Post].owning(_.author).except(Action.Index, Action.Show)
}
```

Every route now needs a signed-in user, five of them reach only the author's own rows, and the
login page travels into the route table with the declaration.
[Guards and ownership](docs/explanation/guards-and-ownership.md) explains more.

The route table is a value, so `Main` can transform it before serving it. For example, `Route.under("/admin")`
moves a set of routes and every URL their pages emit under a path prefix. The derived pages link to each other
without ever naming the prefix. [URLs and mounts](docs/explanation/urls-and-mounts.md) explains
how it works.

A live component holds its state on the server and the browser receives patches over a websocket:

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

The launcher is [`bin/eezo`](bin/eezo) in this repository.
[The eezo command line](docs/reference/cli.md) is the reference, and
[Deploy](docs/how-to/deploy.md) explains how deploying works, and the possible deploy targets.

## Examples

| example | artifact | entry trait | what it shows                                                               |
|---|---|---|-----------------------------------------------------------------------------|
| [`hello`](examples/hello) | `eezo-http` | `HttpApp` | one handwritten route and no database                                       |
| [`reminders`](examples/reminders) | `eezo-db` | `DbApp` | one `Table` and a nightly job over its rows, no server                      |
| [`blog`](examples/blog) | `eezo`, `eezo-auth` | `EezoApp` | a model deriving all three type classes, guarded and mounted under `/admin` |
| [`todo`](examples/todo) | `eezo` | `EezoApp` | a guided tour of the command line, every output captured from a real run    |
| [`shop`](examples/shop) | `eezo`, `eezo-auth` | `EezoApp` | a shop paying through Stripe, with a live sales board                       |

The examples build against the working tree rather than a release, so run them from a clone:

```bash
sbt publishLocalForExample      # publishes eezo and sbt-eezo locally and records the version
cd examples
sbt hello/run                   # http://localhost:8080/hello
sbt reminders/run               # runs the job once against the development Postgres
sbt "blog/run sync --apply"     # creates the tables, then:
sbt blog/run                    # http://localhost:8080, posts under /admin/posts
```

Each example has a README that explains how it works.

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
describe open work and are expected to fail until it lands. The decisions behind the framework
are recorded in [`docs/adr`](docs/adr).

Pull requests target `dev`, the trunk. `main` is the latest release: it moves only by fast forward
when a version is tagged, so the front page, this README and a plain clone always describe what is
on Maven Central. Releasing is `bin/release <version>` on `dev`: it bumps the version literals the
docs quote, fast forwards `main`, and pushes the tag that publishes the artifacts and deploys the
site.

## Licence

Eezo is released under the [MIT License](LICENSE), copyright 2026 Riccardo Cardin and Daniel
Ciocîrlan. An application that depends on Eezo carries no obligation beyond preserving the
copyright notice, and Eezo takes on no dependency that would add one. Attribution notices for
third party components are collected in [NOTICE](NOTICE).
