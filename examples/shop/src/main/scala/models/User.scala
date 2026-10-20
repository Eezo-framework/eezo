package models

import io.eezo.auth.{Guard, Password, Token}
import io.eezo.core.Id
import io.eezo.core.html.Url
import io.eezo.db.*
import io.eezo.db.Scopes.read

/** `token` holds the hash of the one API token the user was issued, and nothing until one is: a
  * program acts as this user for as long as the hash is here, so replacing it is revoking the last
  * one.
  */
final case class User(id: Id[User], email: String, password: Password, token: Option[Token])
    derives Table

object User {
  given Column[Password] = Column[String].imap(Password.stored)(_.value)
  given Column[Token]    = Column[String].imap(Token.stored)(_.value)

  def byEmail(email: String): Option[User] = {
    read(Table[User].where(_.email === email).first())
  }

  /** The user a program's API token was issued to. The guard hands over the hash of what the
    * program sent, so this compares stored hash with stored hash; a user with no token holds SQL
    * null, which equals nothing, so no hash can find them.
    */
  def byToken(hash: Token): Option[User] =
    read(Table[User].where(_.token === Some(hash)).first())

  given guard: Guard[User] = Guard[User](
    find = id => read(Table[User].findById(id)),
    credentials = email => byEmail(email).map(user => (user.id, user.password)),
    home = Url.Mounted("/products"),
    tokens = hash => byToken(hash).map(_.id)
  )
}
