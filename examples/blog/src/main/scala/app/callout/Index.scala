package app.callout

import io.eezo.core.html.*
import io.eezo.core.html.Tags.*
import io.eezo.http.{Request, Response}
import io.eezo.live.Live

import components.Callout

/** `app/callout/Index.scala` mounts `GET /callout`: the M6 walkthrough page. */
object Index {

  def index(request: Request): Response = {
    val _ = request
    Response.Ok(
      Html.doctype ++ html(
        head(meta(Attrs.charset := "utf-8"), title("calling out")),
        body(Live.mount(new Callout(_)))
      )
    )
  }
}
