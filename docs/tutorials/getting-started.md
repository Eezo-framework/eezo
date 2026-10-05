# Your first eezo application

This page is for people who can read Scala and have never run eezo. By the end you'll have an
application scaffolded, running, restarting on every save, and serving a second page you wrote
yourself. It takes about twenty minutes, most of which is sbt downloading things the first time.
No database is involved yet; that's the next tutorial.

## Before you start

You need JDK 25 or newer and sbt.

eezo isn't published yet, so publish it from a clone of the repository, and alias the launcher:

```bash
git clone https://github.com/Eezo-framework/eezo
cd eezo
sbt publishLocalForExample
alias eezo=$PWD/bin/eezo
```

## Scaffold

Pick a name and run:

```bash
eezo new bookshelf
```

```
created ./bookshelf (eezo 0.0.0+105-756d1ed0+20260929-1242-SNAPSHOT, Scala 3.8.4, sbt 1.12.14)

next:
  cd bookshelf
  sbt eezoDev        # serve on :8080, restart on save
  sbt "run routes"   # what is mounted
```

(Your version string will differ; it comes from the commit you published.)

Here's everything it wrote:

```
bookshelf/
  build.sbt
  project/build.properties
  project/plugins.sbt
  src/main/scala/Main.scala
  src/main/scala/app/Index.scala
```

That's the whole application. Open the files one at a time.

### build.sbt

```scala
name         := "bookshelf"
scalaVersion := "3.8.4"

enablePlugins(EezoPlugin)

libraryDependencies += "io.eezo" %% "eezo" % "0.0.0+105-756d1ed0+20260929-1242-SNAPSHOT"

scalacOptions ++= Seq("-release", "25", "-deprecation", "-feature", "-unchecked", "-no-indent")

// eezo needs a modern JDK to run (virtual threads without pinning), so the app forks.
run / fork           := true
run / outputStrategy := Some(OutputStrategy.StdoutOutput)
```

`enablePlugins(EezoPlugin)` turns on the sbt plugin, which looks at your files and writes the
route table (more on that in a minute). `eezo` is the umbrella artifact, with both the http side
and the database side of the framework. If you only want one, depend on `eezo-http` or `eezo-db`
instead.

`-no-indent` means braces, not significant indentation. That's the syntax eezo uses, and a file
written in indentation syntax is a compile error.

### project/plugins.sbt

```scala
addSbtPlugin("io.eezo" % "sbt-eezo" % "0.0.0+105-756d1ed0+20260929-1242-SNAPSHOT")
```

The plugin, at the same version as the framework.

### Main.scala

```scala
import io.eezo.EezoApp
import io.eezo.db.Schema
import io.eezo.generated.Routes
import io.eezo.http.RouteTable

object Main extends EezoApp {
  override def schema: Schema     = Schema.empty
  override def routes: RouteTable = Routes.table()
}
```

(The scaffold's version has a long comment on top; it's been cut here.)

`EezoApp` brings `main` with it, so you never write one. `schema` is the tables the application
has, and a new application has none. `routes` is the route table, and `Routes.table()` calls
into a file the plugin generates. The table is a value you name in `Main`, which means you can
transform it before serving it, and if the plugin isn't enabled this line doesn't compile.

### app/Index.scala

```scala
package app

import io.eezo.core.html.*
import io.eezo.http.Request
import io.eezo.http.Response

object Index {

  def index(request: Request): Response = {
    val _ = request
    Response.Ok(
      Html.doctype ++ html(
        head(meta(Attrs.charset := "utf-8"), title("eezo")),
        body(
          h1("it works"),
          p("Add a case class with ", code("derives Form, Resource"), " under models/ "),
          p("and it mounts seven CRUD routes. ", code("sbt \"run routes\""), " lists them.")
        )
      )
    )
  }
}
```

Your first page, and the first rule of eezo routing: **the filename is the route.** A file called
`Index.scala` under `app/` mounts `GET /` and calls a method called `index`. The plugin saw the
file and wrote the row.

A handler takes a `Request` and returns a `Response`. It runs on a virtual thread, so if you
need to block in there, say on a database call, you block.

The page is built with eezo's HTML DSL. `html`, `head`, `body`, `h1` and the rest are functions,
attributes are written `Attrs.charset := "utf-8"`, and strings become escaped text. There's no
template language, and your views are type-checked like everything else.

The `val _ = request` line is there because the scaffold doesn't read the request. You'll
delete it as soon as you do.

## Run it

```bash
cd bookshelf
eezo dev
```

Give sbt a minute the first time. Then eezo announces what it serves:

```
INFO: 3 routes:
  GET /
  WS /eezo/live/:page
  GET /eezo/live.js
```

The routes under `/eezo` are the framework's: the live layer's socket and client script.
`/eezo/health` is there too, without showing in the list; it answers `ok`, and a deploy's health
check polls it later.

Open http://localhost:8080. You'll see "it works".

You'll also see a warning in the console about a throwaway secret. That's the key eezo signs
session cookies with. You haven't set one, so eezo mints one for the life of the process. It's
fine on a laptop; the deploy walkthrough covers production.

## Change something

With `eezo dev` still running, open `app/Index.scala`, change the `h1` to anything you like,
and save. sbt recompiles, eezo restarts the application, and the browser tab reloads itself.

Now break it on purpose. Delete a closing parenthesis and save. The compile fails, you'll see the
error in the console, and the old server keeps serving. Put the parenthesis back, save, and you're
back in the loop.

## A second page

Create `src/main/scala/app/Hello.scala`:

```scala
package app

import io.eezo.core.html.*
import io.eezo.http.Request
import io.eezo.http.Response

object Hello {

  def hello(request: Request): Response = {
    val name = request.queryParam("name").getOrElse("stranger")
    Response.Ok(
      Html.doctype ++ html(
        head(meta(Attrs.charset := "utf-8"), title("hello")),
        body(
          h1(s"hello, $name"),
          p("This page is served by eezo. Try ", a(Attrs.href := "/hello?name=you", "?name=you"), ".")
        )
      )
    )
  }
}
```

Save it. The application restarts, and `GET /hello` exists. Open http://localhost:8080/hello,
then http://localhost:8080/hello?name=you.

The second rule of routing: a file whose name isn't one of the seven REST names (`Index`,
`New`, `Show`, `Edit`, `Create`, `Update`, `Destroy`) mounts a `GET` at a segment of its own
name, lower-cased, and calls a method of that name. `Hello.scala` is `GET /hello` calling
`hello`. A subdirectory becomes a path segment: `app/books/Latest.scala` is `GET /books/latest`.

`request.queryParam("name")` gives you an `Option[String]`, and you decide what absence means.
The `Request` also has `path`, `header`, `cookie`, the form body, and typed path parameters.

## Reading the route table

```bash
eezo routes
```

```
2 routes:
  GET /hello
  GET /
```

This command only reads the table; it starts no server and opens no database. The order is the
order the table is matched in, most specific first. With two pages that doesn't matter. Once you
have `/books/new` next to `/books/:id`, it's what keeps `new` from being read as an id.

The table is a Scala file you can read, at
`target/scala-3.8.4/src_managed/main/io/eezo/generated/Routes.scala`. One row per file:

```scala
io.eezo.http.Route.Http(
  io.eezo.http.Method.GET,
  io.eezo.http.PathPattern.parse("/hello"),
  req => app.Hello.hello(req)
)
```

Each row has a comment naming the file it came from. If you ever get a compile error inside
`Routes.scala`, the method on that row doesn't take a `Request` and return a `Response`, and the
fix is in the file the comment names.

## Where to go next

Your application serves pages but stores nothing. The next tutorial, [from a case class to seven
routes](first-model.md), adds one case class and gets a database table, a form and the CRUD pages
out of it. If you'd rather see it ship first, [the deploy walkthrough](deploy-to-fly.md) takes an
application like this one to a public URL with a single command.
