package io.eezo.http.cli

import io.eezo.core.internal.Json
import io.eezo.http.Route

/** The machine side of this edge's CLI: the same result values [[Render]] turns into text, as JSON.
  * The format rules, and why the encoding is written by hand over `Json` rather than derived, are
  * explained once on the db edge's `RenderJson` (`io.eezo.db.cli.RenderJson`) and hold here
  * unchanged: a `"command"` discriminator on every object, and shapes that are a contract for
  * tools.
  */
object RenderJson {

  private def route(r: Route): Json = {
    val (method, path) = r match {
      case Route.Http(m, pattern, _, _, _) => (m.toString, pattern.render)
      case Route.Ws(pattern, _, _)      => ("WS", pattern.render)
    }
    Json.Obj(
      List(
        "method"     -> Json.Str(method),
        "path"       -> Json.Str(path),
        "provenance" -> Json.Str(r.provenance.toString.toLowerCase)
      )
    )
  }

  def routes(r: RouteListing): String = Json.render(
    Json.Obj(
      List(
        "command"    -> Json.Str("routes"),
        "routes"     -> Json.Arr(r.routes.toList.map(route)),
        "overridden" -> Json.Arr(r.overridden.toList.map(route)),
        "shadowed"   -> Json.Arr(
          r.shadowed.toList.map { case (earlier, later) =>
            Json.Obj(List("earlier" -> route(earlier), "later" -> route(later)))
          }
        ),
        "orphans" -> Json.Arr(
          r.orphans.toList.map(o =>
            Json.Obj(
              List(
                "page"        -> Json.Str(o.page.toString),
                "pageRoute"   -> Json.Str(o.pageRoute),
                "target"      -> Json.Str(o.target.toString),
                "targetRoute" -> Json.Str(o.targetRoute)
              )
            )
          )
        )
      )
    )
  )
}
