package io.eezo.http.cli

import io.eezo.core.internal.Json
import io.eezo.http.Route

/** The machine side of this edge's CLI: the same result values [[Render]] turns into text, as JSON.
  * The format rules, and why the encoding is written by hand over `Json` rather than derived, are
  * explained once on the db edge's `RenderJson` (`io.eezo.db.cli.RenderJson`) and hold here
  * unchanged: a `"command"` discriminator on every object, and shapes that are a contract for
  * tools.
  */
private[http] object RenderJson {

  /** `api` is on every route, `false` included, so a tool reads one field rather than inferring a
    * browser route from a missing key. A boolean rather than a kind string, beside how the db edge
    * renders its yes or no properties, and because a WebSocket upgrade has no kind of its own.
    */
  private def route(r: Route): Json = {
    val (method, path, api) = r match {
      case Route.Http(m, pattern, _, _, kind) => (m.toString, pattern.render, kind.api)
      case Route.Ws(pattern, _, _)            => ("WS", pattern.render, false)
    }
    Json.Obj(
      List(
        "method"     -> Json.Str(method),
        "path"       -> Json.Str(path),
        "provenance" -> Json.Str(r.provenance.toString.toLowerCase),
        "api"        -> Json.Bool(api)
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
