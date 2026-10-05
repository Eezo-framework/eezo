package io.eezo.http.cli

import io.eezo.http.{
  ApiRequest,
  Handler,
  Method,
  PathPattern,
  Provenance,
  Response,
  Route,
  RouteTable
}

import munit.FunSuite

/** The JSON shape of `routes` is a contract for tools, so it is pinned here: a field rename or a
  * shape change must fail here, not in someone's agent.
  */
class RenderJsonSuite extends FunSuite {

  test("routes renders method, path and provenance per route") {
    val ok: Handler = _ => Response.status(200)
    val table       = RouteTable(
      Seq(
        Route.Http(Method.GET, PathPattern.parse("/todos/:id"), ok),
        Route.Http(Method.POST, PathPattern.parse("/todos"), ok, Provenance.Derived)
      )
    )
    val json = RenderJson.routes(Commands.routes(table))
    assert(json.contains("\"command\": \"routes\""))
    assert(json.contains("\"method\": \"GET\""))
    assert(json.contains("\"path\": \"/todos/:id\""))
    assert(json.contains("\"provenance\": \"derived\""))
    assert(json.contains("\"provenance\": \"handwritten\""))
  }

  test("routes marks an API route, and says a browser route is not one") {
    val ok: Handler = _ => Response.status(200)
    val table       = RouteTable(
      Seq(
        Route.handwritten(Method.POST, "/webhooks/stripe", (_: ApiRequest) => Response.status(200)),
        Route.Http(Method.GET, PathPattern.parse("/"), ok)
      )
    )
    // Whitespace dropped, so the pin is on each route's fields and values, not on the indentation.
    val json = RenderJson.routes(Commands.routes(table)).replaceAll("\\s", "")
    assert(
      clue(json).contains(
        "{\"method\":\"POST\",\"path\":\"/webhooks/stripe\",\"provenance\":\"handwritten\",\"api\":true}"
      )
    )
    assert(
      clue(json).contains(
        "{\"method\":\"GET\",\"path\":\"/\",\"provenance\":\"handwritten\",\"api\":false}"
      )
    )
  }
}
