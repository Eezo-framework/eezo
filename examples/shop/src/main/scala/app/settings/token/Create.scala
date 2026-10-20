package app.settings.token

import io.eezo.auth.Token
import io.eezo.core.html.*
import io.eezo.db.*
import io.eezo.db.Scopes.transact
import io.eezo.http.{Guarded, Request, Response}

import models.User

/** The one place a plain API token exists outside the program that receives it.
  *
  * It is answered on this page and nowhere else: not stored, not put in a flash or a redirect,
  * because a redirect would leave the next page with nothing to show it from but a copy kept
  * somewhere. The cost is that reloading this page posts again and issues a new token, which
  * revokes the one shown before; that is the safe direction for the mistake to go.
  */
object Create {
  given Guarded[Create.type] = User.guard.required

  def create(request: Request): Response = {
    val user          = User.guard.current(request)
    val (plain, hash) = Token.gen()
    transact(Table[User].update(user.copy(token = Some(hash))))
    Response.Ok(
      title("API token") ++ h1("Your API token") ++
        p("Copy it now. The shop keeps only its hash and cannot show it again.") ++
        pre(code(plain)) ++
        p("Send it as: Authorization: Bearer followed by the token.")
    )
  }
}
