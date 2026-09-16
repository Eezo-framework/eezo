package io.eezo.http

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

import org.eclipse.jetty.server.ServerConnector

/** The framework health endpoint: always mounted, unshadowable, and cheap. */
class HealthSuite extends munit.FunSuite {

  private val client = HttpClient.newHttpClient()

  private def get(port: Int, path: String): HttpResponse[String] =
    client.send(
      HttpRequest.newBuilder(URI.create(s"http://localhost:$port$path")).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    )

  test("GET /eezo/health answers 200 on a server with no routes at all") {
    val server = Eezo.start(port = 0, config = Config(RouteTable.empty))
    try {
      val port     = server.getConnectors.head.asInstanceOf[ServerConnector].getLocalPort
      val response = get(port, Eezo.HealthPath)
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
    val server = Eezo.start(port = 0, config = Config(table))
    try {
      val port = server.getConnectors.head.asInstanceOf[ServerConnector].getLocalPort
      assertEquals(get(port, Eezo.HealthPath).statusCode(), 200)
      assertEquals(get(port, "/anything-else").statusCode(), 418)
    } finally server.stop()
  }
}
