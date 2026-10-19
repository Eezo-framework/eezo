package io.eezo.http

import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

import scala.jdk.CollectionConverters.*

import io.eezo.core.html.*

/** A JSON body over the wire: the text a handler serialized goes out as it was written, as UTF-8,
  * under a JSON content type, and nothing that frames a page ever reaches into it.
  *
  * Driven through a booted server, because the content type is added where the response is written
  * and the frame and the reload script go on where every response passes, and neither is visible on
  * the value a handler returns.
  */
class JsonBodySuite extends munit.FunSuite with ServerFixtures {

  /** Text with a character outside ASCII and one outside the basic plane, so that an encoding other
    * than UTF-8 anywhere on the way out changes the bytes.
    */
  private val text = "{\"a\":1,\"name\":\"café ☕ 𝄞\"}"

  private def replying(response: Response): RouteTable =
    RouteTable(
      Seq(
        Route.Http(Method.GET, PathPattern.parse("/browser"), _ => response),
        Route.handwritten(Method.GET, "/api", (_: api.Request) => response),
        Route.Http(Method.GET, PathPattern.parse("/page"), _ => Response.Ok(p("a page")))
      )
    )

  private def bytes(port: Int, path: String): HttpResponse[Array[Byte]] =
    client.send(
      HttpRequest.newBuilder(URI.create(s"http://localhost:$port$path")).GET().build(),
      HttpResponse.BodyHandlers.ofByteArray()
    )

  private def contentTypes(response: HttpResponse[?]): Seq[String] =
    response.headers().allValues("Content-Type").asScala.toSeq

  test("a JSON body reaches the client as its text in UTF-8, under a JSON content type") {
    serving(replying(Response(200, Seq.empty, Body.Json("{\"a\":1}")))) { (_, port) =>
      Seq("/browser", "/api").foreach { path =>
        val response = bytes(port, path)
        assertEquals(response.statusCode(), 200, path)
        assertEquals(contentTypes(response), Seq("application/json; charset=utf-8"), path)
        assertEquals(response.body().toSeq, "{\"a\":1}".getBytes(StandardCharsets.UTF_8).toSeq)
      }
    }
    serving(replying(Response(200, Seq.empty, Body.Json(text)))) { (_, port) =>
      assertEquals(bytes(port, "/api").body().toSeq, text.getBytes(StandardCharsets.UTF_8).toSeq)
    }
  }

  test("a content type the handler names is the one that goes out, and only once") {
    val named = Response(200, Seq("Content-Type" -> "application/vnd.shop+json"), Body.Json(text))
    serving(replying(named)) { (_, port) =>
      assertEquals(contentTypes(bytes(port, "/api")), Seq("application/vnd.shop+json"))
    }
  }

  test("a JSON body is never framed by the layout nor given the reload script, in dev or not") {
    Seq(false, true).foreach { dev =>
      serving(replying(Response(200, Seq.empty, Body.Json(text))), dev = dev, layout = marked) {
        (_, port) =>
          // The page beside them proves the frame and, in dev, the script are on for this server.
          val page = new String(bytes(port, "/page").body(), StandardCharsets.UTF_8)
          assert(clue(page).contains("framed"), page)
          assertEquals(page.contains("<script>"), dev, page)
          Seq("/browser", "/api").foreach { path =>
            val response = bytes(port, path)
            assertEquals(
              new String(response.body(), StandardCharsets.UTF_8),
              text,
              s"$path with dev=$dev"
            )
          }
      }
    }
  }
}
