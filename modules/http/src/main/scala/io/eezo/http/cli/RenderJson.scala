package io.eezo.http.cli

import io.eezo.core.internal.Json
import io.eezo.http.Route

/** The machine front-end: the same result values [[Render]] turns into text, as JSON.
  *
  * This is `design/objective.md`'s "every step emits machine-readable output" landing — an agent
  * drives the loop on `--json` and never parses prose. The encoding is deliberately hand-rolled
  * over `Json` rather than derived: these shapes are a public contract for tools, and a contract
  * should not silently change because a field was renamed in a Scala case class. Each object
  * carries a `"command"` discriminator so a stream of results needs no out-of-band context.
  */
object RenderJson {

  private def route(r: Route): Json = {
    val (method, path) = r match {
      case Route.Http(m, pattern, _, _) => (m.toString, pattern.render)
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
