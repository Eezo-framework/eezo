package app.board

import io.eezo.core.html.*
import io.eezo.core.html.Tags.*
import io.eezo.http.{Guarded, Request, Response}
import io.eezo.live.Live

import components.Board

/** `app/board/Index.scala` mounts `GET /board`. `Main.scala` mounts the same route a second time
  * under `/admin`, which is what makes the board the mount-prefix demo too.
  */
object Index {

  /** A demo page anyone may open; the blog has a guard, so saying so is not optional. */
  given Guarded[Index.type] = Guarded.public

  def index(request: Request): Response =
    Response.Ok(title("shared board") ++ Live.mount(request, new Board))
}
