# Take over one of the seven derived routes

Replace a derived page with one you wrote and keep the other six, or remove an action from a
resource altogether.

## Write the page, same method, same path

A handwritten route wins over a derived one on the same method and path. To replace the list
page of `Book`, create `app/books/Index.scala`:

```scala
package app.books

import io.eezo.core.html.*
import io.eezo.db.*
import io.eezo.db.Scopes.read
import io.eezo.http.{Guarded, Request, Response}
import models.{Book, User}

object Index {

  given Guarded[Index.type] = User.guard.required

  def index(request: Request): Response = {
    val books = read(Table[Book].all())
    Response.Ok(
      Html.doctype ++ html(
        head(meta(Attrs.charset := "utf-8"), title("the shelf")),
        body(
          h1("The shelf"),
          ul(books.map(b => li(a(Attrs.href := s"/books/${b.id.value}", b.title), " by ", b.author))),
          p(a(Attrs.href := "/books/new", "Add a book"))
        )
      )
    )
  }
}
```

(The `Guarded` line is only needed if the application has a guard.)

```
$ eezo routes

⚠ GET /books is written by hand and also derived; the handwritten route is served and the derived one is not mounted.
13 routes:
  ...
  GET /books
  ...
  GET /books/new
  GET /books/:id
```

The derived list page is dropped from the table, your page is served, and the other six are
untouched. The warning is there so you know it was deliberate.

## Keep the form honest

If the page you take over is one that renders or reads the form, `new`, `edit`, `create` or
`update`, call the derived `Form` from your handler instead of writing the inputs by hand:

```scala
import io.eezo.http.{Form, Method}

// render it
Form[Book].render(action = "/books", method = Method.POST, value = None, token = request.csrf)

// read it back
request.as[Book] match {
  case Right(book)  => ...
  case Left(errors) => ...
}
```

The page that emits the inputs and the handler that reads them then can't disagree about field
names, and a new field on the case class appears on both without a change here.

## Remove an action instead

To not have a route at all, say so in the model's companion:

```scala
import io.eezo.http.{Action, Actions}

object Book {
  given Actions[Book] = Actions.except(Action.Destroy)
}
```

`Actions.except(...)` keeps everything but the named ones; `Actions.only(...)` keeps only the
named ones. The derived pages stop linking to a removed action, so a show page without `Destroy`
has no Delete button.

## Order and shadowing

A handwritten route with the same method and path replaces the derived one. A handwritten route
that merely overlaps, `/books/latest` next to `/books/:id`, is matched first because the table
is ordered most specific first, and `eezo routes` reports the shadow so you can check it's what
you meant. Two handwritten routes on one method and path is a boot error.
