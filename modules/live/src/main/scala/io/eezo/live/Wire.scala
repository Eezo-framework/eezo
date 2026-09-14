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
  * what `template.innerHTML` on the other side wants. Every op spells `path` the same way, `expect`
  * is the node name or null (null only at the anchor), and node payloads are always under `html` —
  * the applier reads one shape, not seven.
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
    case Patch.SetText(path, text) =>
      obj("setText", path, "text" -> Json.Str(text))

    case Patch.SetAttr(path, expect, name, value) =>
      obj(
        "setAttr",
        path,
        "expect" -> Json.Str(expect),
        "name"   -> Json.Str(name),
        "value"  -> Json.Str(value)
      )

    case Patch.RemoveAttr(path, expect, name) =>
      obj("removeAttr", path, "expect" -> Json.Str(expect), "name" -> Json.Str(name))

    case Patch.ReplaceNode(path, expect, node) =>
      obj("replaceNode", path, "expect" -> Json.Str(expect), "html" -> Json.Str(node.render))

    case Patch.RemoveNode(path, expect) =>
      obj("removeNode", path, "expect" -> Json.Str(expect))

    case Patch.AppendChildren(path, expect, children) =>
      obj(
        "appendChildren",
        path,
        "expect" -> Json.Str(expect),
        "html"   -> Json.Str(rendered(children))
      )

    case Patch.SetChildren(path, expect, children) =>
      obj(
        "setChildren",
        path,
        "expect" -> expect.fold[Json](Json.Null)(Json.Str.apply),
        "html"   -> Json.Str(rendered(children))
      )
  }

  private def obj(op: String, path: List[Int], fields: (String, Json)*): Json =
    Json.Obj(
      ("op"     -> Json.Str(op)) ::
        ("path" -> Json.Arr(path.map(i => Json.Num(i.toLong)))) ::
        fields.toList
    )

  private def rendered(children: Vector[Html]): String = {
    val sb = new StringBuilder
    children.foreach(_.renderTo(sb))
    sb.result()
  }
}
