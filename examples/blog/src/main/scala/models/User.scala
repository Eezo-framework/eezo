package models

import io.eezo.auth.{Guard, Password}
import io.eezo.core.Id
import io.eezo.core.html.Url
// The whole package, because `findById` and `where` are top-level extensions on `Table[T]` rather
// than members of it: `db` keeps `Table` a plain description so the migration layer can consume one
// without acquiring a transaction capability.
import io.eezo.db.*
import io.eezo.db.Scopes.read

/** Who can sign in to the blog.
  *
  * One derivation, not three. `Table` puts it in Postgres; `Form` and `Resource` are deliberately
  * absent, because there is no page that edits a user: registration and changing a password are
  * flows of their own, and an `Update` route over this model would take a password out of a form
  * and store whatever text arrived. A model with a `derives` clause and no `Resource` mounts
  * nothing, which is what the generated table's `guardForModel` reads off it, so `User` is never
  * asked to declare who may reach routes it does not have.
  *
  * The password field is a `Password`, never a `String` and never a `Password.Plain`. The type is
  * the guarantee: a `Plain` has no `Column`, so a model that tried to store the text as typed would
  * not compile, and this one cannot hold anything but a hash.
  */
case class User(id: Id[User], email: String, password: Password) derives Table

object User {

  /** How a `Password` reaches a column.
    *
    * It is written here, in the application, rather than shipped by `auth` or by `db`, because it is
    * the one line where the two modules meet and neither depends on the other: `auth` knows nothing
    * about a `ResultSet` and `db` knows nothing about bcrypt. A seam in `db` for library types
    * stored as text is worth building when there is a second such type; with one, it would be an
    * abstraction designed against a single implementation.
    */
  given Column[Password] = Column[String].imap(Password.stored)(_.value)

  /** The lookup the guard authenticates through.
    *
    * `Store[A]` has five operations and no lookup by field, on purpose, so this is written against
    * `Table` directly. That is not a gap being worked around: a store is what the seven derived
    * routes need, and an application that wants a query writes one.
    */
  def byEmail(email: String): Option[User] =
    read(Table[User].where(_.email === email).first())

  /** The blog's guard, and the reason `Main.scala` did not have to change.
    *
    * `verify` runs outside the `read` scope, which is the whole shape of the second line: a bcrypt
    * verify at strength 12 takes about a quarter of a second, and folding it into the `read` block
    * would hold a pooled connection open for all of it, on every login attempt including the wrong
    * ones.
    *
    * An unknown email is answered without hashing anything, so a wrong email and a wrong password
    * take measurably different times. That is user enumeration by timing, and it is accepted here
    * rather than hidden behind a dummy verify that the next change to this method would silently
    * drop.
    */
  given guard: Guard[User] = Guard[User](
    find = id => read(Table[User].findById(id)),
    authenticate = (email, plain) => byEmail(email).filter(_.password.verify(plain)).map(_.id),
    home = Url.Mounted("/posts")
  )
}
