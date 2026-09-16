package io.eezo.http

import io.eezo.core.html.Html

/** The CSRF token: one per session, minted at dispatch the first time a browser is seen, carried by
  * every form as a hidden input, and returned by every unsafe request before its handler runs.
  *
  * The fixtures stand in for the three browsers this is about: `anonymous` has never been seen and
  * carries nothing, `forged` has a session with the token but does not return it, and `request` is
  * a form on a served page, returning the token it was handed.
  */
class CsrfSuite extends munit.FunSuite with ResourceFixtures {

  private val ok: Handler = _ => Response.Ok(Html.text("ok"))

  private def table(routes: Route*): RouteTable = RouteTable(routes)

  private def route(method: Method, path: String, handler: Handler = ok): Route =
    Route.Http(method, PathPattern.parse(path), handler)

  // ---------------------------------------------------------------- the token

  test("a token is random, and two are never the same") {
    val a = Csrf.Token.gen()
    val b = Csrf.Token.gen()
    assertNotEquals(a, b)
    assert(a.value.length >= 43, a.value)
  }

  test("the hidden input carries the token under the reserved field name") {
    assertEquals(
      Csrf.hidden(token).render,
      s"""<input type="hidden" name="_csrf" value="${token.value}">"""
    )
  }

  test("a request built by hand has no token to read, and says so") {
    val failure = intercept[IllegalStateException](anonymous(Method.GET, "/").csrf)
    assert(failure.getMessage.contains("dispatch"), failure.getMessage)
  }

  // ---------------------------------------------------------------- minting at dispatch

  test("dispatch mints a token for a session that has none, and the handler reads it") {
    var seenByHandler: Option[Csrf.Token] = None
    val routes                            = table(
      route(
        Method.GET,
        "/",
        request => { seenByHandler = Some(request.csrf); Response.Ok(Html.text("ok")) }
      )
    )
    val response = routes.dispatch(anonymous(Method.GET, "/"))
    val minted   = seenByHandler.getOrElse(fail("the handler saw no token"))
    // The minted token rides out on the response, so the adapter writes it once.
    assertEquals(response.session.map(Csrf.read), Some(Some(minted)))
  }

  test("a session that already holds a token keeps it") {
    var read   = Option.empty[Csrf.Token]
    val routes = table(
      route(Method.GET, "/", request => { read = Some(request.csrf); Response.Ok(Html.text("ok")) })
    )
    val response = routes.dispatch(forged(Method.GET, "/"))
    assertEquals(read, Some(token))
    assertEquals(response.session.flatMap(Csrf.read), Some(token))
  }

  test("a handler's own session amendment wins, on top of the minted token") {
    val routes = table(
      route(
        Method.GET,
        "/",
        request => Response.Ok(Html.text("ok")).withSession(request.session.set("user", "42"))
      )
    )
    val response = routes.dispatch(anonymous(Method.GET, "/"))
    val session  = response.session.getOrElse(fail("no session on the response"))
    assertEquals(session.get("user"), Some("42"))
    assert(Csrf.read(session).isDefined)
  }

  // ---------------------------------------------------------------- verifying at dispatch

  test("a POST that returns the session's token runs") {
    val response = table(route(Method.POST, "/things")).dispatch(request(Method.POST, "/things"))
    assertEquals(response.status, 200)
  }

  test("a POST with no token is Forbidden, and the handler never runs") {
    var ran    = false
    val routes =
      table(route(Method.POST, "/things", _ => { ran = true; Response.Ok(Html.text("ok")) }))
    val failure = intercept[Forbidden](routes.dispatch(forged(Method.POST, "/things", "a" -> "b")))
    assert(failure.getMessage.contains("missing or stale"), failure.getMessage)
    assert(!ran)
  }

  test("a POST with a token that is not the session's is Forbidden") {
    val other = Csrf.Token.gen()
    intercept[Forbidden](
      table(route(Method.POST, "/things")).dispatch(
        forged(Method.POST, "/things", Csrf.Field -> other.value)
      )
    )
  }

  test("a POST from a browser never seen before is Forbidden rather than minted through") {
    intercept[Forbidden](
      table(route(Method.POST, "/things")).dispatch(
        anonymous(Method.POST, "/things", Csrf.Field -> Csrf.Token.gen().value)
      )
    )
  }

  test("PUT, PATCH and DELETE verify too, and GET never does") {
    val routes = table(
      route(Method.GET, "/things"),
      route(Method.PUT, "/things"),
      route(Method.PATCH, "/things"),
      route(Method.DELETE, "/things")
    )
    Seq(Method.PUT, Method.PATCH, Method.DELETE).foreach { method =>
      intercept[Forbidden](routes.dispatch(forged(method, "/things", "a" -> "b")))
      assertEquals(routes.dispatch(request(method, "/things")).status, 200)
    }
    assertEquals(routes.dispatch(anonymous(Method.GET, "/things")).status, 200)
  }

  test("the check runs after the route match, so a 404 and a 405 stay what they are") {
    val routes = table(route(Method.GET, "/things"))
    intercept[NotFound](routes.dispatch(anonymous(Method.POST, "/nowhere", "a" -> "b")))
    intercept[MethodNotAllowed](routes.dispatch(anonymous(Method.POST, "/things", "a" -> "b")))
  }

  // ---------------------------------------------------------------- the boundary

  test("Forbidden is a 403 at the boundary") {
    val resolution = Boundary.resolve(Forbidden("no"), "/things", Config(RouteTable.empty))
    assertEquals(resolution.problem.status, 403)
    assertEquals(resolution.problem.title, "Forbidden")
    assertEquals(resolution.problem.detail, "no")
  }
}
