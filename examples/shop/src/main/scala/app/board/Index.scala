package app.board

import io.eezo.core.html.*
import io.eezo.http.{Guarded, Request, Response}
import io.eezo.live.Live

import components.Board

object Index {
  given Guarded[Index.type] = Guarded.public

  def index(request: Request): Response =
    Response.Ok(title("sales") ++ Live.mount(request, new Board))
}
