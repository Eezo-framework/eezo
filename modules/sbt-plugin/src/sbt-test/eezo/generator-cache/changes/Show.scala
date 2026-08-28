package app.widgets._id

import io.eezo.http.Request
import io.eezo.http.Response

/** Copied in mid-test, to `src/main/scala/app/widgets/_id/Show.scala`, where the directory names
  * make it `GET /widgets/:id`.
  */
object Show {

  def show(request: Request): Response = Response.Ok("show")
}
