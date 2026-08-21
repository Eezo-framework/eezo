package eezo.http

import eezo.core.html.Html

/** One table, two route kinds, and one dispatch pass that can populate `Allow`. */
class RouteTableSuite extends munit.FunSuite {

  private val ok: Handler = _ => Response.Ok(Html.text("ok"))

  private def get(pattern: String, handler: Handler = ok): Route =
    Route.Http(Method.GET, PathPattern.parse(pattern), handler)

  private def request(method: Method, path: String): Request =
    Request(method, path, Map.empty, Map.empty, Array.emptyByteArray, Map.empty)

  test("the table partitions into HTTP and WebSocket routes at construction") {
    val ws    = Route.Ws(PathPattern.parse("/live"), _ => new WsListener {})
    val table = RouteTable(Seq(get("/"), ws))
    assertEquals(table.httpRoutes.size, 1)
    assertEquals(table.wsRoutes.size, 1)
  }

  test("dispatch runs the first route whose method and pattern both match") {
    val table = RouteTable(
      Seq(
        get("/widgets/new", _ => Response.Ok(Html.text("form"))),
        get("/widgets/:id", _ => Response.Ok(Html.text("show")))
      )
    )
    assertEquals(
      table.dispatch(request(Method.GET, "/widgets/new")).body,
      Body.Html(Html.text("form"))
    )
    assertEquals(
      table.dispatch(request(Method.GET, "/widgets/7")).body,
      Body.Html(Html.text("show"))
    )
  }

  test("dispatch fills in the path parameters the pattern captured") {
    val table =
      RouteTable(Seq(get("/widgets/:id", req => Response.Ok(Html.text(req.param[String]("id"))))))
    assertEquals(table.dispatch(request(Method.GET, "/widgets/7")).body, Body.Html(Html.text("7")))
  }

  test("a path no route matches throws NotFound, naming the path") {
    val table   = RouteTable(Seq(get("/widgets")))
    val failure = intercept[NotFound](table.dispatch(request(Method.GET, "/nope")))
    assertEquals(failure.path, "/nope")
  }

  test(
    "a path that matches with the wrong method throws MethodNotAllowed with every method that does"
  ) {
    val table = RouteTable(
      Seq(
        get("/widgets/:id"),
        Route.Http(Method.PUT, PathPattern.parse("/widgets/:id"), ok),
        Route.Http(Method.DELETE, PathPattern.parse("/widgets/:id"), ok)
      )
    )
    val failure = intercept[MethodNotAllowed](table.dispatch(request(Method.POST, "/widgets/7")))
    assertEquals(failure.allowed.toSet, Set(Method.GET, Method.PUT, Method.DELETE))
  }

  test("a WebSocket route never answers an HTTP request, whatever its path") {
    val table = RouteTable(Seq(Route.Ws(PathPattern.parse("/live"), _ => new WsListener {})))
    intercept[NotFound](table.dispatch(request(Method.GET, "/live")))
  }

  test("the same method and pattern twice fails the boot rather than the request") {
    val failure =
      intercept[IllegalArgumentException](RouteTable(Seq(get("/widgets"), get("/widgets/"))))
    assert(clue(failure.getMessage).contains("/widgets"))
  }

  test("++ concatenates, and the earlier route shadows the later one") {
    val first  = RouteTable(Seq(get("/a/:id", _ => Response.Ok(Html.text("param")))))
    val second = RouteTable(Seq(get("/a/new", _ => Response.Ok(Html.text("literal")))))
    val joined = first ++ second
    assertEquals(joined.httpRoutes.size, 2)
    // Shadowing is never a runtime error: first match in Seq order is exactly the mechanism that
    // makes a handwritten route beat a derived one.
    assertEquals(joined.dispatch(request(Method.GET, "/a/new")).body, Body.Html(Html.text("param")))
  }
}
