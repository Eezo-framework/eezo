# Adding sign in

This page continues from [the model tutorial](first-model.md). By the end, the bookshelf has
users, a login page, and every book belongs to whoever created it: anyone signed in can read the
shelf, and only the owner can edit or delete a book. About thirty minutes. There's no sign-up
page in this tutorial; the first users are created from the command line.

## The auth artifact

Add `eezo-auth` to `build.sbt`, next to the umbrella:

```scala
libraryDependencies += "io.eezo" %% "eezo" % "0.0.0+105-756d1ed0+20260929-1242-SNAPSHOT"
libraryDependencies += "io.eezo" %% "eezo-auth" % "0.0.0+105-756d1ed0+20260929-1242-SNAPSHOT"

// two mains now: the application and CreateUser, written below
Compile / run / mainClass := Some("Main")
```

The umbrella already contains `eezo-auth`. Naming it separately is what turns on a rule: once an
application declares a way of signing in, every route it mounts has to say who may reach it.
You'll see what that looks like as a compile error shortly.

## The user

Create `src/main/scala/models/User.scala`:

```scala
package models

import io.eezo.auth.{Guard, Password}
import io.eezo.core.Id
import io.eezo.core.html.Url
import io.eezo.db.*
import io.eezo.db.Scopes.read

case class User(id: Id[User], email: String, password: Password) derives Table

object User {

  given Column[Password] = Column[String].imap(Password.stored)(_.value)

  def byEmail(email: String): Option[User] =
    read(Table[User].where(_.email === email).first())

  given guard: Guard[User] = Guard[User](
    find = id => read(Table[User].findById(id)),
    credentials = email => byEmail(email).map(user => (user.id, user.password)),
    home = Url.Mounted("/books")
  )
}
```

A few things in here.

`User` derives `Table` only. No `Form`, no `Resource`: there's no page that lists users or lets
anyone edit one, so nothing mounts.

`Password` is a hash. It's the only form a password takes in a model or a table; there's no way
to put plain text into one except by hashing it, and when it's a form input it hashes on the way
in and never renders its value. The `Column[Password]` line tells the table to store it as text.

The guard is the thing that signs people in. You give it two functions: `find`, which turns the
id kept in the session back into a `User`, and `credentials`, which answers an email with that
user's id and password hash, or nothing. The guard does the comparing, so the plain text never
leaves it. `home` is where a successful sign in lands when there's nowhere better to go.

## The owner

Give `Book` an owner, and say which actions are the owner's alone:

```scala
package models

import io.eezo.core.Id
import io.eezo.db.Table
import io.eezo.http.{Action, Form, Owned, Resource}

case class Book(
    id: Id[Book],
    owner: Id[User],
    title: String,
    author: String,
    pages: Int,
    notes: Option[String]
) derives Table,
      Form,
      Resource

object Book {
  given Owned[Book, User] =
    User.guard.required[Book].owning(_.owner).except(Action.Index, Action.Show)
}
```

`owner` is a field like any other, except that it never appears on a form or a page. eezo fills
it from the signed-in user when a book is created, and a hand-crafted request carrying some
other user's id gets its own id written instead.

The `given Owned` line reads left to right. `required[Book]`: every book route needs a signed-in
user. `owning(_.owner)`: the rows a route reads and writes are the current user's. `except(Index,
Show)`: the list and the detail page see everybody's books. So any user can browse the shelf and
open any book, and the five remaining routes, including edit and delete, work on your own books
only.

Register the users table in `AppSchema.scala`:

```scala
import io.eezo.db.Schema
import models.{Book, User}

object AppSchema extends Schema {
  val books = table[Book].unique(_.title)
  val users = table[User].unique(_.email)
}
```

## Every page says who may reach it

Compile now, and it fails:

```
[error] 9 |    guardFor[app.Hello.type]("app.Hello").mounting(
[error]   |    ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
[error]   |no Guarded given for app.Hello, and this application's build declares eezo-auth, so every mounted route has to say who may reach it. In its companion, one of:
[error]   |  given io.eezo.http.Guarded[T] = <yourGuard>.required
[error]   |  given io.eezo.http.Guarded[T] = io.eezo.http.Guarded.public
```

The same error repeats for `app.Index`. The two handwritten pages have no model behind them, so
the declaration goes on the page's own object. Both pages stay public, so add this line inside
`object Index` and inside `object Hello`:

```scala
import io.eezo.http.Guarded

object Index {

  given Guarded[Index.type] = Guarded.public
  // ...
}
```

A page that should need a sign-in would say `User.guard.required` instead. What you can't do is
say nothing.

```bash
eezo routes
```

```
12 routes:
  GET /hello
  GET /
  GET /login
  POST /login
  POST /logout
  GET /books
  GET /books/new
  GET /books/:id
  GET /books/:id/edit
  POST /books
  PUT /books/:id
  DELETE /books/:id
```

The three routes in the middle are the guard's. Declaring `Owned` on `Book` mounted them, so the
login page a refusal redirects to can't be forgotten.

## The migration

The database needs a `user` table and an `owner` column. If you created a book in the previous
tutorial, delete it from its page first: `owner` is `not null`, and Postgres won't add such a
column over an existing row.

```bash
eezo status
```

```
3 difference(s) between model and database:

  create table user
  + book.owner uuid not null
  + index uq_user_email on user
```

```bash
eezo freeze add users
eezo migrate --apply
```

```
  0003  0003_add_users.sql  (3 statements)

applied ✓
database matches model ✓
```

## The first user

There's no sign-up page, so make a small tool for the operator. Create
`src/main/scala/CreateUser.scala`:

```scala
import io.eezo.auth.Password
import io.eezo.core.Id
import io.eezo.db.*
import io.eezo.db.Scopes.transact
import io.eezo.http.Field
import models.User

object CreateUser extends DbApp {

  override def schema: Schema = AppSchema

  override def boot(): Unit = {
    val email    = sys.env.getOrElse("BOOKSHELF_EMAIL", sys.error("BOOKSHELF_EMAIL is not set"))
    val password = sys.env.getOrElse("BOOKSHELF_PASSWORD", sys.error("BOOKSHELF_PASSWORD is not set"))
    val hashed   = Field[Password].read(password).fold(message => sys.error(message), identity)
    transact {
      Table[User].insert(User(Id.gen(), email, hashed))
    }
    println(s"created $email")
  }
}
```

It's a second application in the same project, on the database edge alone: `DbApp` gives it
the database around `boot` and nothing else. It reads the email and password from the
environment, because a command-line argument is visible to every process on the machine, and
it hashes through `Field[Password]`, the same door a login form goes through, so a password
longer than the 72 bytes bcrypt reads is refused here with the same message.

Type the password at a prompt so it doesn't end up in your shell history. This line on its own:

```bash
printf 'password: '; IFS= read -rs BOOKSHELF_PASSWORD; echo; export BOOKSHELF_PASSWORD
```

Then:

```bash
BOOKSHELF_EMAIL=alice@example.com sbt "runMain CreateUser"
unset BOOKSHELF_PASSWORD
```

```
created alice@example.com
```

Make a second user the same way; you'll want two to see ownership do anything.

## Try it

Start `eezo dev` and open http://localhost:8080/books. You're not signed in, so you get a 303 to
`/login`, and the guard remembers where you were going.

The login page is a form with an email and a password. Get the password wrong and the form
comes back with a 422 and "that email and password do not match", the same message whether the
email exists or not. Get it right and you land on `/books`, the page you asked for.

Create a book. The form has Title, Author, Pages and Notes on it and no Owner; the owner is you.
The book's page shows the fields, an Edit link and a Delete button.

Now sign out and sign in as the second user. The list still shows the first user's book, and its
page still opens, but the Edit link and the Delete button are gone. Type the edit address by
hand, `/books/<id>/edit`, and you get a 403: "this Book belongs to another user". A `PUT` or a
`DELETE` sent straight to the address answers the same. An id that names no book at all is still
a 404, so the two cases are told apart.

Signing out is a `POST /logout`, which empties the session. The guard builds that form for you,
`User.guard.logoutForm(request)`, because a `POST` has to carry the CSRF token and a form that
forgets it is refused. Put it on the index page, where it renders a Sign out button for a browser
that's signed in and nothing for one that isn't:

```scala
body(
  h1("it works"),
  User.guard.logoutForm(request)
)
```

## Two things to know

A sign in lasts fourteen days from the moment it was made, however active the user is. The
guard's `lifetime` parameter changes that.

The login page answers every attempt, and each attempt costs one bcrypt, about a quarter of a
second, whether the password is right or wrong. That equal cost is what keeps the response time
from saying which emails have accounts. It also means there's no rate limit in the application;
put one on `POST /login` at your reverse proxy or your platform before strangers can reach it.

## Where to go next

The bookshelf now has users, and a deploy needs one more thing from you: the key that signs the
session cookie. Set it where the application runs, `fly secrets set EEZO_SECRET=<a long random
string>` on Fly, before [the deploy walkthrough](deploy-to-fly.md); without it, eezo mints a
throwaway key per process and every restart signs everyone out. [Guards and ownership](../explanation/guards-and-ownership.md) explains the three questions the
guard keeps apart, and [the live tutorial](a-live-page.md) shows what a live page does behind a
guarded route.
