package io.eezo.http

import io.eezo.core.html.Html

/** One table, two route kinds, and one dispatch pass that can populate `Allow`. */
class RouteTableSuite extends munit.FunSuite {

  private val ok: Handler = _ => Response.Ok(Html.text("ok"))

  private def get(pattern: String, handler: Handler = ok): Route =
    Route.Http(Method.GET, PathPattern.parse(pattern), handler)

  /** What `Resource` mounts, spelled out here so the precedence tests do not need a model. */
  private def derived(pattern: String, handler: Handler = ok): Route =
    Route.Http(Method.GET, PathPattern.parse(pattern), handler, Provenance.Derived)

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

  test("the same method and pattern twice, both written by hand, fails the boot") {
    val failure =
      intercept[IllegalArgumentException](RouteTable(Seq(get("/widgets"), get("/widgets/"))))
    assert(clue(failure.getMessage).contains("/widgets"))
  }

  test("the same method and pattern twice, both derived, fails the boot too") {
    val failure =
      intercept[IllegalArgumentException](RouteTable(Seq(derived("/posts"), derived("/posts/"))))
    assert(clue(failure.getMessage).contains("/posts"))
  }

  // ----------------------------------------------- handwritten beats derived (decision 18)

  test(
    "a handwritten route and a derived one on the same method and path both mount, and the " +
      "handwritten one answers"
  ) {
    val table = RouteTable(
      Seq(
        get("/posts", _ => Response.Ok(Html.text("by hand"))),
        derived("/posts", _ => Response.Ok(Html.text("derived")))
      )
    )
    assertEquals(
      table.dispatch(request(Method.GET, "/posts")).body,
      Body.Html(Html.text("by hand"))
    )
    // Dropped, not merely outrun: a route the table still holds would show up in the boot listing
    // and read as a page the application serves.
    assertEquals(table.routes.size, 1)
    assertEquals(table.httpRoutes.size, 1)
  }

  test("the handwritten route wins wherever in the table it sits") {
    val table = RouteTable(
      Seq(
        derived("/posts", _ => Response.Ok(Html.text("derived"))),
        get("/posts", _ => Response.Ok(Html.text("by hand")))
      )
    )
    assertEquals(table.routes.size, 1)
    assertEquals(
      table.dispatch(request(Method.GET, "/posts")).body,
      Body.Html(Html.text("by hand"))
    )
  }

  test("overridden names the derived route that was dropped, which is what boot warns about") {
    val loser = derived("/posts")
    val table = RouteTable(Seq(get("/posts"), loser))
    assertEquals(table.overridden, Seq(loser))
    assertEquals(RouteTable(Seq(get("/posts"), derived("/posts/:id"))).overridden, Seq.empty)
  }

  test("only the colliding route of a derived resource is dropped, the rest still mount") {
    val table = RouteTable(
      Seq(get("/posts"), derived("/posts"), derived("/posts/new"), derived("/posts/:id"))
    )
    assertEquals(
      table.routes.map(_.describe),
      Seq("GET /posts", "GET /posts/new", "GET /posts/:id")
    )
  }

  test("a derived route only loses to a handwritten route on the very same method") {
    val table = RouteTable(
      Seq(Route.Http(Method.POST, PathPattern.parse("/posts"), ok), derived("/posts"))
    )
    assertEquals(table.routes.size, 2)
    assertEquals(table.overridden, Seq.empty)
  }

  test("++ resolves the precedence across the join, whichever side derived the route") {
    val handwritten = RouteTable(Seq(get("/posts", _ => Response.Ok(Html.text("by hand")))))
    val resource    = RouteTable(Seq(derived("/posts", _ => Response.Ok(Html.text("derived")))))
    assertEquals((handwritten ++ resource).routes.size, 1)
    assertEquals((resource ++ handwritten).routes.size, 1)
    assertEquals(
      (resource ++ handwritten).dispatch(request(Method.GET, "/posts")).body,
      Body.Html(Html.text("by hand"))
    )
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

  test("shadowed names every pair where an earlier route swallows a later one") {
    val broad  = get("/widgets/:id")
    val narrow = get("/widgets/new")
    val table  = RouteTable(Seq(broad, narrow))
    assertEquals(table.shadowed, Seq((broad, narrow)))
  }

  test("declaration order decides: the narrow route first shadows nothing") {
    val table = RouteTable(Seq(get("/widgets/new"), get("/widgets/:id")))
    assertEquals(table.shadowed, Seq.empty)
  }

  test("routes on different methods never shadow, however broad the earlier pattern") {
    val table = RouteTable(
      Seq(get("/widgets/:id"), Route.Http(Method.POST, PathPattern.parse("/widgets/new"), ok))
    )
    assertEquals(table.shadowed, Seq.empty)
  }

  test("one catch-all shadows every later route on its method, and is reported once per victim") {
    val all   = get("/*rest")
    val one   = get("/widgets")
    val two   = get("/widgets/:id")
    val table = RouteTable(Seq(all, one, two))
    assertEquals(table.shadowed, Seq((all, one), (all, two)))
  }

  test("a WebSocket route shadows a later WebSocket route, and no HTTP route ever shadows one") {
    val broad  = Route.Ws(PathPattern.parse("/live/:room"), _ => new WsListener {})
    val narrow = Route.Ws(PathPattern.parse("/live/lobby"), _ => new WsListener {})
    assertEquals(RouteTable(Seq(broad, narrow)).shadowed, Seq((broad, narrow)))
    assertEquals(RouteTable(Seq(get("/live/:room"), narrow)).shadowed, Seq.empty)
  }

  test("describe names a route the way the boot print and the shadow warning both need") {
    assertEquals(get("/widgets/:id").describe, "GET /widgets/:id")
    assertEquals(Route.Ws(PathPattern.parse("/live"), _ => new WsListener {}).describe, "WS /live")
  }
  // ---------------------------------------------------------------- under

  test("under prefixes every route it is given, derived or handwritten") {
    val moved = Route.under("/admin")(Seq(get("/widgets"), get("/widgets/:id")))
    assertEquals(moved.map(_.describe), Seq("GET /admin/widgets", "GET /admin/widgets/:id"))
  }

  test("under prefixes a WebSocket route too, since it is a transformation over Route") {
    val moved =
      Route.under("/admin")(Seq(Route.Ws(PathPattern.parse("/live"), _ => new WsListener {})))
    assertEquals(moved.map(_.describe), Seq("WS /admin/live"))
  }

  test("under keeps the handler, so a moved route still runs") {
    val moved = Route.under("/admin")(Seq(get("/widgets", _ => Response.Ok(Html.text("list")))))
    assertEquals(
      RouteTable(moved).dispatch(request(Method.GET, "/admin/widgets")).body,
      Body.Html(Html.text("list"))
    )
  }

  test("under keeps the provenance, so a moved derived route still loses to a handwritten one") {
    val moved = Route.under("/admin")(Seq(derived("/posts")))
    val table = RouteTable(get("/admin/posts") +: moved)
    assertEquals(table.routes.size, 1)
    assertEquals(table.overridden.map(_.describe), Seq("GET /admin/posts"))
  }

  test("a prefix written without its slash, or with a trailing one, mounts the same paths") {
    val routes = Seq(get("/widgets"))
    assertEquals(Route.under("admin")(routes).map(_.describe), Seq("GET /admin/widgets"))
    assertEquals(Route.under("/admin/")(routes).map(_.describe), Seq("GET /admin/widgets"))
  }
}
