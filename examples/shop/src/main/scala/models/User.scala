package models

import io.eezo.auth.{Guard, Password}
import io.eezo.core.Id
import io.eezo.core.html.Url
import io.eezo.db.*
import io.eezo.db.Scopes.read

final case class User(id: Id[User], email: String, password: Password) derives Table

object User {
  given Column[Password] = Column[String].imap(Password.stored)(_.value)

  def byEmail(email: String): Option[User] = {
    read(Table[User].where(_.email === email).first())
  }
  
  given guard: Guard[User] = Guard[User](
    find = id => read(Table[User].findById(id)),
    credentials = email => byEmail(email).map(user => (user.id, user.password)),
    home = Url.Mounted("/products")
  )
}
