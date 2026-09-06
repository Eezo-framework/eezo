package app

import java.time.LocalTime

import io.eezo.core.html.*
import io.eezo.http.Request
import io.eezo.http.Response

/** `app/Index.scala` mounts `GET /`, calling `def index` — the filename convention.
  *
  * A handwritten route is a plain function from `Request` to `Response`; the page is an `Html`
  * value from eezo's own DSL. Handlers run on virtual threads, so blocking in one is fine.
  */
object Index {

  def index(request: Request): Response = {
    val greeting =
      if (LocalTime.now().isBefore(LocalTime.NOON)) "good morning" else "good afternoon"

    val name = request.queryParam("name").getOrElse("there")

    Response.Ok(
      Html.doctype ++ html(
        head(meta(Attrs.charset := "utf-8"), title("eezo todo")),
        body(
          h1(s"$greeting, $name"),
          p("A model, a schema, and two handwritten routes."),
          ul(
            li(a(Attrs.href := "/todos", "the todo list"), " — seven routes derived from one case class"),
            li(a(Attrs.href := "/health", "health"), " — a custom handwritten route"),
            li(a(Attrs.href := "/?name=eezo", "say hi to eezo"), " — query parameters")
          )
        )
      )
    )
  }
}
