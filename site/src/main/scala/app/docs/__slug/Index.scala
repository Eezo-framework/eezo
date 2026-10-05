package app.docs.__slug

import io.eezo.http.Request
import io.eezo.http.Response
import site.Docs

/** `app/docs/__slug/Index.scala` mounts the catch-all under `/docs`, `*slug` in the generated
  * table: a directory named `__slug` is a catch-all segment, so one route serves every page however
  * deep its address, `/docs/live` and `/docs/adr/0001-...` alike. A slug with no page behind it is
  * a 404 the page raises itself.
  */
object Index {

  def index(request: Request): Response = Docs.render(request, request.param[String]("slug"))
}
