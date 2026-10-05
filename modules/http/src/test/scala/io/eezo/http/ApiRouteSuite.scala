package io.eezo.http

import io.eezo.core.html.Html

/** The API route at dispatch: a route a program calls, in which no session takes part.
  *
  * Every route here is built the way the generated row builds it, through `Route.handwritten`, so
  * the kind comes from the type the handler takes and from nothing else. The browser fixtures are
  * `ResourceFixtures`', the ones `CsrfSuite` stands on, so a browser route beside an API route is
  * checked against the very requests that pin the token.
  */
class ApiRouteSuite extends munit.FunSuite with ResourceFixtures {

  private val ok: Handler = _ => Response.Ok(Html.text("ok"))

  /** A webhook, the shape the shop's Stripe handler has: reads the body, answers a status. */
  private def webhook(request: ApiRequest): Response =
    if (request.body.isEmpty) Response.status(400) else Response.status(200)

  private def page(request: Request): Response = Response.Ok(Html.text(request.path))

  private def kindOf(route: Route): RouteKind = route match {
    case http: Route.Http => http.kind
    case other            => fail(s"not an HTTP route: $other")
  }

  /** A request a program sends: a JSON body, no cookie, no form, so no `_csrf` field either. */
  private def program(method: Method, path: String, body: String = """{"id":"evt_1"}"""): Request =
    Request(
      method = method,
      path = path,
      query = Map.empty,
      headers = Map("Content-Type" -> Seq("application/json")),
      body = body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
      pathParams = Map.empty
    )

  // The kind

  test("a route built without saying so is a browser route") {
    assertEquals(kindOf(Route.Http(Method.GET, PathPattern.parse("/"), ok)), RouteKind.Browser)
  }

  test("the generated row makes a browser route of a handler that takes a Request") {
    val route = Route.handwritten(Method.GET, "/posts/:id", page)
    route match {
      case Route.Http(method, pattern, _, provenance, kind) =>
        assertEquals(method, Method.GET)
        assertEquals(pattern.render, "/posts/:id")
        assertEquals(provenance, Provenance.Handwritten)
        assertEquals(kind, RouteKind.Browser)
      case other => fail(s"not an HTTP route: $other")
    }
  }

  test("the generated row makes an API route of a handler that takes an ApiRequest") {
    val route = Route.handwritten(Method.POST, "/webhooks/stripe", webhook)
    assertEquals(kindOf(route), RouteKind.Api)
    assertEquals(route.provenance, Provenance.Handwritten)
    assertEquals(route.describe, "POST /webhooks/stripe")
  }

  test("the kind survives a guard's wrapper and a mount") {
    val guard = Guarded[ApiRouteSuite](
      Set.empty,
      {
        case http: Route.Http => http.copy(handler = request => http.handler(request))
        case other            => other
      },
      Seq.empty
    )
    val mounted = Route.under("/hooks")(
      guard.mounting(Route.handwritten(Method.POST, "/stripe", webhook))
    )
    assertEquals(mounted.map(_.describe), Seq("POST /hooks/stripe"))
    assertEquals(mounted.map(kindOf), Seq(RouteKind.Api))
  }

  // Dispatch

  test("a POST from a program with no cookie and no _csrf field reaches the handler") {
    val routes   = RouteTable(Seq(Route.handwritten(Method.POST, "/webhooks/stripe", webhook)))
    val response = routes.dispatch(program(Method.POST, "/webhooks/stripe"))
    assertEquals(response.status, 200)
  }

  test("the handler and the guard around it see an empty session, whatever the request carried") {
    var guardSaw: Option[Session] = None
    var handlerSaw                = Option.empty[ApiRequest]
    val guard                     = Guarded[ApiRouteSuite](
      Set.empty,
      {
        case http: Route.Http =>
          http.copy(handler = request => {
            guardSaw = Some(request.session); http.handler(request)
          })
        case other => other
      },
      Seq.empty
    )
    val routes = RouteTable(
      guard.mounting(
        Route.handwritten(
          Method.POST,
          "/webhooks/stripe",
          (request: ApiRequest) => { handlerSaw = Some(request); Response.status(200) }
        )
      )
    )
    // A browser that is signed in, with a flash waiting and a token in its session, calling the URL.
    val signedIn = request(Method.POST, "/webhooks/stripe").copy(
      session = Csrf.carrying(Session.empty.set("user", "42").flash("notice", "hi"), token)
    )
    val response = routes.dispatch(signedIn)
    assertEquals(response.status, 200)
    assertEquals(guardSaw, Some(Session.empty))
    assert(handlerSaw.isDefined, "the handler was never called")
  }

  test("a page copied onto an API route's kind reads an empty session, not the signed in user") {
    // What an application can write with public names only: the kind of an existing API route,
    // copied onto a page whose handler takes a Request and reads the session.
    var pageSaw = Option.empty[Session]
    val api     = kindOf(Route.handwritten(Method.POST, "/webhooks/stripe", webhook))
    val flipped = Route
      .Http(
        Method.POST,
        PathPattern.parse("/account/delete"),
        request => { pageSaw = Some(request.session); Response.status(200) }
      )
      .copy(kind = api)
    val signedIn = request(Method.POST, "/account/delete").copy(
      session = Csrf.carrying(Session.empty.set("user", "42"), token)
    )
    val response = RouteTable(Seq(flipped)).dispatch(signedIn)
    assertEquals(response.status, 200)
    assertEquals(pageSaw, Some(Session.empty))
  }

  test("no CSRF token is minted: the response names no session and the request carried none") {
    var guardSaw = Option.empty[Option[Csrf.Token]]
    val guard    = Guarded[ApiRouteSuite](
      Set.empty,
      {
        case http: Route.Http =>
          http.copy(handler = request => {
            guardSaw = Some(Csrf.read(request.session)); http.handler(request)
          })
        case other => other
      },
      Seq.empty
    )
    val routes =
      RouteTable(
        guard.mounting(
          Route.handwritten(Method.GET, "/status", (_: ApiRequest) => Response.status(204))
        )
      )
    val response = routes.dispatch(program(Method.GET, "/status", body = ""))
    assertEquals(response.session, None)
    assertEquals(guardSaw, Some(None))
  }

  test("a response that names a session on an API route is a defect naming the route") {
    Seq(Session.empty, Session.empty.set("user", "42")).foreach { named =>
      val routes = RouteTable(
        Seq(
          Route.handwritten(
            Method.POST,
            "/webhooks/stripe",
            (_: ApiRequest) => Response.status(200).withSession(named)
          )
        )
      )
      val failure =
        intercept[IllegalStateException](routes.dispatch(program(Method.POST, "/webhooks/stripe")))
      assert(clue(failure.getMessage).contains("POST /webhooks/stripe"), failure.getMessage)
      assert(
        clue(failure.getMessage).contains("no session takes part in an API route"),
        failure.getMessage
      )
    }
  }

  test("a guard that names a session on an API route is a defect that points at the guard too") {
    // The shape of a sign in guard mounted on an API route: nobody is signed in, so it redirects
    // to login and clears the session. The handler named nothing, so the message must not send the
    // author looking for a withSession only the handler could hold.
    var called = false
    val guard  = Guarded[ApiRouteSuite](
      Set.empty,
      {
        case http: Route.Http =>
          http.copy(handler = _ => Response.Redirect("/login").withSession(Session.empty))
        case other => other
      },
      Seq.empty
    )
    val routes = RouteTable(
      guard.mounting(
        Route.handwritten(
          Method.POST,
          "/webhooks/stripe",
          (_: ApiRequest) => { called = true; Response.status(200) }
        )
      )
    )
    val failure =
      intercept[IllegalStateException](routes.dispatch(program(Method.POST, "/webhooks/stripe")))
    assert(!called, "the guard answered, the handler never ran")
    assert(clue(failure.getMessage).contains("POST /webhooks/stripe"), failure.getMessage)
    assert(clue(failure.getMessage).contains("a guard around it"), failure.getMessage)
    assert(
      clue(failure.getMessage).contains("a Guarded that does not read the session"),
      failure.getMessage
    )
  }

  test("a guard that refuses runs after the match and before the handler") {
    var called = false
    val guard  = Guarded[ApiRouteSuite](
      Set.empty,
      {
        case http: Route.Http => http.copy(handler = _ => throw Forbidden("bad signature"))
        case other            => other
      },
      Seq.empty
    )
    val routes = RouteTable(
      guard.mounting(
        Route.handwritten(
          Method.POST,
          "/webhooks/stripe",
          (_: ApiRequest) => { called = true; Response.status(200) }
        )
      )
    )
    intercept[Forbidden](routes.dispatch(program(Method.POST, "/webhooks/stripe")))
    assert(!called, "the handler ran behind a guard that refused")
    // Matching comes first: a path the table does not have is a 404, a verb it does not take a 405.
    intercept[NotFound](routes.dispatch(program(Method.POST, "/webhooks/paypal")))
    val wrongVerb =
      intercept[MethodNotAllowed](routes.dispatch(program(Method.GET, "/webhooks/stripe")))
    assertEquals(wrongVerb.allowed, Seq(Method.POST))
    assert(!called)
  }

  test("a browser route beside an API route still mints on GET and refuses a stranger's POST") {
    val routes = RouteTable(
      Seq(
        Route.handwritten(Method.POST, "/webhooks/stripe", webhook),
        Route.handwritten(Method.GET, "/", page),
        Route.handwritten(Method.POST, "/", page)
      )
    )
    val minted = routes.dispatch(anonymous(Method.GET, "/"))
    assert(minted.session.flatMap(Csrf.read).isDefined, "no token minted on the browser route")
    intercept[Forbidden](routes.dispatch(anonymous(Method.POST, "/", "title" -> "x")))
    assertEquals(routes.dispatch(program(Method.POST, "/webhooks/stripe")).status, 200)
  }

  // The table

  test("a handwritten API route still overrides a derived route on the same method and path") {
    val derived = Route.derived(Method.POST, "/posts", ok)
    val table   = RouteTable(Seq(Route.handwritten(Method.POST, "/posts", webhook), derived))
    assertEquals(table.overridden, Seq(derived))
    assertEquals(table.routes.map(kindOf), Seq(RouteKind.Api))
    assertEquals(RouteReport.warnings(table).size, 1)
  }

  test("an API route and a browser route on the same method and path are still a duplicate") {
    val failure = intercept[IllegalArgumentException](
      RouteTable(
        Seq(
          Route.handwritten(Method.POST, "/posts", webhook),
          Route.handwritten(Method.POST, "/posts", page)
        )
      )
    )
    assert(clue(failure.getMessage).contains("duplicate route: POST /posts"))
  }

  test("shadowing and orphans are still reported with API routes in the table") {
    val table = RouteTable(
      Seq(
        Route.handwritten(Method.GET, "/feed/:id", (_: ApiRequest) => Response.status(200)),
        Route.handwritten(Method.GET, "/feed/latest", (_: ApiRequest) => Response.status(200)),
        Route.derived(Method.GET, "/widgets/new", ok)
      )
    )
    assertEquals(
      table.shadowed.map { case (earlier, later) => (earlier.describe, later.describe) },
      Seq("GET /feed/:id" -> "GET /feed/latest")
    )
    assertEquals(Resource.orphaned(table).map(_.pageRoute), Seq("GET /widgets/new"))
  }

  // A form page cannot be one

  test("a New or Edit file whose handler takes an ApiRequest does not compile, naming the file") {
    val failures = Seq(
      compileErrors(
        """Route.page(Method.GET, "/hooks/new", ApiPages.`new`, "src/main/scala/app/hooks/New.scala")"""
      ) -> "src/main/scala/app/hooks/New.scala",
      compileErrors(
        """Route.page(Method.GET, "/hooks/:id/edit", ApiPages.edit, "src/main/scala/app/hooks/Edit.scala")"""
      ) -> "src/main/scala/app/hooks/Edit.scala"
    )
    failures.foreach { case (failure, source) =>
      assert(clue(failure).contains(source), failure)
      assert(clue(failure).contains("take a Request there"), failure)
      assert(clue(failure).contains("rename the file"), failure)
      assert(!clue(failure).contains("literal string is expected"), failure)
    }
  }

  test("a New or Edit file whose handler takes a Request is the browser route it always was") {
    val route =
      Route.page(Method.GET, "/hooks/new", BrowserPages.`new`, "src/main/scala/app/hooks/New.scala")
    assertEquals(route.describe, "GET /hooks/new")
    assertEquals(route.provenance, Provenance.Handwritten)
    assertEquals(kindOf(route), RouteKind.Browser)
    // Still behind the token: both form pages mint one for a browser seen for the first time.
    val form = Route.page(
      Method.GET,
      "/hooks/:id/edit",
      BrowserPages.edit,
      "src/main/scala/app/hooks/Edit.scala"
    )
    val table = RouteTable(Seq(route, form))
    Seq("/hooks/new", "/hooks/7/edit").foreach { path =>
      assert(table.dispatch(anonymous(Method.GET, path)).session.flatMap(Csrf.read).isDefined, path)
    }
  }
  // A handler with a using clause

  test("a handler that takes its capability in a using clause is routed by every row") {
    given Clock = Clock(7)
    val table   = RouteTable(
      Seq(
        Route.handwritten(Method.GET, "/clock", CapabilityPages.index),
        Route.handwritten(Method.POST, "/webhooks/clock", CapabilityPages.hook),
        Route.page(
          Method.GET,
          "/clock/new",
          CapabilityPages.index,
          "src/main/scala/app/clock/New.scala"
        )
      )
    )
    assertEquals(
      table.routes.map(route => route.describe -> kindOf(route)),
      Seq(
        "GET /clock"           -> RouteKind.Browser,
        "POST /webhooks/clock" -> RouteKind.Api,
        "GET /clock/new"       -> RouteKind.Browser
      )
    )
    assertEquals(table.dispatch(program(Method.POST, "/webhooks/clock")).status, 207)
    assertEquals(table.dispatch(anonymous(Method.GET, "/clock/new")).status, 200)
  }
}

/** Handlers shaped the way a file under `app/` writes them, for the form page rows above. */
object ApiPages {
  def `new`(request: ApiRequest): Response = Response.status(if (request.body.isEmpty) 204 else 200)
  def edit(request: ApiRequest): Response  = Response.status(if (request.body.isEmpty) 204 else 200)
}

object BrowserPages {
  def `new`(request: Request): Response = Response.Ok(Html.text(request.path))
  def edit(request: Request): Response  = Response.Ok(Html.text(request.path))
}

/** A capability a handler asks for in its `using` list, the way `Request` recommends. */
final class Clock(val now: Int)

/** Handlers that take their capability in a `using` clause, so a change to the row's overloads that
  * stops resolving the clause fails this build rather than an application's.
  */
object CapabilityPages {
  def index(request: Request)(using clock: Clock): Response =
    Response.Ok(Html.text(s"${request.path} ${clock.now}"))
  def hook(request: ApiRequest)(using clock: Clock): Response =
    Response.status(if (request.body.isEmpty) 400 else 200 + clock.now)
}
