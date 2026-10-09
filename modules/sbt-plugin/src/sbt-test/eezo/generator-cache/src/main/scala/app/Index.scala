package app

import io.eezo.http.Request
import io.eezo.http.Response

/** A handwritten route, so the table has a row that is not derived. Nothing here is compiled: the
  * generator reads the path of this file for the route and its text for the `def` the route names.
  */
object Index {

  def index(request: Request): Response = Response.Ok("index")
}
