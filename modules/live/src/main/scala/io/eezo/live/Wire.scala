package io.eezo.live

import io.eezo.core.html.Html
import io.eezo.core.internal.Json

/** The patch protocol's outbound half: what the server writes onto the socket.
  *
  * One message kind so far. The envelope carries `kind` from the first message on, so the client
  * dispatches on one field forever and a new kind is a new arm rather than a new format. Names are
  * words, not initials: the byte budget is a goal, not a test (design/live.md §1.1), and a frame
  * someone reads in DevTools should say what it is.
  *
  * `Html` payloads are rendered here, at the boundary, and nowhere earlier: patches carry trees so
  * the reference applier can apply them structurally, and the wire carries markup because that is
  * what `template.innerHTML` on the other side wants.
  */
object Wire {

  def patches(patches: List[Patch]): String =
    Json.render(
      Json.Obj(
        List(
          "kind"    -> Json.Str("patches"),
          "patches" -> Json.Arr(patches.map(one))
        )
      )
    )

  private def one(patch: Patch): Json = patch match {
    case Patch.SetChildren(path, expect, children) =>
      Json.Obj(
        List(
          "op"     -> Json.Str("setChildren"),
          "path"   -> Json.Arr(path.map(i => Json.Num(i.toLong))),
          "expect" -> expect.fold[Json](Json.Null)(Json.Str.apply),
          "html"   -> Json.Str(rendered(children))
        )
      )
  }

  private def rendered(children: Vector[Html]): String = {
    val sb = new StringBuilder
    children.foreach(_.renderTo(sb))
    sb.result()
  }
}
