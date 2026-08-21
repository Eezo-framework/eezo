package eezo.http

import java.util.UUID

/** What a handler reads. Note what is absent: an untyped `attachment: AnyRef` bag. Capabilities
  * arrive through the handler's own `using` list.
  */
class RequestSuite extends munit.FunSuite {

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

  test("a field with no value decodes to the empty string") {
    val req = request(
      method = Method.POST,
      headers = Map("Content-Type" -> Seq("application/x-www-form-urlencoded; charset=UTF-8")),
      body = "name=&flag".getBytes("UTF-8")
    )
    assertEquals(req.form, Map("name" -> Seq(""), "flag" -> Seq("")))
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
}
