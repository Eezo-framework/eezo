package io.eezo.http.cli

import io.eezo.http.{Handler, Method, PathPattern, Provenance, Response, Route, RouteTable}

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
}
