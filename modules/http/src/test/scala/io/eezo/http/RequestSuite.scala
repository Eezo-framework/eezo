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
    // The stamp is written by whoever holds the rule, and `http` holds none: nothing short of a
    // guard can name anyone, so a request nobody stamped carries nobody.
    assertEquals(request().currentUser, None)
  }

  test("nothing under modules/http/src/main names the current user itself") {
    // The invariant the field lives by: `http` holds the value and whoever holds the rule, a guard,
    // writes it. Nothing here may, which is what keeps a header, a query or path parameter and a
    // frame, all of them a client's to choose, out of it. The one writer is the guard's stamp in
    // `modules/auth`, and the table's `identify` is that stamp composed rather than a second one.
    //
    // A write is `currentUser =`, as a named argument to `copy` or to the constructor. The pattern
    // steps around `val currentUser =`, which is [[Owned]]'s own function of that name, a lookup a
    // resource calls and an older thing entirely, bound to a local in `Resource`.
    val writes  = java.util.regex.Pattern.compile("""(?<!val )(?<!var )currentUser\s*=""")
    val written = RequestSuite
      .sourcesUnder("modules/http/src/main")
      .filter { case (_, text) => writes.matcher(text).find() }
      .map { case (path, _) => path }
    assertEquals(written, Seq.empty[String], "something in http names the current user itself")
  }
}

object RequestSuite {

  /** Every Scala source under `path`, found from the working directory sbt runs a suite in and from
    * its parents, so that the scan passes whether the suite was started at the build root or inside
    * the module. `RouteGeneratorSuite` reads its pinned copy the same way and for the same reason.
    */
  def sourcesUnder(path: String): Seq[(String, String)] = {
    def upwards(from: java.io.File, left: Int): Option[java.io.File] = {
      val candidate = new java.io.File(from, path)
      if (candidate.isDirectory) Some(candidate)
      else if (left == 0 || from.getParentFile == null) None
      else upwards(from.getParentFile, left - 1)
    }
    def walk(dir: java.io.File): Seq[java.io.File] =
      Option(dir.listFiles()).toSeq.flatten.flatMap { file =>
        if (file.isDirectory) walk(file) else Seq(file)
      }
    val here = new java.io.File(".").getCanonicalFile
    val root = upwards(here, 4).getOrElse(throw new AssertionError(s"$path not found from $here"))
    walk(root).filter(_.getName.endsWith(".scala")).sortBy(_.getPath).map { file =>
      val source = scala.io.Source.fromFile(file, "UTF-8")
      try (file.getPath, source.mkString)
      finally source.close()
    }
  }
}
