package app

import io.eezo.http.Request
import io.eezo.http.Response
import site.Landing

/** `app/Index.scala` mounts `GET /`: the front page. */
object Index {

  def index(request: Request): Response = Response.Ok(Landing.page(request))
}
