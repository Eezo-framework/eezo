package app.docs

import io.eezo.http.Request
import io.eezo.http.Response
import site.Docs

/** `app/docs/Index.scala` mounts `GET /docs`: the overview, which is the repository's README. */
object Index {

  def index(request: Request): Response = Docs.render(request, "")
}
