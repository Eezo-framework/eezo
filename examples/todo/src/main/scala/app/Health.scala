package app

import io.eezo.core.html.*
import io.eezo.http.Request
import io.eezo.http.Response

/** A file under `app/` whose name is not one of the seven REST names mounts a custom GET at a
  * segment of its own name: `app/Health.scala` → `GET /health`, calling `def health`.
  */
object Health {

  def health(request: Request): Response = {
    val _ = request
    Response.Ok(
      Html.doctype ++ html(
        head(meta(Attrs.charset := "utf-8"), title("health")),
        body(pre(code(s"ok\nuptime-ish: ${System.nanoTime()}\nthread: ${Thread.currentThread()}")))
      )
    )
  }
}
