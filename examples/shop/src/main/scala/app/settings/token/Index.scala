package app.settings.token

import io.eezo.core.html.*
import io.eezo.http.{Csrf, Guarded, Request, Response}

import models.User

/** Where a signed in user asks for an API token. A page with a form rather than a link straight to
  * the issue, because issuing replaces the token a program may be using, and a change like that is
  * a POST the CSRF check stands in front of.
  */
object Index {
  given Guarded[Index.type] = User.guard.required

  def index(request: Request): Response =
    Response.Ok(
      title("API token") ++ h1("API token") ++
        p(
          "A program calling the shop sends an API token in its Authorization header. ",
          "Issuing one replaces the last, so a program still holding the old one is refused."
        ) ++
        form(
          Attrs.method := "post",
          Attrs.action := "/settings/token",
          Csrf.hidden(request.csrf),
          button("Issue a new API token")
        )
    )
}
