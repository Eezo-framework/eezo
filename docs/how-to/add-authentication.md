# Add authentication

Put a login page in front of routes, decide per action who may take it, and scope a model's rows
to their owner. [Adding sign in](../tutorials/sign-in.md) walks through the same steps with
explanation; this is the recipe.

## 1. The artifact

```scala
libraryDependencies += "io.eezo" %% "eezo-auth" % eezoVersion
Compile / run / mainClass := Some("Main")   // there will be a second main below
```

Naming `eezo-auth` turns on the rule that every mounted route declares who may reach it.

## 2. A user and a guard

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
    home = Url.Mounted("/")
  )
}
```

`Guard[User]` also takes `login` (default `/login`) and `lifetime` (default fourteen days from
the sign in). Register the table in your `Schema` with `.unique(_.email)`.

## 3. Declare every route

A model, in its companion:

```scala
import io.eezo.http.{Action, Owned}

object Book {
  // signed in for everything; the owner's rows for everything but the list and the detail page
  given Owned[Book, User] =
    User.guard.required[Book].owning(_.owner).except(Action.Index, Action.Show)
}
```

`owner: Id[User]` is a field on the model. It's never on a form; eezo fills it from the current
user on create and update.

Without ownership, just a guard:

```scala
given Guarded[Book] = User.guard.required          // every action
given Guarded[Book] = User.guard.only(Action.Index) // just the list
```

A handwritten page, in its own object:

```scala
given Guarded[Index.type] = Guarded.public
given Guarded[Admin.type] = User.guard.required
```

A route that says nothing is a compile error in the generated table, naming the object.

## 4. The routes you get

Declaring on a model mounts `GET /login`, `POST /login` and `POST /logout` beside it. Under a
mount they move with the resource, so `/admin/login`. A refused page answers 303 to the login
page and remembers where you were going.

## 5. The first user

No sign-up page is mounted. A second `DbApp` in the project makes a user from the environment:

```scala
object CreateUser extends DbApp {
  override def schema: Schema = AppSchema
  override def boot(): Unit = {
    val email    = sys.env.getOrElse("APP_EMAIL", sys.error("APP_EMAIL is not set"))
    val password = sys.env.getOrElse("APP_PASSWORD", sys.error("APP_PASSWORD is not set"))
    val hashed   = Field[Password].read(password).fold(message => sys.error(message), identity)
    transact { Table[User].insert(User(Id.gen(), email, hashed)) }
  }
}
```

Type the password at a prompt so it stays out of your shell history, then run
`APP_EMAIL=... sbt "runMain CreateUser"`.

## 6. Sign out

```scala
User.guard.logoutForm(request)
```

Renders a Sign out button, with the CSRF token, for a browser that's signed in, and nothing
otherwise. Outside a mount, pass the finished address: `logoutForm(request, Url.Absolute("/admin/logout"))`.

## 7. In the handler

`User.guard.current(request)` is the signed-in user on a guarded route. On a public route there
may be nobody, and asking is an error, so don't.

## Production notes

- Set `EEZO_SECRET` where the application runs. Without it eezo signs sessions with a throwaway
  key per process, and every restart signs everyone out.
- The login page answers every attempt at one bcrypt each, about a quarter of a second, right or
  wrong. There's no rate limit in the application; put one on `POST /login` at your reverse
  proxy or your platform.
- A sign in lasts `lifetime` from the moment it was made, however active the user is.
