# Routing by file layout

You've put a file under `app/` and a route appeared. This page is about how that happens: what
runs before the compiler, what order the table ends up in, how a request finds its row, and why
nothing in it is found by reflection.

## A generator, before the compiler

The sbt plugin reads `src/main/scala/app/` and writes one Scala file,
`target/scala-3.8.4/src_managed/main/io/eezo/generated/Routes.scala`. It runs as a source
generator, so it's done before your code compiles, and it works on text alone: filenames,
directory names, `package` clauses and `case class` declarations. It never asks the compiler
anything, because the compiler hasn't run yet.

The filename is the verb. Seven names are reserved and map to the REST roles:

| file | route |
|---|---|
| `Index.scala` | `GET /` |
| `New.scala` | `GET /new` |
| `Show.scala` | `GET /` |
| `Edit.scala` | `GET /edit` |
| `Create.scala` | `POST /` |
| `Update.scala` | `PUT /` |
| `Destroy.scala` | `DELETE /` |

Any other name is a `GET` at its own lower-cased segment, so `app/Health.scala` is `GET
/health`, calling `def health`. Directories carry the path: `app/books/Index.scala` is `GET
/books`, a directory named `_id` becomes a `:id` parameter, and `__rest` becomes a `*rest`
catch-all. The `def` the route calls is the role name, `index`, `show`, `create`, or the custom
name decapitalised. Those are the three segment kinds there are. No regex constraints, no
optional segments, no typed patterns: a parameter is text, and the handler reads it with a type
through `request.param[Id[Book]]("id")`, which is a 400 when it won't parse.

Here's what the generator wrote for the bookshelf from the tutorials, with one handwritten
`GET /books` added beside the derived `Book`:

```scala
  /** Routes written by hand under src/main/scala/app/. */
  private val handwritten: Seq[io.eezo.http.Route] =
    // from src/main/scala/app/books/_id/Preview.scala
    guardFor[app.books._id.Preview.type]("app.books._id.Preview").mounting(
      io.eezo.http.Route.Http(
        io.eezo.http.Method.GET,
        io.eezo.http.PathPattern.parse("/books/:id/preview"),
        req => app.books._id.Preview.preview(req)
      )
    ) ++
    // from src/main/scala/app/books/Index.scala
    guardFor[app.books.Index.type]("app.books.Index").mounting(
      io.eezo.http.Route.Http(
        io.eezo.http.Method.GET,
        io.eezo.http.PathPattern.parse("/books"),
        req => app.books.Index.index(req)
      )
    ) ++
    // ...
```

Every row carries a `// from` comment naming the file it came from. When a row fails to
compile, the `def` on that row doesn't take a `Request` and return a `Response`, and the fix is
in the file the comment names. The generated file is never edited.

Models get the same treatment: one line per candidate case class, calling
`Resource.routesOf[models.Book]`. The generator doesn't decide whether `Book` has a `Resource`.
It emits the line for any case class with a `derives` clause and lets the compiler answer,
because `routesOf` resolves to the seven routes when the instance exists and to nothing when
it doesn't. A false positive costs one line that mounts nothing. A regex that tried to read the
`derives` clause would get type aliases and hand-written instances wrong.

What the generator *can* warn about is what text can decide: a file under `app/` that defines
no `def` of the name its filename promises, and a `derives`-carrying model it can't name. The
second one has a story. The scan used to treat "top level" as "column zero", and a model that
an editor had indented compiled, deployed, and silently mounted nothing. The scan now tracks
brace depth, so an indented top-level model mounts, a model nested in an `object` mounts as
`pkg.Outer.Inner`, and one nested in a class or a block gets a warning naming the file instead
of silence.

## Order is decided at generation

Dispatch is first match in table order, and the table never sorts. That's the whole contract:
you can read `Routes.scala` top to bottom and know what matches first. Rails, Phoenix and Play
all do the same, for the same reason.

So the generator sorts. Handwritten routes come first, and among them the most static path
wins: segment by segment, a literal beats `:name`, which beats `*rest`, with ties broken by
path and then by method so that two runs of the generator produce the same file. That's what
puts `GET /books/new` ahead of `GET /books/:id`; under filesystem order, `new` would sometimes
be read as an id and answer 400.

Here's the bookshelf's table as `eezo routes` prints it:

```
⚠ GET /books is written by hand and also derived; the handwritten route is served and the derived one is not mounted.
13 routes:
  GET /login
  POST /login
  POST /logout
  GET /books/:id/preview
  GET /books
  GET /hello
  GET /
  GET /books/new
  GET /books/:id
  GET /books/:id/edit
  POST /books
  PUT /books/:id
  DELETE /books/:id
```

The three login routes come from the guard and travel with the first declaration that uses it.
The handwritten `/books` is listed, the derived one isn't, and the warning says so.

## Matching

One pass over the HTTP routes. The first whose pattern matches the path and whose method
matches wins, and its handler runs with the captured parameters. If some pattern matched the
path but no method did, the methods that would have matched are already in hand, so the answer
is a 405 carrying exactly the `Allow` header the standard requires. If nothing matched the
path, it's a 404. A trailing slash is ignored, so `/books` and `/books/` are one path.

Between the match and the handler sits the CSRF check: after the match, so a 404 stays a 404,
and before the handler and anything a guard wrapped around it, so a forged `POST` to a guarded
route is refused as forged and never reaches the login redirect.

## Handwritten beats derived

Every route knows where it came from: handwritten, or derived by `Resource`. When both kinds
land on the same method and path, the handwritten one is served and the derived one is dropped
from the table entirely. That's the ordinary way to take over one page of a resource and keep
the other six, and it's reported at boot so you know it happened.

Shadowing is different. A handwritten `GET /books/latest` ahead of a derived `GET /books/:id`
is legal and is how first-match is meant to be used. Only when an earlier pattern swallows
everything a later one could match does boot warn, because the later route can never run.

A duplicate, two handwritten routes or two derived ones on the same method and path, is a boot
error. Neither has a reading under which you meant it, and picking one would hide the mistake.

## The table is a `def`

`Routes.table()` builds the table every time you call it, and the stores with it. A model with
no `Table` gets an in-memory store, fresh per call, so a test that calls `Routes.table()` and
creates rows through a derived resource starts from an empty world every time, with no reset
step. A model with a `Table` gets a store over the installed database, and the compiler picks
which, per model, inside the generated `storeFor` helper.

The table also carries `identify`, a function composed from every guard declaration mounted
into it. A guarded page names its current user inside the guard's own wrapper, but a WebSocket
upgrade on a public route has no wrapper, and the table is the one thing that holds every
declaration at once. `identify` is what names the user on an upgrade, and it's why a table you
rebuild by hand has to pass the original's along: `RouteTable(rearranged, table.identify)`.
Leave it out and every socket is anonymous, with nothing to say so.

## The reserved prefix

`/eezo` belongs to the framework. Under it: `/eezo/health`, answered before anything else and
without reading a cookie; `/eezo/reload`, the dev server's WebSocket; `/eezo/live/:page` and
`/eezo/live.js` for live pages; and the drift page's two actions under `eezo dev`. The health
and reload endpoints are asked before your table, so no route can shadow them and no mount
moves them. The live routes are appended after yours, so they show in the boot listing but not
in `eezo routes`, which is your table.

## Where to go next

What a mount does to the paths and to the links on the pages is
[URLs and mounts](urls-and-mounts.md). What a handler is handed and gives back is
[requests and responses](requests-and-responses.md).
