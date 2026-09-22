package app.live

import io.eezo.core.html.*
import io.eezo.core.html.Tags.*
import io.eezo.http.{Guarded, Request, Response}
import io.eezo.live.Live

import components.Guestbook

/** `app/live/Index.scala` mounts `GET /live`: the everything-on-one-page demo that
  * `docs/live.md` walks through.
  */
object Index {

  /** A demo page anyone may open; the blog has a guard, so saying so is not optional. */
  given Guarded[Index.type] = Guarded.public

  def index(request: Request): Response =
    Response.Ok(
      Html.doctype ++ html(
        head(meta(Attrs.charset := "utf-8"), title("guestbook")),
        body(Live.mount(request, new Guestbook))
      )
    )
}
