package io.eezo.http

import io.eezo.core.Id
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

  private final case class Order(quantity: Int)

  /** Any model at all, written as its `toString` in braces: the shape of an adapter's catch all
    * given, with no JSON library behind it, so that the overloads below are pinned in `http`
    * itself.
    */
  private object everything {
    given anything[A]: api.Serde[A] with {
      def serialize(value: A): String = s"{$value}"

      def deserialize(body: Array[Byte]): Either[api.Serde.Failure, A] =
        Left(api.Serde.Failure.Malformed("never read here"))

      def deserialize(
          body: Array[Byte],
          decided: Map[String, Id[Any]]
      ): Either[api.Serde.Failure, A] =
        deserialize(body)
    }
  }

  test("Ok with a model in a Serde is a 200 carrying its serialized text as JSON") {
    import everything.given
    val response = Response.Ok(Order(2))
    assertEquals(response.status, 200)
    assertEquals(response.body, Body.Json("{Order(2)}"))
    // No content type of its own: the server names a JSON body when the response names none.
    assertEquals(response.headers, Seq.empty)
  }

  test("Created with a model in a Serde is a 201 carrying it, and keeps a Location added to it") {
    import everything.given
    val response = Response.Created(Order(3)).withHeader("Location", "/api/orders/3")
    assertEquals(response.status, 201)
    assertEquals(response.body, Body.Json("{Order(3)}"))
    assertEquals(response.header("Location"), Some("/api/orders/3"))
  }

  test("Ok with a page still answers HTML when a Serde for every type is in scope") {
    import everything.given
    // The same scope sends a model as JSON, so the given is reachable here and was passed over.
    assertEquals(Response.Ok(Order(1)).body, Body.Json("{Order(1)}"))
    assertEquals(Response.Ok(p("hi")).body, Body.Html(p("hi")))
    assertEquals(Response.Ok(Html.text("x")).body, Body.Html(Html.text("x")))
    val page: Html = div(p("a"), p("b"))
    assertEquals(Response.Ok(page).headers, Seq("Content-Type" -> "text/html; charset=utf-8"))
  }
}
