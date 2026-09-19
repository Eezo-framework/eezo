package io.eezo.http

/** The boundary: every failure becomes a `Problem`, and every `Problem` becomes one page. */
class BoundarySuite extends munit.FunSuite {

  /** An application's own exception: outside eezo's sealed set, and wanting a status of its own. */
  private final class PostLocked extends RuntimeException("post is locked")

  private def problem(
      failure: Throwable,
      dev: Boolean = false,
      problems: PartialFunction[Throwable, Problem] = PartialFunction.empty
  ): Problem = {
    val config = Config(RouteTable.empty, dev = dev, problems = problems)
    Boundary.resolve(failure, "/widgets/7", config).problem
  }

  test("each member of the sealed set maps to its status") {
    assertEquals(problem(BadRequest("bad")).status, 400)
    assertEquals(problem(Forbidden("nope")).status, 403)
    assertEquals(problem(NotFound("/x")).status, 404)
    assertEquals(problem(MethodNotAllowed(Seq(Method.GET))).status, 405)
    assertEquals(problem(PayloadTooLarge(1024)).status, 413)
    assertEquals(problem(NotImplemented("BREW")).status, 501)
    assertEquals(problem(InternalServerError(new RuntimeException("boom"))).status, 500)
  }

  test("a problem carries RFC 9457's data model, with about:blank and the status phrase") {
    val result = problem(NotFound("/widgets/7"))
    assertEquals(result.tpe, "about:blank")
    assertEquals(result.title, "Not Found")
    assertEquals(result.instance, "/widgets/7")
  }

  test("a 4xx detail is the exception's message, in both modes") {
    assertEquals(problem(BadRequest("id is not a number")).detail, "id is not a number")
    assertEquals(problem(BadRequest("id is not a number"), dev = true).detail, "id is not a number")
  }

  test("a 500 detail is redacted in production and the cause's message in development") {
    val boom = new RuntimeException("connection refused")
    assertEquals(problem(boom, dev = true).detail, "connection refused")
    assertNoDiff(problem(boom, dev = false).detail, "The server encountered an unexpected error.")
  }

  test("an exception outside the set wraps into a 500") {
    assertEquals(problem(new IllegalStateException("nope")).status, 500)
  }

  test("the problems hook is tried after eezo's set and before the 500 fallback") {
    val hook: PartialFunction[Throwable, Problem] = { case _: PostLocked =>
      Problem(status = 409, detail = "post is locked", instance = "/widgets/7")
    }
    assertEquals(problem(new PostLocked, problems = hook).status, 409)
    assertEquals(problem(new PostLocked, problems = hook).title, "Conflict")
    // eezo's own set still wins over a hook that would also match it
    val greedy: PartialFunction[Throwable, Problem] = { case _ =>
      Problem(status = 409, detail = "hijacked", instance = "/widgets/7")
    }
    assertEquals(problem(NotFound("/x"), problems = greedy).status, 404)
  }

  test("the error response is an HTML page carrying the status and the detail") {
    val response =
      Boundary.errorResponse(NotFound("/widgets/7"), "/widgets/7", Config(RouteTable.empty))
    assertEquals(response.status, 404)
    assertEquals(response.headers, Seq("Content-Type" -> "text/html; charset=utf-8"))
    val page = response.body match {
      case Body.Html(html) => html.render
      case other           => fail(s"expected an HTML body, got $other")
    }
    assert(clue(page).contains("404"))
    assert(clue(page).contains("Not Found"))
  }

  test("a 405 response carries the Allow header, built from the methods that did match") {
    val response =
      Boundary.errorResponse(
        MethodNotAllowed(Seq(Method.GET, Method.PUT)),
        "/widgets/7",
        Config(RouteTable.empty)
      )
    assertEquals(response.status, 405)
    assertEquals(response.headers.toMap.get("Allow"), Some("GET, PUT"))
  }

  test("a stack trace is logged for 500 and above, and never for a client mistake") {
    assert(!Boundary.logsStackTrace(404))
    assert(!Boundary.logsStackTrace(409))
    assert(Boundary.logsStackTrace(500))
    assert(Boundary.logsStackTrace(503))
  }
}
