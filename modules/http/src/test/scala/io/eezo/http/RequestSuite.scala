package io.eezo.http

import java.util.UUID

import io.eezo.core.Id

/** What a handler reads. Note what is absent: an untyped `attachment: AnyRef` bag. Capabilities
  * arrive through the handler's own `using` list.
  */
class RequestSuite extends munit.FunSuite {

  case class Widget()

  private def request(
      method: Method = Method.GET,
      path: String = "/",
      query: Map[String, Seq[String]] = Map.empty,
      headers: Map[String, Seq[String]] = Map.empty,
      body: Array[Byte] = Array.emptyByteArray,
      pathParams: Map[String, String] = Map.empty
  ): Request = Request(method, path, query, headers, body, pathParams)

  test("header lookup is case insensitive") {
    val req = request(headers = Map("Content-Type" -> Seq("text/plain")))
    assertEquals(req.header("content-type"), Some("text/plain"))
    assertEquals(req.header("CONTENT-TYPE"), Some("text/plain"))
    assertEquals(req.header("accept"), None)
  }

  test("a forwarded scheme is the first of the first value, whatever the name's case") {
    assert(Request.isSecure(false, Map("x-forwarded-proto" -> Seq("HTTPS, http", "http"))))
    assert(!Request.isSecure(false, Map("X-FORWARDED-PROTO" -> Seq("http", "https"))))
    assert(!Request.isSecure(false, Map.empty))
    assert(Request.isSecure(true, Map("X-Forwarded-Proto" -> Seq("http"))))
  }

  test("queryParam returns the first value of a repeated key") {
    val req = request(query = Map("tag" -> Seq("a", "b")))
    assertEquals(req.queryParam("tag"), Some("a"))
    assertEquals(req.queryParam("missing"), None)
  }

  test("form decodes urlencoded bodies, keeping every value of a repeated field") {
    val req = request(
      method = Method.POST,
      headers = Map("Content-Type" -> Seq("application/x-www-form-urlencoded")),
      body = "name=Tom+%26+Jerry&tag=a&tag=b".getBytes("UTF-8")
    )
    assertEquals(req.form, Map("name" -> Seq("Tom & Jerry"), "tag" -> Seq("a", "b")))
  }

  test("form is empty when the body is not form encoded") {
    val req = request(
      method = Method.POST,
      headers = Map("Content-Type" -> Seq("application/json")),
      body = """{"a":1}""".getBytes("UTF-8")
    )
    assertEquals(req.form, Map.empty[String, Seq[String]])
  }

  test("the form media type is read without regard to case or parameters") {
    for (
      contentType <- Seq(
        "Application/X-WWW-Form-Urlencoded",
        "APPLICATION/X-WWW-FORM-URLENCODED; charset=UTF-8",
        "application/x-www-form-urlencoded;charset=utf-8"
      )
    ) {
      val req = request(
        method = Method.POST,
        headers = Map("Content-Type" -> Seq(contentType)),
        body = "a=1".getBytes("UTF-8")
      )
      assertEquals(req.form, Map("a" -> Seq("1")), clue(contentType))
    }
    val other = request(
      method = Method.POST,
      headers = Map("Content-Type" -> Seq("application/x-www-form-urlencodedx")),
      body = "a=1".getBytes("UTF-8")
    )
    assertEquals(other.form, Map.empty[String, Seq[String]])
  }

  test("a field with no value decodes to the empty string") {
    val req = request(
      method = Method.POST,
      headers = Map("Content-Type" -> Seq("application/x-www-form-urlencoded; charset=UTF-8")),
      body = "name=&flag".getBytes("UTF-8")
    )
    assertEquals(req.form, Map("name" -> Seq(""), "flag" -> Seq("")))
  }

  test("a malformed percent escape in the form body is a BadRequest, so a 400") {
    // `%FF`, `%C3` and `%C3%28` are well formed escapes, but the bytes they name are not UTF-8.
    for (
      raw <- Seq(
        "name=%",
        "name=50%",
        "name=%zz",
        "%=1",
        "name=%FF",
        "name=%C3",
        "name=a%C3b",
        "%C3%28=1"
      )
    ) {
      val req = request(
        method = Method.POST,
        headers = Map("Content-Type" -> Seq("application/x-www-form-urlencoded")),
        body = raw.getBytes("UTF-8")
      )
      val refusal = intercept[BadRequest](req.form)
      assertEquals(refusal.detail, "the form body is not valid percent encoded UTF-8", clue(raw))
    }
  }

  test("a form body with UTF-8 escapes, raw text and plus signs still decodes") {
    val req = request(
      method = Method.POST,
      headers = Map("Content-Type" -> Seq("application/x-www-form-urlencoded")),
      body = "name=caf%C3%A9+%F0%9F%8D%B0&note=d%C3%A9j%C3%A0+vu&raw=é&pct=50%25".getBytes("UTF-8")
    )
    assertEquals(
      req.form,
      Map(
        "name" -> Seq("café 🍰"),
        "note" -> Seq("déjà vu"),
        "raw"  -> Seq("é"),
        "pct"  -> Seq("50%")
      )
    )
  }

  test("param converts a path parameter through FromPath") {
    val id  = UUID.randomUUID()
    val req =
      request(pathParams = Map("n" -> "42", "big" -> "9999999999", "s" -> "x", "u" -> id.toString))
    assertEquals(req.param[Int]("n"), 42)
    assertEquals(req.param[Long]("big"), 9999999999L)
    assertEquals(req.param[String]("s"), "x")
    assertEquals(req.param[UUID]("u"), id)
  }

  test("param reads a model's key, which is what a derived show asks for") {
    val id  = Id.gen[Widget]()
    val req = request(pathParams = Map("id" -> id.show, "bad" -> "not-a-uuid"))
    assertEquals(req.param[Id[Widget]]("id"), id)
    assertEquals(req.paramOpt[Id[Widget]]("bad"), None)
  }

  test("param throws the exception the boundary maps to 400 when the value will not convert") {
    val req     = request(pathParams = Map("id" -> "new"))
    val failure = intercept[BadRequest](req.param[Int]("id"))
    assert(clue(failure.getMessage).contains("id"))
  }

  test("param throws when the parameter is not in the pattern at all") {
    intercept[BadRequest](request().param[Int]("id"))
  }

  test("paramOpt is the non-throwing form") {
    val req = request(pathParams = Map("id" -> "7", "bad" -> "x"))
    assertEquals(req.paramOpt[Int]("id"), Some(7))
    assertEquals(req.paramOpt[Int]("bad"), None)
    assertEquals(req.paramOpt[Int]("missing"), None)
  }

  test("a request built by hand names nobody, and so does one in a build with no guard at all") {
    // The naming is written by whoever holds the rule, and `http` holds none: nothing short of
    // a guard can name anyone, so a request nobody named carries nobody.
    assertEquals(request().currentUser, None)
  }
}
