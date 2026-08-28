package app

import io.eezo.core.html.*
import io.eezo.http.Request
import io.eezo.http.Response

/** A handwritten route, so the table has a row the derivation did not put there. */
object Index {

  def index(request: Request): Response = {
    val _ = request
    Response.Ok(html(body(h1("index"))))
  }
}
