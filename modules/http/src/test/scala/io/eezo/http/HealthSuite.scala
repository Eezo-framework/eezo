package io.eezo.http

/** The framework health endpoint: always mounted, unshadowable, and cheap. */
class HealthSuite extends munit.FunSuite with ServerFixtures {

  test("GET /eezo/health answers 200 on a server with no routes at all") {
    serving(RouteTable.empty) { (_, port) =>
      val response = get(port, HttpServer.HealthPath)
      assertEquals(response.statusCode(), 200)
      assertEquals(response.body(), "ok")
      // and nothing else under the reserved prefix leaks a page
      assertEquals(get(port, "/eezo/nothing").statusCode(), 404)
    }
  }

  test("a user catch-all cannot shadow it") {
    val table = RouteTable(
      Seq(Route.Http(Method.GET, PathPattern.parse("/*rest"), _ => Response.status(418)))
    )
    serving(table) { (_, port) =>
      assertEquals(get(port, HttpServer.HealthPath).statusCode(), 200)
      assertEquals(get(port, "/anything-else").statusCode(), 418)
    }
  }
}
