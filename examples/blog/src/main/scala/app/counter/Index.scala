package app.counter

import io.eezo.core.html.*
import io.eezo.core.html.Tags.*
import io.eezo.http.{Guarded, Request, Response}
import io.eezo.live.Live

import components.Counter

/** `app/counter/Index.scala` mounts `GET /counter`, calling `def index`.
  *
  * The handler returns a title and the live component, dropped in with `Live.mount`, which returns
  * the anchor and the client script as one fragment; the layout named in `Main` puts the document
  * around them. The framework injects only the mount, never the page (design/live.md §2.3): the
  * frame is the application's own.
  */
object Index {

  /** A demo page anyone may open; the blog has a guard, so saying so is not optional. */
  given Guarded[Index.type] = Guarded.public

  def index(request: Request): Response =
    Response.Ok(title("eezo live counter") ++ Live.mount(request, new Counter))
}
