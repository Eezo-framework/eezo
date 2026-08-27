package io.eezo.http

import java.nio.charset.StandardCharsets

/** `_method`, which is how a browser that can only issue `GET` and `POST` reaches `PUT` and
  * `DELETE`.
  *
  * The override is applied before dispatch, so the `Request` a handler sees carries the real verb
  * and `RouteTable.dispatch` stays a pure function of a request whose method is true.
  */
class MethodOverrideSuite extends munit.FunSuite {

  private def post(
      body: String = "",
      query: Map[String, Seq[String]] = Map.empty,
      contentType: String = "application/x-www-form-urlencoded",
      method: Method = Method.POST
  ): Request =
    Request(
      method = method,
      path = "/widgets/1",
      query = query,
      headers = Map("Content-Type" -> Seq(contentType)),
      body = body.getBytes(StandardCharsets.UTF_8),
      pathParams = Map.empty
    )

  test("a form-encoded POST carrying _method=PUT becomes a PUT") {
    assertEquals(Request.withMethodOverride(post("_method=PUT&title=x")).method, Method.PUT)
  }

  test("the override is case insensitive, because the input is written by hand") {
    assertEquals(Request.withMethodOverride(post("_method=delete")).method, Method.DELETE)
  }

  test("a query string override works too, for a link that cannot carry a body") {
    assertEquals(
      Request.withMethodOverride(post(query = Map("_method" -> Seq("PUT")))).method,
      Method.PUT
    )
  }

  test("the body wins over the query string when both are present") {
    assertEquals(
      Request
        .withMethodOverride(post("_method=DELETE", query = Map("_method" -> Seq("PUT"))))
        .method,
      Method.DELETE
    )
  }

  test("a POST is never downgraded to GET, whatever the field says") {
    assertEquals(Request.withMethodOverride(post("_method=GET")).method, Method.POST)
  }

  test("only a POST is overridden: a GET carrying the field stays a GET") {
    val request = post("_method=DELETE", method = Method.GET)
    assertEquals(Request.withMethodOverride(request).method, Method.GET)
  }

  test("a method eezo does not model leaves the request alone rather than failing") {
    assertEquals(Request.withMethodOverride(post("_method=TRACE")).method, Method.POST)
  }

  test("a POST with no override at all is untouched") {
    assertEquals(Request.withMethodOverride(post("title=x")).method, Method.POST)
  }

  test("a JSON POST is not form-encoded, so its body is never read for an override") {
    val request = post("""{"_method":"DELETE"}""", contentType = "application/json")
    assertEquals(Request.withMethodOverride(request).method, Method.POST)
  }
}
