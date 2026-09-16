package io.eezo.http

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import io.eezo.core.html.Html

/** The CSRF token: one per session, minted at dispatch the first time a browser is seen, carried by
  * every form as a hidden input, and returned by every unsafe request before its handler runs.
  */
class CsrfSuite extends munit.FunSuite {

  private val ok: Handler = _ => Response.Ok(Html.text("ok"))

  private def table(routes: Route*): RouteTable = RouteTable(routes)

  private def post(path: String, handler: Handler = ok): Route =
    Route.Http(Method.POST, PathPattern.parse(path), handler)

  /** A request from a browser never seen before: no session, and a form encoded body when there are
    * fields. Built here rather than through `ResourceFixtures`, whose requests carry a token the
    * way a seen browser's do, because this suite is about what happens before and without one.
    */
  private def request(method: Method, path: String, form: (String, String)*): Request = {
    val body = form
      .map { case (k, v) =>
        s"${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
      }
      .mkString("&")
    Request(
      method = method,
      path = path,
      query = Map.empty,
      headers =
        if (form.isEmpty) Map.empty
        else Map("Content-Type" -> Seq("application/x-www-form-urlencoded")),
      body = body.getBytes(StandardCharsets.UTF_8),
      pathParams = Map.empty
    )
  }

  /** A request whose session already holds `token`, the way a browser that has been seen once sends
    * it back.
    */
  private def seen(request: Request, token: Csrf.Token): Request =
    request.copy(session = Csrf.carrying(request.session, token))

  // ---------------------------------------------------------------- the token

  test("a token is random, and two are never the same") {
    val a = Csrf.Token.gen()
    val b = Csrf.Token.gen()
    assertNotEquals(a, b)
    assert(a.value.length >= 43, a.value)
  }

  test("the hidden input carries the token under the reserved field name") {
    val token = Csrf.Token.gen()
    assertEquals(
      Csrf.hidden(token).render,
      s"""<input type="hidden" name="_csrf" value="${token.value}">"""
    )
  }

  test("a request built by hand has no token to read, and says so") {
    val failure = intercept[IllegalStateException](request(Method.GET, "/").csrf)
    assert(failure.getMessage.contains("dispatch"), failure.getMessage)
  }

  // ---------------------------------------------------------------- minting at dispatch

  test("dispatch mints a token for a session that has none, and the handler reads it") {
    var seenByHandler: Option[Csrf.Token] = None
    val routes                            = table(
      Route.Http(
        Method.GET,
        PathPattern.parse("/"),
        request => { seenByHandler = Some(request.csrf); Response.Ok(Html.text("ok")) }
      )
    )
    val response = routes.dispatch(request(Method.GET, "/"))
    val token    = seenByHandler.getOrElse(fail("the handler saw no token"))
    // The minted token rides out on the response, so the adapter writes it once.
    assertEquals(response.session.map(Csrf.read), Some(Some(token)))
  }

  test("a session that already holds a token keeps it") {
    val token  = Csrf.Token.gen()
    var read   = Option.empty[Csrf.Token]
    val routes = table(
      Route.Http(
        Method.GET,
        PathPattern.parse("/"),
        request => { read = Some(request.csrf); Response.Ok(Html.text("ok")) }
      )
    )
    val response = routes.dispatch(seen(request(Method.GET, "/"), token))
    assertEquals(read, Some(token))
    assertEquals(response.session.flatMap(Csrf.read), Some(token))
  }

  test("a handler's own session amendment wins, on top of the minted token") {
    val routes = table(
      Route.Http(
        Method.GET,
        PathPattern.parse("/"),
        request => Response.Ok(Html.text("ok")).withSession(request.session.set("user", "42"))
      )
    )
    val response = routes.dispatch(request(Method.GET, "/"))
    val session  = response.session.getOrElse(fail("no session on the response"))
    assertEquals(session.get("user"), Some("42"))
    assert(Csrf.read(session).isDefined)
  }

  // ---------------------------------------------------------------- verifying at dispatch

  test("a POST that returns the session's token runs") {
    val token    = Csrf.Token.gen()
    val response =
      table(post("/things")).dispatch(
        seen(request(Method.POST, "/things", "_csrf" -> token.value), token)
      )
    assertEquals(response.status, 200)
  }

  test("a POST with no token is Forbidden, and the handler never runs") {
    var ran     = false
    val routes  = table(post("/things", _ => { ran = true; Response.Ok(Html.text("ok")) }))
    val failure = intercept[Forbidden](routes.dispatch(request(Method.POST, "/things", "a" -> "b")))
    assert(failure.getMessage.contains("missing or stale"), failure.getMessage)
    assert(!ran)
  }

  test("a POST with a token that is not the session's is Forbidden") {
    val token = Csrf.Token.gen()
    val other = Csrf.Token.gen()
    intercept[Forbidden](
      table(post("/things")).dispatch(
        seen(request(Method.POST, "/things", "_csrf" -> other.value), token)
      )
    )
  }

  test("a POST from a browser never seen before is Forbidden rather than minted through") {
    intercept[Forbidden](
      table(post("/things")).dispatch(
        request(Method.POST, "/things", "_csrf" -> Csrf.Token.gen().value)
      )
    )
  }

  test("PUT, PATCH and DELETE verify too, and GET never does") {
    val token                 = Csrf.Token.gen()
    def route(method: Method) = Route.Http(method, PathPattern.parse("/things"), ok)
    val routes                =
      table(route(Method.GET), route(Method.PUT), route(Method.PATCH), route(Method.DELETE))
    Seq(Method.PUT, Method.PATCH, Method.DELETE).foreach { method =>
      intercept[Forbidden](routes.dispatch(seen(request(method, "/things", "a" -> "b"), token)))
      assertEquals(
        routes.dispatch(seen(request(method, "/things", "_csrf" -> token.value), token)).status,
        200
      )
    }
    assertEquals(routes.dispatch(request(Method.GET, "/things")).status, 200)
  }

  test("the check runs after the route match, so a 404 and a 405 stay what they are") {
    val routes = table(Route.Http(Method.GET, PathPattern.parse("/things"), ok))
    intercept[NotFound](routes.dispatch(request(Method.POST, "/nowhere", "a" -> "b")))
    intercept[MethodNotAllowed](routes.dispatch(request(Method.POST, "/things", "a" -> "b")))
  }

  // ---------------------------------------------------------------- the boundary

  test("Forbidden is a 403 at the boundary") {
    val resolution = Boundary.resolve(Forbidden("no"), "/things", Config(RouteTable.empty))
    assertEquals(resolution.problem.status, 403)
    assertEquals(resolution.problem.title, "Forbidden")
    assertEquals(resolution.problem.detail, "no")
  }
}
