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
private[live] object Wire {

  /** What a browser may say, after validation. Everything else is a `Left` with the reason, which
    * the socket answers with an error frame and survives: every inbound frame is untrusted input
    * (design/live.md §1.1), and a malformed one must never take down the page loop.
    */
  enum ClientMessage {

    /** The first message after the socket opens: the client is ready to apply patches, and reports
      * the rendered `data-eezo-base` so a mounted page's re-renders can follow the same prefix the
      * response was rewritten with (design/live.md §2.6).
      */
    case Join(base: String)

    case Emit(event: Event)

    /** Keeps the connection under the server's idle timeout; answered with a pong. */
    case Ping

    /** The applier refused patches (design/live.md §1.1, fail loud): the client reports rather than
      * sitting on a wrong DOM, and the server answers with a full resync.
      */
    case PatchesFailed(reasons: List[String])
  }

  /** More entries than any real form; fewer than a hostile client would like. */
  private val MaxPayloadEntries = 64

  def read(text: String): Either[String, ClientMessage] =
    Json.parse(text).flatMap {
      case Json.Obj(fields) =>
        val map = fields.toMap
        map.get("kind") match {
          case Some(Json.Str("join")) =>
            map.get("base") match {
              case Some(Json.Str(base)) => Right(ClientMessage.Join(base))
              case _                    => Left("a join carries a string 'base'")
            }
          case Some(Json.Str("event"))  => event(map)
          case Some(Json.Str("ping"))   => Right(ClientMessage.Ping)
          case Some(Json.Str("failed")) =>
            map.get("reasons") match {
              case Some(Json.Arr(items)) =>
                // Capped and truncated: this is a diagnostic from untrusted input, not a payload.
                val reasons = items.take(8).collect { case Json.Str(reason) => reason.take(500) }
                Right(ClientMessage.PatchesFailed(reasons))
              case _ => Left("a failed report carries a 'reasons' array")
            }
          case Some(Json.Str(other)) => Left(s"unknown kind '$other'")
          case _                     => Left("a frame carries a string 'kind'")
        }
      case _ => Left("a frame is a JSON object")
    }

  private def event(map: Map[String, Json]): Either[String, ClientMessage] =
    (map.get("name"), map.getOrElse("payload", Json.Obj(Nil))) match {
      case (Some(Json.Str(name)), Json.Obj(payload)) if name.nonEmpty =>
        if (payload.sizeIs > MaxPayloadEntries)
          Left(s"a payload holds at most $MaxPayloadEntries entries")
        else {
          val entries = payload.map {
            case (key, Json.Str(value)) => Right(key -> value)
            case (key, other)           => Left(s"payload entry '$key' is not a string: $other")
          }
          entries.collectFirst { case Left(problem) => problem } match {
            case Some(problem) => Left(problem)
            case None          =>
              Right(ClientMessage.Emit(Event(name, entries.collect { case Right(e) => e }.toMap)))
          }
        }
      case (Some(Json.Str(_)), _) => Left("an event's 'payload' is an object")
      case _                      => Left("an event carries a non-empty string 'name'")
    }

  val pong: String =
    Json.render(Json.Obj(List("kind" -> Json.Str("pong"))))

  def error(message: String): String =
    Json.render(Json.Obj(List("kind" -> Json.Str("error"), "message" -> Json.Str(message))))

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

    case Patch.InsertChild(path, expect, index, node) =>
      obj(
        "insertChild",
        path,
        "expect" -> Json.Str(expect),
        "index"  -> Json.Num(index.toLong),
        "html"   -> Json.Str(node.render)
      )

    case Patch.MoveChild(path, expect, from, to) =>
      obj(
        "moveChild",
        path,
        "expect" -> Json.Str(expect),
        "from"   -> Json.Num(from.toLong),
        "to"     -> Json.Num(to.toLong)
      )

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
