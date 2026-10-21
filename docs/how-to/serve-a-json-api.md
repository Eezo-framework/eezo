# Serve a JSON API

Expose a model to a program as five JSON routes, write an API route of your own, and let a
program prove who it is with an API token. The shop example,
[shop: a small shop with Stripe](../../examples/shop/README.md), is the running version with
the `curl` calls and their answers.

## 1. The adapter

```scala
libraryDependencies += "io.eezo" %% "eezo-upickle" % eezoVersion
```

`eezo-http` has no JSON library on its classpath. A model crosses the wire through an
`api.Serde`, and `eezo-upickle` gives one to any case class through upickle. The umbrella does
not carry it, so it is a line of its own in the build.

## 2. The model

```scala
package models

import io.eezo.core.Id
import io.eezo.db.Table
import io.eezo.http.{Api, Form, Resource}
import io.eezo.json.upickle.given

case class Book(id: Id[Book], title: String, author: String, pages: Int)
    derives Table, Form, Resource, Api
```

`Api` needs the `id: Id[Book]` field and a `Serde[Book]` in scope where the model derives,
which is what the import brings. `Resource` is optional beside it: a model only a program reads
derives `Api` and no `Form`.

## 3. The routes you get

| route | on success |
|---|---|
| `GET /api/books` | 200, a JSON array of every row |
| `GET /api/books/:id` | 200, the row; 404 when there is none |
| `POST /api/books` | 201, the row as stored, with its address in `Location` |
| `PUT /api/books/:id` | 200, the row as stored; 404 when there is none |
| `DELETE /api/books/:id` | 204, no body |

The plural is the one the pages use, so `/books` and `/api/books` read and write the same rows
through the same store. A body that is not JSON is a 400 and JSON that does not fit the model
is a 422, both as `application/problem+json` with the adapter's reason in `detail`. The key is
eezo's: `create` mints it and `update` reads it from the path, so a body need not name `id`
and one that does is overwritten.

Subtracting an action works as on the pages, with one declaration for both:

```scala
object Book {
  given Actions[Book] = Actions.except(Action.Destroy)
}
```

`New` and `Edit` have no JSON route to subtract. Both serve a form, and a program builds its
own body.

## 4. A handwritten API route

A file under `app/` whose handler takes an `api.Request` is an API route. The file name rules
are the ones every handwritten route follows, so `app/api/books/Count.scala` mounts
`GET /api/books/count`:

```scala
package app.api.books

import io.eezo.db.*
import io.eezo.db.Scopes.read
import io.eezo.http.{Guarded, Response, api}
import io.eezo.json.upickle.given
import models.Book

case class Count(books: Int)

object Count {
  given Guarded[Count.type] = Guarded.public

  def count(request: api.Request): Response =
    Response.Ok(Count(read(Table[Book].all()).size))
}
```

`Count` is a payload: a case class that crosses the wire and mounts nothing, so it derives
nothing and the adapter still writes it. `Response.Ok(value)` answers 200 with the value as
JSON, `Response.Created(value)` answers 201, and a header goes on with `withHeader`:

```scala
Response.Created(book).withHeader("Location", s"/api/books/${book.id.show}")
```

An `api.Request` carries the method, the path, the query, the headers, the body and the path
parameters, and nothing a browser session would: no session, no flash, no form and no CSRF
token. A body is read into a model with `request.as[Book]`, which throws the 400 or the 422
rather than returning them, since there is no form to render again with its errors. A
handwritten file at the same method and path as a derived JSON route takes it over.

## 5. Who may call it

The declaration is the one the pages use, in the model's companion, and it covers both sets of
routes:

```scala
object Book {
  given Guarded[Book] = User.guard.except(Action.Index, Action.Show)
}
```

Here anyone reads the list and one row, on a page or as JSON, and every write needs a user. The
guard knows a browser by its session and a program by its API token, so the same declaration
answers a browser's refusal with a 303 to the login page and a program's with a 401 that
carries `WWW-Authenticate: Bearer`. An owned model is owned on its JSON routes too: a covered
list answers the caller's rows, and a create records the caller as owner whatever the body
says.

A guard that wraps an API route has to know how to find a user by token:

```scala
given guard: Guard[User] = Guard[User](
  find        = id => read(Table[User].findById(id)),
  credentials = email => byEmail(email).map(user => (user.id, user.password)),
  home        = Url.Mounted("/books"),
  tokens      = hash => byToken(hash).map(_.id)
)
```

`tokens` turns the hash of what a program sent into the user's key, or nothing. A guard without
it refuses to wrap an API route at boot, naming the route, because no program could ever get
through. [Add authentication](add-authentication.md) has the token column and the lookup.

## 6. The token

A program sends it as `Authorization: Bearer <token>`. It is yours to issue: `Token.gen()`
gives the plain text to show once and the hash to store, and the guard hashes what arrives and
asks `tokens` whose hash it is. The shop issues one from a page behind the session and stores
the hash on the user, so issuing a new one revokes the last. The plain text is never stored
and never shown again.

```bash
curl -i -X POST localhost:8080/api/books \
  -H "Authorization: Bearer $TOKEN" -d '{"title":"Dune","author":"Herbert","pages":412}'
```

## 7. In a test

`dispatch` runs an API route as the server would: no session is read and no CSRF token is
asked for. Build the request with the header and the body, and read the status back:

```scala
val response = Routes.table().dispatch(
  Request(
    Method.POST,
    "/api/books",
    Map.empty,
    Map("Authorization" -> Seq(s"Bearer $token")),
    """{"title":"Dune","author":"Herbert","pages":412}""".getBytes,
    Map.empty
  )
)
assertEquals(response.status, 201)
```

A refusal is the thrown `Unauthorized` at this level. The 401 and its `WWW-Authenticate`
header are the boundary's, which a test through `dispatch` never reaches.

## What is not here

No `Content-Type` is checked on the way in and no `Accept` is negotiated on the way out: an API
route reads JSON and answers JSON. There is no pagination, no filtering, no `PATCH` and no bulk
route. A browser page posting JSON with its session cookie is not an API route.
