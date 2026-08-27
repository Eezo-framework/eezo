package app

import io.eezo.core.html.*
import io.eezo.http.Request
import io.eezo.http.Response

/** `app/Index.scala` mounts `GET /`, calling `def index`.
  *
  * A handwritten route beside seven derived ones: the generated table lists this one first, so a
  * handwritten route always wins a path a derived one would also match.
  */
object Index {

  def index(request: Request): Response = {
    val _ = request

    Response.Ok(
      Html.doctype ++ html(
        head(
          meta(Attrs.charset := "utf-8"),
          title("eezo blog")
        ),
        body(
          h1("eezo blog"),
          p("Seven routes, derived from one case class."),
          p(a(Attrs.href := "/posts", "All posts"))
        )
      )
    )
  }
}
