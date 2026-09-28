package io.eezo.http

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/** The framework health endpoint: always mounted, unshadowable, and cheap. */
class HealthSuite extends munit.FunSuite {

  private val client = HttpClient.newHttpClient()

  private def get(port: Int, path: String): HttpResponse[String] =
    client.send(
      HttpRequest.newBuilder(URI.create(s"http://localhost:$port$path")).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    )

  test("GET /eezo/health answers 200 on a server with no routes at all") {
    val server = HttpServer.start(port = 0, routes = RouteTable.empty)
    try {
      val port     = server.port
      val response = get(port, HttpServer.HealthPath)
      assertEquals(response.statusCode(), 200)
      assertEquals(response.body(), "ok")
      // and nothing else under the reserved prefix leaks a page
      assertEquals(get(port, "/eezo/nothing").statusCode(), 404)
    } finally server.stop()
  }

  test("a user catch-all cannot shadow it") {
    val table = RouteTable(
      Seq(Route.Http(Method.GET, PathPattern.parse("/*rest"), _ => Response.status(418)))
    )
    val server = HttpServer.start(port = 0, routes = table)
    try {
      val port = server.port
      assertEquals(get(port, HttpServer.HealthPath).statusCode(), 200)
      assertEquals(get(port, "/anything-else").statusCode(), 418)
    } finally server.stop()
  }
}
