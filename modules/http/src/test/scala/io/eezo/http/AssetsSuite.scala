package io.eezo.http

import io.eezo.core.html.Url

/** The asset route over three fixtures under `src/test/resources/assets`: a stylesheet, a script in
  * a subdirectory, and a file whose extension names no content type.
  */
class AssetsSuite extends munit.FunSuite {

  private val table = RouteTable(Seq(Assets.route))

  private def get(path: String, query: Map[String, Seq[String]] = Map.empty): Response =
    table.dispatch(Request(Method.GET, path, query, Map.empty, Array.emptyByteArray, Map.empty))

  private def header(response: Response, name: String): Option[String] =
    response.headers.collectFirst { case (n, value: String) if n == name => value }

  private val css = Assets.version("suite.css").getOrElse(fail("suite.css is not on the classpath"))

  test("a file is served with its content type and bytes") {
    val response = get("/assets/suite.css")
    assertEquals(response.status, 200)
    assertEquals(header(response, "Content-Type"), Some("text/css; charset=utf-8"))
    response.body match {
      case Body.Bytes(bytes) =>
        assertEquals(new String(bytes, "UTF-8"), "body { color: rebeccapurple }\n")
      case other => fail(s"a byte body was expected, not $other")
    }
  }

  test("a file in a subdirectory is served under its path") {
    val response = get("/assets/nested/suite.js")
    assertEquals(response.status, 200)
    assertEquals(header(response, "Content-Type"), Some("text/javascript; charset=utf-8"))
  }

  test("the address that carries the file's version is cached for a year") {
    val response = get("/assets/suite.css", Map("v" -> Seq(css)))
    assertEquals(header(response, "Cache-Control"), Some("public, max-age=31536000, immutable"))
  }

  test("an address without the version, or with a stale one, is revalidated soon") {
    assertEquals(header(get("/assets/suite.css"), "Cache-Control"), Some("public, max-age=300"))
    assertEquals(
      header(get("/assets/suite.css", Map("v" -> Seq("000000000000"))), "Cache-Control"),
      Some("public, max-age=300")
    )
  }

  test("a path that is not a plain descent into assets/ is not found") {
    intercept[NotFound](get("/assets/../logback-test.xml"))
    intercept[NotFound](get("/assets/./suite.css"))
  }

  test("a file that is not there, and a file with no content type, are the same 404") {
    assertEquals(intercept[NotFound](get("/assets/missing.css")).path, "/assets/missing.css")
    intercept[NotFound](get("/assets/suite.unknown"))
  }

  test("the version is twelve hex characters of the file's digest, the same on every ask") {
    assert(css.matches("[0-9a-f]{12}"), css)
    assertEquals(Assets.version("suite.css"), Some(css))
    assertEquals(Assets.version("missing.css"), None)
  }

  test("url spells the version in, as an absolute address no mount moves") {
    assertEquals(Assets.url("suite.css"), Url.Absolute(s"/assets/suite.css?v=$css"))
    assertEquals(Assets.url("suite.css").under("/admin"), Url.Absolute(s"/assets/suite.css?v=$css"))
  }

  test("url on a file that is not there is a defect naming the file") {
    val thrown = intercept[IllegalStateException](Assets.url("missing.css"))
    assert(thrown.getMessage.contains("assets/missing.css"), thrown.getMessage)
  }

  test("the content type table serves the web's own kinds and nothing else") {
    assertEquals(Assets.contentType("site.CSS"), Some("text/css; charset=utf-8"))
    assertEquals(Assets.contentType("font.woff2"), Some("font/woff2"))
    assertEquals(Assets.contentType("logo.svg"), Some("image/svg+xml"))
    assertEquals(Assets.contentType("archive.zip"), None)
    assertEquals(Assets.contentType("noextension"), None)
  }
}
