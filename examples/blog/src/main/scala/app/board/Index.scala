package app.board

import io.eezo.core.html.*
import io.eezo.core.html.Tags.*
import io.eezo.http.{Request, Response}
import io.eezo.live.Live

import components.Board

/** `app/board/Index.scala` mounts `GET /board`. `Main.scala` mounts the same route a second time
  * under `/admin`, which is what makes the board the mount-prefix demo too.
  */
object Index {

  def index(request: Request): Response = {
    val _ = request
    Response.Ok(
      Html.doctype ++ html(
        head(meta(Attrs.charset := "utf-8"), title("shared board")),
        body(Live.mount(new Board))
      )
    )
  }
}
