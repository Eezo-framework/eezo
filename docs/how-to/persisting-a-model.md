# How to persist a derived model

**Audience**: Daniel, or anyone wiring an eezo application to a real database for the first time.

The five steps were run end to end against this repository at `b7afa8c`, on sbt 1.12.14 and Scala
3.8.4, with Postgres 16 in a container on port 5442. The reload section describes what merged at
`f72f509`, step 4 the entry traits that merged at `9d06a65`, and the scalatags section records a
decision parked on 2026-09-01.

## What the glue is

`core` owns a trait, `Store[A]`: five operations, one per thing the derived seven routes do. It is
the only type `http` and `db` both name, and they never see each other.

* `http` supplies `InMemoryStore[A]()`, rows in a `TreeMap` for as long as the process lives.
* `db` supplies `JdbcStore[A]()(using Table[A])`, rows in Postgres.
* The generated route table is where one of the two is picked, **per model**, by the compiler.

The sbt plugin emits a helper called `storeFor`, and every derived row calls it. When `eezo-db` is
on the application's compile classpath the helper's body is a `summonFrom`:

```scala
private inline def storeFor[A]: io.eezo.core.Store[A] =
  scala.compiletime.summonFrom {
    case t: io.eezo.db.Table[A] => io.eezo.db.JdbcStore[A]()(using t)
    case _                      => io.eezo.http.InMemoryStore[A]()
  }
```

So a model whose companion carries a `Table` is persisted, and every other model in the same
application still mounts its seven routes over memory. Nothing you write names either
implementation.

### Why a `Store[A]` at all

The obvious shortcut is to let `Resource` call `db` directly. Four reasons it does not, each one a
rule the trait now enforces.

* **Siblings, not a stack.** `http` must never depend on `db`. An application with no database is a
  first class shape, and a login form or a `Note` that lives in memory would otherwise drag a JDBC
  driver onto every classpath. The trait lives in `core` because that is the one module both can
  see.
* **Vocabulary, not an API.** Five operations, one per route, and no sixth. The trait is what the
  derivation can say, not eezo's data access layer. A route that wants a filter, a sort or a page is
  a handwritten route on `db`'s query DSL. Widening the trait for one such route would put a second
  query language in the most expensive module in the build to change.
* **Typed per model.** `Store[A]` rather than one store holding every model's rows: a store that
  needs telling which bucket to look in cannot be typed, and `find(key: Id[A]): Option[A]` is the
  whole point. The `summonFrom` picks per model for the same reason.
* **Nothing throws.** A missing row is `false` or `None`, and `Resource` turns that into a 404. An
  implementation that raised would be deciding an HTTP status one layer below the layer that knows
  what HTTP is, and `core` would learn `db`'s exception vocabulary.

It is a trait and not a class because two implementations exist today, and nobody writes a third by
hand. Had only the in memory one existed, the seam would not have been cut yet.

## Before you start

```bash
docker run -d --name eezo-pg \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_USER=postgres -e POSTGRES_DB=eezo \
  -p 5442:5432 postgres:16

sbt publishLocalForExample   # in the eezo repository
```

`publishLocalForExample` publishes the framework and the plugin to the local ivy cache and records
the version in `.eezo-version`.

## Step 1: put `eezo-db` on the compile classpath

```scala
libraryDependencies ++= Seq(
  "io.eezo" %% "eezo-http" % eezoVersion,
  "io.eezo" %% "eezo-db"   % eezoVersion
)
```

The plugin reads the resolution report for `ConfigRef("compile-internal")` to decide whether the
`summonFrom` arm may be emitted at all. An `eezo-db` that arrives only `% Test`, for example through
`eezo-testkit`, does not count, and the emitted helper stays the in memory one. That is deliberate:
the generated file is a Compile source, and a name it cannot resolve would break the build the
generator exists to serve.

Adding the dependency to an existing application changes no file under `src/main/scala`, so the
generator carries the flag in its cache witness and regenerates anyway.

## Step 2: add `Table` to the model's `derives` clause

```scala
package models

import io.eezo.core.Id
import io.eezo.db.Table
import io.eezo.http.Form
import io.eezo.http.Resource

case class Post(
    id: Id[Post],
    title: String,
    body: String,
    minutes: Int,
    published: Boolean
) derives Form,
      Resource,
      Table
```

Three derivations on one declaration compile, and each keeps its own job. `Form` renders and parses,
`Resource` mounts the seven routes, `Table` describes the row. The key must be `Id[Post]`: `create`
mints one with `Id.gen()`, and `Column[Id[T]]` stores it as a `uuid`.

A model beside it with no `Table` is untouched:

```scala
case class Note(id: Id[Note], text: String) derives Form, Resource
```

## Step 3: declare the schema

```scala
import io.eezo.db.Schema
import models.Post

object JournalSchema extends Schema {
  val posts = table[Post]
}
```

Only models you register get DDL. `JournalSchema.ddl` for the `Post` above is:

```sql
create table "post" (
  "body" text not null,
  "id" uuid primary key,
  "minutes" integer not null,
  "published" boolean not null,
  "title" text not null
);
```

Two things worth knowing. The table name is the singular class name, `post`, while the route path is
the plural, `/posts`; the two are computed by different modules and neither reads the other. And
column order is alphabetical, which is `db`'s own convention, not the declaration order.

## Step 4: boot the database and the server together

`EezoApp` is the umbrella's entry trait, the two edges stacked. The database edge builds the
`Database` from configuration, installs it around the program and closes it after; the http edge
serves. Both are declarative: the application names its `schema` and its `routes`, and nothing in
it calls `Eezo.run`.

```scala
import io.eezo.EezoApp
import io.eezo.db.Schema
import io.eezo.generated.Routes
import io.eezo.http.RouteTable

object Main extends EezoApp {
  override def schema: Schema     = JournalSchema
  override def routes: RouteTable = Routes.table()
}
```

`main` is inherited and dispatches. `sbt run` serves on 8080 with the `Database` installed;
`sbt "run dev"` runs the drift check first and answers the drift page instead of the app while the
drift is destructive; `sbt "run status|sync|freeze|migrate|reset|drop|dump|ddl"` are the schema
commands, and `sbt "run routes"` prints the table. Bring the database to the model before the first
request, `sbt "run sync --apply"` while prototyping or `freeze` then `migrate --apply` once the
migration is worth reviewing; `examples/todo/README.md` walks the whole loop.

Every knob is an override on the trait. `port`, `maxBodySize` and `problems` are the server's;
`databaseUrl`, `databaseUser` and `databasePassword` read `EEZO_DB_URL`, `EEZO_DB_USER` and
`EEZO_DB_PASS`; `databaseSchema` and `databaseInit` put the tables in a Postgres schema of their
own. `boot` is the program: its default serves `routes`, and an override runs under the installed
`Database`, so a job before serving is `transact { ... }` then `serve(routes)`.

Two traps this shape closes.

**Do not sync from `boot`.** DDL is the schema commands' job, and they are idempotent: `sync` and
`migrate` diff the live snapshot against the model, where a hand written `Schema.ddl` on every boot
is the diff from an empty database and dies on `relation "post" already exists` the second time.

**Application code cannot reach a `Statement` from inside a scope.** `Tx.connection` is
`private[eezo]`, so `transact` is for rows, never for DDL. Every schema entry point in `db` takes a
`Connection` for exactly this reason, and the commands are the one place that hands them one.

## Step 5: prove it

```bash
curl -i -X POST http://localhost:8080/posts \
  --data-urlencode "title=Glued" --data-urlencode "body=http and db" \
  --data-urlencode "minutes=3" --data-urlencode "published=true"
# HTTP/1.1 303 See Other
# Location: /posts/aec98ce3-77a3-4141-9887-83a101b568d8

docker exec eezo-pg psql -U postgres -d eezo -c 'select id, title, minutes, published from post'
#                   id                  | title | minutes | published
#  aec98ce3-77a3-4141-9887-83a101b568d8 | Glued |       3 | t
```

Post a `Note` too, then restart the process. The `Post` is still there and the `Note` is gone, which
is the whole point: one route table, two stores, one decision per model made by the compiler.

`\dt` shows a single table, `post`. `Note` never asked for one.

## What `JdbcStore` promises

* **One scope per operation, and so one scope per request.** Each of the derived seven makes exactly
  one store call, so there is nothing for a transaction to span. `read` for `all` and `find`,
  `transact` for `insert`, `update` and `delete`. A handler that wants two writes under one commit is
  a handwritten handler calling `transact` itself.
* **`all()` is ordered by primary key**, because a derived index page that reshuffles on every write
  is unreadable. The in memory half orders by the text of the UUID, which is byte for byte what
  Postgres compares, so the two halves agree.
* **The key locates the row.** `update(key, row)` writes the row under `key`, not under
  `row.id`. A stale key is `false` from both halves, and `Resource` turns that into a 404.
* **Nothing throws.** `db` reports a zero row write by raising `NoSuchRow`; `JdbcStore` converts it
  to `false` there and nowhere else, so neither `core`'s trait nor `Resource` learns `db`'s
  vocabulary.

## Reload: the tab follows the backend

Now edit `Post`: rename a field, add a column. With `sbt eezoDev` running, the open browser tab
shows the new page about a second after the save, with no manual refresh. Merged at `f72f509`
(PR 156), implementing the contract in issue 153 verbatim.

### Two halves, one restart

The dev loop has a build side and a browser side, and they meet at exactly one event: the
application process being replaced. Neither half sends the other a message. The restart itself is
the message.

* **Build side.** `eezoDev` is an alias for `~eezoRestart`. The restart task asks for the full
  classpath, which compiles first, then kills the forked JVM and forks a fresh one with `dev` as its
  argument. A broken edit fails at the classpath step, so the dev loop never kills a working server
  for a compile error, the socket stays open, and the tab stays as it was.
* **Browser side.** Every page the dev server serves carries an inline script of about thirty
  lines. It opens a WebSocket to `/eezo/reload` and does nothing with it. On close it marks the
  connection lost and reconnects every 200 ms, forever, with no backoff and no cap. The first
  successful open after a loss calls `location.reload()`. Whole page, no state kept.
* **Server side.** Nothing. The listener behind `/eezo/reload` is the empty `WsListener`. The
  client needs only the open and close events, and the restart is what produces the close.

### Where the script enters the page

Structurally, at one site, on every response. `EezoHandler.handle` has a single completion point,
and `Reload.inject` runs there before the bytes are written. So a 200 page, a 404 or 500 page from
`Boundary`, and the drift refusal page all carry the client. `Boundary` stays the failure boundary
and does not grow a response filter.

```scala
private def appendToBody(page: Html): Html = {
  var done = false
  page.transform {
    case Html.Element("body", attrs, key, children) if !done =>
      done = true
      Html.Element("body", attrs, key, children :+ tag)
    case node => node
  }
}
```

The walk descends through `Fragment`, so `Html.doctype ++ html(...)` works, and appends the tag as
the last child of the first `body`. Nothing is searched for as a string.

| Body | Result | Why |
|---|---|---|
| `Body.Html`, any status, with a `body` element | Script appended | The tree is walked, never parsed. |
| `Body.Html` fragment, no `body` | Untouched | A fragment is not a document. This settles the later `live` patch case in advance. |
| `Response.Ok(Html.raw("<html>..."))` | Untouched, no reload | `Raw` is opaque by design. Searching it for `</body>` is string surgery. |
| `Body.Bytes`, even with an HTML content type | Untouched | User encoded. Same refusal as `Raw`. |
| Anything, with `dev = false` | Untouched, and the upgrade answers 404 | Production never sees the client or the endpoint. No per page opt out, because the flag is the switch. |

### Why the endpoint is not a route

`/eezo/reload` is matched in the WebSocket creator before the user's `RouteTable` is consulted,
and only when `dev` is on. It is never part of the table. Three things follow: no user route can
shadow it, no mount rewrites it, and it never appears in the boot listing. `Eezo.ReservedPrefix`
spells `/eezo` once, and the drift page's `sync` and `freeze` actions build from the same value.

Two edges, both accepted without a special case:

* `sbt "run dev"` starts the dev server with no watch. The socket stays open forever and nothing
  reloads. Reload reacts to a restart, and without one there is nothing to react to.
* The drift refusal page is a full document served with `dev = true`, so it carries the client like
  any other page. Resolve the drift, save a file, and the tab reloads into the application.

## Why the HTML tree stays ours

On 2026-09-01 the proposal was to drop `io.eezo.core.html` and render with scalatags. It was
parked, and the reload work above is the second consumer of the one property that decided it.

`modules/core` is, in practice, the HTML module: some 590 lines of tree, tags and attributes, and
only `http` imports them. scalatags would replace all of it with a maintained vocabulary of 110 tags
and 180 attributes against eezo's 46 and about 40. That is the whole of what it buys, and it is
real. What it costs is one guarantee.

**eezo's tree is a sealed enum** with four cases, `Element`, `Fragment`, `Text` and `Raw`, and no
fifth. A walk over it is total: the compiler proves every node is handled. `Html.transform` is that
walk, written once, and two rewriters ride on it. `Html.under` moves every `Url` attribute under a
mount prefix, which is how `Route.scala` keeps its promise that nothing a user writes has to know it
is mounted. `Reload.inject` finds the first `body` and appends the client.

**scalatags' tree is an open `Modifier` trait.** `TypedTag` is a case class with public
`modifiers`, and `AttrPair` keeps its typed value, so rewriting after construction is possible. It
is not total. A rewriter needs a fallthrough arm, and a user defined `Modifier` or a nested
`SeqFrag` can slip past it. For `under` that means a link left unmounted with no error. For
`inject` it means a page with no reload client and no error. Both fail silently, in exactly the
case a framework promise is supposed to cover.

A sealed tree gives total rewriting. An open trait gives partial rewriting with silent failure.
Mount rewriting was the blocker on its own; reload injection is the same shape of walk, and it
landed a week later without touching the question. Two consumers of one property is the point at
which a seam stops being speculative.

The smaller findings, so they are not re-derived:

| Point | scalatags | eezo |
|---|---|---|
| Escaping | Covers `<`, `>`, `&` and `"`, not `'`, and silently drops control characters. | Stricter. `Text`'s constructor is private to the package, so an unescaped string cannot enter the tree. |
| Maintenance | Last release 0.13.1 on 2024-04-15, then build chores only. 39 open issues. Published against Scala 3.3.1, works under 3.8.4. | Ours to change, and only `http` reads it. |
| Vocabulary | 110 tags, 180 attributes, plus SVG and CSS bindings. | 46 tags, about 40 attributes. A code generator would close the gap with no dependency, when a gap is felt. |
| Effects | None in the tree. | None in the tree, and none wanted for now. Settled along the way. |

The status is parked, not refused forever: the words on the day were "not enough information to
understand if it's convenient or not". What would reopen it is a scalatags that closes its tree, or
an eezo that gives up mount rewriting. Neither is on the map.

## What is not glued yet

* There is no standalone `eezo` launcher yet. The schema commands are the entry trait's, so
  `sbt "run status"` on the application is the whole CLI; `modules/cli` is reserved for `eezo new`,
  `g` and `deploy`.
* The entry point is by artifact: `HttpApp` in `eezo-http`, `DbApp` in `eezo-db`, `EezoApp` in the
  umbrella `eezo`, and `examples/hello`, `examples/reminders` and `examples/blog` are the three
  shapes. The mismatch, an umbrella dependency with an `HttpApp` entry and a model deriving
  `Table`, is a documented gap: `storeFor` picks `JdbcStore` and no `Database` is installed, so the
  first request fails. Whether boot refuses it is ticket 162.
* `Store[A]` is the vocabulary the derivation can speak, not eezo's data access API. A route that
  wants a filter, a sort or a page is a handwritten route on `db`'s query DSL, and it never touches
  the trait.
* How fast the loop is. Seen at about a second from save to refreshed tab on `examples/blog`.
  Measured properly it is not: that is ticket 155, timed by hand.
* Reserving `/eezo/`. Dispatch before the table is the whole enforcement. No boot refusal of user
  routes under the prefix: one reserved route is not enough to justify a rule.
