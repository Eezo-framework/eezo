package app

import io.eezo.core.html.*
import io.eezo.http.{Guarded, Request, Response}

import models.User

object Index {
  given Guarded[Index.type] = User.guard.required

  def index(request: Request): Response =
    Response.Ok(title("the shop") ++ h1("the shop") ++ p("nothing for sale yet"))
}
