package site

import io.eezo.generated.Routes
import io.eezo.http.Method
import io.eezo.http.Request
import io.eezo.http.Session
import munit.FunSuite

/** The dressing of a scaladoc page, on a page shaped like the ones scaladoc writes. */
class ApiPagesSuite extends FunSuite {

  private val page =
    """<!DOCTYPE html><html><head><meta charset="utf-8"></meta>
      |<script type="text/javascript" src="../../../scripts/theme.js"></script>
      |<link rel="stylesheet" href="../../../styles/theme/bundle.css">
      |</head><body><div id="header" class="body-small">
      |<div class="header-container-left">
      |<a href="../../../" class="logo-container">
      |<span class="project-name h300">eezo</span>
      |</a>
      |</div>
      |<div class="header-container-right"><button id="search-toggle"></button></div>
      |</div><main>content</main></body></html>""".stripMargin

  /** A session holding a CSRF token, which only dispatch mints: one request through the table, and
    * the session its response carries is the one a hand-built request needs for the theme form.
    */
  private val minted: Session =
    Routes
      .table()
      .dispatch(Request(Method.GET, "/", Map.empty, Map.empty, Array.empty, Map.empty))
      .session
      .getOrElse(fail("dispatch minted no session"))

  private def request(theme: Option[String]): Request = {
    val session = theme.fold(minted)(minted.set(Theme.Key, _))
    Request(Method.GET, "/api/x.html", Map.empty, Map.empty, Array.empty, Map.empty, session)
  }

  test("the stylesheet is linked last in the head") {
    val out = ApiPages.dress(page, request(None))
    val at  = out.indexOf("api.css?v=")
    assert(at > 0 && at > out.indexOf("bundle.css") && at < out.indexOf("</head>"), out)
  }

  test("the logo and the site's links take the header, the rest of the page is untouched") {
    val out = ApiPages.dress(page, request(None))
    assert(!out.contains("""class="project-name h300""""), out)
    assert(out.contains("""<a class="logo" href="/""""), out)
    assert(
      out.indexOf("""<nav class="site-links"""") < out.indexOf("""header-container-right"""),
      out
    )
    assert(out.contains("""<a href="/api/" aria-current="page">API</a>"""), out)
    assert(out.contains("<main>content</main>"), out)
  }

  test("the theme is seeded from the session, and cleared without a choice") {
    assert(
      ApiPages.dress(page, request(Some("dark"))).contains("""setItem("use-dark-theme","true")""")
    )
    assert(
      ApiPages.dress(page, request(Some("light"))).contains("""setItem("use-dark-theme","false")""")
    )
    assert(ApiPages.dress(page, request(None)).contains("""removeItem("use-dark-theme")"""))
    val out = ApiPages.dress(page, request(Some("dark")))
    assert(
      out.indexOf("use-dark-theme") < out.indexOf("scripts/theme.js"),
      "seeded before scaladoc reads it"
    )
  }

  test("a page without scaladoc's anchors comes back unchanged but for the stylesheet") {
    val plain = "<html><head></head><body>x</body></html>"
    val out   = ApiPages.dress(plain, request(None))
    assert(out.contains("api.css"), out)
    assert(out.endsWith("<body>x</body></html>"), out)
  }
}
