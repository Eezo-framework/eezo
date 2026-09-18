package app.counter

import io.eezo.core.html.*
import io.eezo.core.html.Tags.*
import io.eezo.http.{Request, Response}
import io.eezo.live.Live

import components.Counter

/** `app/counter/Index.scala` mounts `GET /counter`, calling `def index`.
  *
  * The handler owns the whole document — title, styling, chrome — and drops the live component in
  * with `Live.mount`, which returns the anchor and the client script as one fragment. This is the
  * envelope rule (design/live.md §2.3): the framework injects only the mount, never the page.
  */
object Index {

  def index(request: Request): Response = {
    val _ = request
    Response.Ok(
      Html.doctype ++ html(
        head(meta(Attrs.charset := "utf-8"), title("eezo live counter")),
        body(Live.mount(new Counter))
      )
    )
  }
}
