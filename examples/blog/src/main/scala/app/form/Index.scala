package app.form

import io.eezo.core.html.*
import io.eezo.core.html.Tags.*
import io.eezo.http.{Guarded, Request, Response}
import io.eezo.live.Live

import components.Signup

/** `app/form/Index.scala` mounts `GET /form`: the live-form walkthrough page. */
object Index {

  /** A demo page anyone may open; the blog has a guard, so saying so is not optional. */
  given Guarded[Index.type] = Guarded.public

  def index(request: Request): Response =
    Response.Ok(
      Html.doctype ++ html(
        head(meta(Attrs.charset := "utf-8"), title("live signup")),
        body(Live.mount(request, new Signup))
      )
    )
}
