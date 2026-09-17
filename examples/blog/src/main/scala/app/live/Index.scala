package app.live

import io.eezo.core.html.*
import io.eezo.core.html.Tags.*
import io.eezo.http.{Request, Response}
import io.eezo.live.Live

import components.Guestbook

/** `app/live/Index.scala` mounts `GET /live`: the everything-on-one-page demo that
  * `docs/live.md` walks through.
  */
object Index {

  def index(request: Request): Response = {
    val _ = request
    Response.Ok(
      Html.doctype ++ html(
        head(meta(Attrs.charset := "utf-8"), title("guestbook")),
        body(Live.mount(new Guestbook))
      )
    )
  }
}
