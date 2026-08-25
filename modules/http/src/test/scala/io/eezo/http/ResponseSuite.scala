package io.eezo.http

import io.eezo.core.html.Html
import io.eezo.core.html.Tags.*

/** A response is a value. Users never touch Jetty's `Callback`, so exactly-once completion is not a
  * mistake they can make.
  */
class ResponseSuite extends munit.FunSuite {

  test("Ok carries HTML and the HTML content type") {
    val response = Response.Ok(p("hi"))
    assertEquals(response.status, 200)
    assertEquals(response.body, Body.Html(p("hi")))
    assertEquals(response.headers, Seq("Content-Type" -> "text/html; charset=utf-8"))
  }

  test("Redirect is a 303 with a Location and no body") {
    val response = Response.Redirect("/widgets")
    assertEquals(response.status, 303)
    assertEquals(response.headers, Seq("Location" -> "/widgets"))
    assertEquals(response.body, Body.Empty)
  }

  test("status reaches any code, including the ones eezo does not model") {
    assertEquals(Response.status(418).status, 418)
    assertEquals(Response.status(404).body, Body.Empty)
  }

  test("a header can be added to any response, and duplicates survive") {
    val response = Response
      .Ok(Html.text("x"))
      .withHeader("Set-Cookie", "a=1")
      .withHeader("Set-Cookie", "b=2")
    assertEquals(
      response.headers,
      Seq(
        "Content-Type" -> "text/html; charset=utf-8",
        "Set-Cookie"   -> "a=1",
        "Set-Cookie"   -> "b=2"
      )
    )
  }
}
