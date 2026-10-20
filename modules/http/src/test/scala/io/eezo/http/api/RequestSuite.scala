package io.eezo.http.api

import java.nio.charset.StandardCharsets

import io.eezo.core.Id
import io.eezo.http.{BadRequest, Method, UnprocessableContent}

/** What a handler on an API route reads: the request a program sent, and nothing a browser carries.
  *
  * The absence of a session, a flash, a CSRF token, the cookies and the form is the point of the
  * type, so it is pinned by a compile error rather than left to a reader of the source.
  */
class RequestSuite extends munit.FunSuite {

  private val request = Request(
    method = Method.POST,
    path = "/webhooks/stripe/42",
    query = Map("mode" -> Seq("live", "test")),
    headers = Map("Stripe-Signature" -> Seq("t=1,v1=abc", "t=2,v1=def")),
    body = """{"id":"evt_1"}""".getBytes(StandardCharsets.UTF_8),
    pathParams = Map("id" -> "42", "word" -> "abc")
  )

  test("it holds the method, the path, the query, the headers, the body and the path parameters") {
    assertEquals(request.method, Method.POST)
    assertEquals(request.path, "/webhooks/stripe/42")
    assertEquals(request.query, Map("mode" -> Seq("live", "test")))
    assertEquals(request.headers("Stripe-Signature"), Seq("t=1,v1=abc", "t=2,v1=def"))
    assertEquals(new String(request.body, StandardCharsets.UTF_8), """{"id":"evt_1"}""")
    assertEquals(request.pathParams, Map("id" -> "42", "word" -> "abc"))
  }

  test("a header is read case insensitively, and the first value wins") {
    assertEquals(request.header("stripe-signature"), Some("t=1,v1=abc"))
    assertEquals(request.header("Authorization"), None)
  }

  test("a query parameter is read by name, and the first value wins") {
    assertEquals(request.queryParam("mode"), Some("live"))
    assertEquals(request.queryParam("page"), None)
  }

  test("a path parameter is converted through FromPath") {
    assertEquals(request.param[Int]("id"), 42)
    assertEquals(request.param[String]("word"), "abc")
  }

  test("a path parameter that will not convert is refused the way a browser Request refuses it") {
    val plain =
      io.eezo.http.Request(
        Method.GET,
        "/",
        Map.empty,
        Map.empty,
        Array.emptyByteArray,
        request.pathParams
      )
    val api     = intercept[BadRequest](request.param[Int]("word"))
    val browser = intercept[BadRequest](plain.param[Int]("word"))
    assertEquals(api.getMessage, browser.getMessage)
    assert(
      api.getMessage.contains("path parameter 'word' cannot be read from 'abc'"),
      api.getMessage
    )
  }

  test("a path parameter the route does not have is refused the way a browser Request refuses it") {
    val plain =
      io.eezo.http.Request(Method.GET, "/", Map.empty, Map.empty, Array.emptyByteArray, Map.empty)
    val api     = intercept[BadRequest](request.param[Int]("missing"))
    val browser = intercept[BadRequest](plain.param[Int]("missing"))
    assertEquals(api.getMessage, browser.getMessage)
    assert(api.getMessage.contains("path parameter 'missing' is not part of this route"))
  }

  test("nothing a browser carries is there to read") {
    Seq(
      compileErrors("request.session"),
      compileErrors("request.csrf"),
      compileErrors("request.cookies"),
      compileErrors("request.cookie(\"eezo_session\")"),
      compileErrors("request.form")
    ).foreach(failure => assert(clue(failure).contains("is not a member of"), failure))
  }

  test("who the guard named travels into the API request, and nobody is the default") {
    assertEquals(request.currentUser, None)
    val named =
      io.eezo.http
        .Request(Method.GET, "/", Map.empty, Map.empty, Array.emptyByteArray, Map.empty)
        .copy(currentUser = Some("ann"))
    assertEquals(Request.of(named).currentUser, Some("ann"))
  }

  /** A payload whose instance answers with whatever the body names, so each outcome of the seam can
    * be asked for by the body alone, with no JSON library in `http`.
    */
  private final case class Quantity(value: Int)

  private given Serde[Quantity] with {
    def serialize(value: Quantity): String = value.value.toString

    def deserialize(body: Array[Byte]): Either[Serde.Failure, Quantity] =
      new String(body, StandardCharsets.UTF_8) match {
        case "malformed" => Left(Serde.Failure.Malformed("expected json at $"))
        case "unfit"     => Left(Serde.Failure.Unfit("missing keys in dictionary: value at $"))
        case number      => Right(Quantity(number.toInt))
      }

    def deserialize(body: Array[Byte], key: Id[Quantity]): Either[Serde.Failure, Quantity] =
      deserialize(body)
  }

  private def sending(body: String): Request =
    request.copy(body = body.getBytes(StandardCharsets.UTF_8))

  test("a body the Serde reads is the value as returns") {
    assertEquals(sending("3").as[Quantity], Quantity(3))
  }

  test("a body that is not JSON is refused as a BadRequest carrying the Serde's reason") {
    assertEquals(
      intercept[BadRequest](sending("malformed").as[Quantity]).detail,
      "expected json at $"
    )
  }

  test("JSON that does not fit the model is refused as UnprocessableContent with the reason") {
    assertEquals(
      intercept[UnprocessableContent](sending("unfit").as[Quantity]).detail,
      "missing keys in dictionary: value at $"
    )
  }
}
