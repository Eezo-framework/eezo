# Write a handwritten route

Mount a page or an endpoint from a file under `app/`, read what the request carries, and
answer it.

## The file is the route

The sbt plugin reads `src/main/scala/app/` and writes one route per file. Three rules decide
the method and the path.

**The seven REST names** carry their own method, path suffix and method name:

| file | mounts | calls |
|---|---|---|
| `Index.scala` | `GET /` | `index` |
| `New.scala` | `GET /new` | `` `new` `` |
| `Show.scala` | `GET /` | `show` |
| `Edit.scala` | `GET /edit` | `edit` |
| `Create.scala` | `POST /` | `create` |
| `Update.scala` | `PUT /` | `update` |
| `Destroy.scala` | `DELETE /` | `destroy` |

**Any other name** is a `GET` at a segment of its own name, lower-cased, calling a method of
that name: `app/Health.scala` is `GET /health` calling `health`. A custom `POST` is a `Create`
in a directory: `app/health/Create.scala` is `POST /health`.

**Directories are path segments.** `app/books/Latest.scala` is `GET /books/latest`. A directory
named `_id` is a parameter, `:id`; a directory named `__rest` is a catch-all, `*rest`, and it
has to be last.

So `app/books/_id/Preview.scala` mounts `GET /books/:id/preview`:

```scala
package app.books._id

import io.eezo.core.html.*
import io.eezo.core.Id
import io.eezo.http.{Request, Response}
import models.Book

object Preview {

  def preview(request: Request): Response = {
    val id = request.param[Id[Book]]("id")
    Response.Ok(
      Html.doctype ++ html(
        head(meta(Attrs.charset := "utf-8"), title("preview")),
        body(h1("preview"), p(s"book ${id.value}"))
      )
    )
  }
}
```

```
$ eezo routes

13 routes:
  GET /login
  POST /login
  POST /logout
  GET /books/:id/preview
  GET /books
  ...
```

The package has to match the directory, `app.books._id` here, and the object has to match the
file name. The plugin only reads names; the compiler checks the rest.

## Reading the request

**Path parameters** are typed. `request.param[Int]("id")` gives you an `Int` or answers 400 if
the segment can't be read as one. `paramOpt` is the `Option` version. `String`, `Int`, `Long`,
`UUID` and `Id[T]` work out of the box.

**Query parameters**: `request.queryParam("q")` is an `Option[String]`, first value wins.
`request.query` is the whole map, with repeats.

**Headers and cookies**: `request.header("Accept")`, `request.cookie("theme")`, both `Option`.

**A form body**: `request.form` is the decoded `Map[String, Seq[String]]`. If the fields match
a model with a `Form`, decode straight into it:

```scala
request.as[Book] match {
  case Right(book)   => ...
  case Left(errors)  => ...   // FormErrors, one message per field
}
```

`request.as[Book]("book")` reads fields prefixed with `book.` instead.

**Who's signed in**: `request.currentUser`, when the application has a guard and the route is
guarded.

## Answering

- A page: `Response.Ok(html)`.
- A redirect after a write: `Response.Redirect("/books")`, a 303.
- Any status: `Response.status(204)`, with an empty body.
- Bytes with a content type:

```scala
Response(200, Seq("Content-Type" -> "text/plain; charset=utf-8"), Body.Bytes(bytes))
```

- A cookie: `.withCookie(Cookie(...))`. A changed session: `.withSession(request.session.set("k", "v"))`.
- A refusal: `throw NotFound(request.path)`, `BadRequest("why")`, `Forbidden("why")`. The status
  and the error page come from the boundary; see [handle failures](handle-failures.md).

## When the application has a guard

If `eezo-auth` is in the build, the page has to say who may reach it, in its own object:

```scala
given Guarded[Preview.type] = User.guard.required   // or Guarded.public
```

Leaving it out is a compile error naming the file.

## Where the table is written

`target/scala-3.8.4/src_managed/main/io/eezo/generated/Routes.scala`. One row per file, each
with a `// from` comment naming the source. A compile error in that file means the method on the
row doesn't take a `Request` and return a `Response`; fix the file the comment names.
