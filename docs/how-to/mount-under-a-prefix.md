# Mount routes under a prefix

Serve part of the table under `/admin`, with the pages' own links, forms and redirects moving
with it.

## Split the table and move half of it

`Routes.table()` is a value, so `Main` can rearrange it before serving. The usual shape: the
handwritten pages stay where they are and the derived resources move under a prefix.

```scala
import io.eezo.http.{Provenance, Route, RouteTable}

object Main extends EezoApp {

  override def schema: Schema = AppSchema

  override def routes: RouteTable = {
    val table                  = Routes.table()
    val (derived, handwritten) = table.routes.partition(_.provenance == Provenance.Derived)
    RouteTable(handwritten ++ Route.under("/admin")(derived), table.identify)
  }
}
```

`Route.under` takes a prefix and a sequence of routes and gives them back mounted. It accepts
`/admin`, `admin` and `/admin/` as the same prefix.

Keep the handwritten routes first: the table matches in order, and a handwritten route only
beats a derived twin if it comes before it.

Pass `table.identify` along. It's how a live socket's upgrade learns who's signed in. The
one-argument `RouteTable(rearranged)` compiles and serves every page as before, and turns every
socket anonymous.

## What moves with the routes

A mount moves two things: the paths the routes answer on, and the URLs the pages emit. The
derived pages link to each other, post their forms and redirect after a write through
`Url.Mounted` values, so under `/admin` they come out as `/admin/books`, `/admin/books/new`
and so on, with nothing in the resource knowing it was moved. The guard's login page moves
too: a refusal under `/admin` redirects to `/admin/login`.

A plain `String` address never moves. `Url.Absolute("/x")` says the same thing in the type.

## Linking into a mount from outside

A page outside the mount, like a handwritten index at `/`, has to spell the prefix itself:

```scala
a(Attrs.href := "/admin/books", "All books")
```

Nothing rewrites that page, so `Url.Mounted("/books")` there would render as `/books` and 404.
The page that points into a mount is the one that knows where the mount is.

## Mounting one route twice

The same route can be served at two prefixes; the blog example serves its live board at
`/board` and `/admin/board`:

```scala
val board = handwritten.filter(_.describe == "GET /board")
RouteTable(handwritten ++ Route.under("/admin")(derived) ++ Route.under("/admin")(board), table.identify)
```

Each copy's pages link relative to where that copy lives.
