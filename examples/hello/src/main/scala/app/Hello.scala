package app

import eezo.core.html.*
import eezo.http.Request
import eezo.http.Response

/** `app/Hello.scala` mounts `GET /hello`, calling `def hello`.
  *
  * The filename is the route. A custom name is always a GET at a segment of its own name, and it
  * calls a `def` of that same name; the seven REST names — `Index`, `New`, `Show`, `Edit`,
  * `Create`, `Update`, `Destroy` — carry their own methods and paths instead.
  */
object Hello {

  def hello(request: Request): Response = {
    val thread = Thread.currentThread()

    Response.Ok(
      Html.doctype ++ html(
        head(
          meta(Attrs.charset := "utf-8"),
          title("hello, eezo")
        ),
        body(
          h1("hello, eezo"),
          p("This page is an ", code("Html"), " value rendered by eezo's own DSL."),
          dl(
            dt("handler thread"),
            dd(code(thread.toString)),
            dt("virtual thread"),
            dd(code(thread.isVirtual.toString)),
            dt("path"),
            dd(code(request.path))
          )
        )
      )
    )
  }
}
