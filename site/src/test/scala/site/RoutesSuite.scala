package site

import io.eezo.generated.Routes
import io.eezo.http.Body
import io.eezo.http.Method
import io.eezo.http.NotFound
import io.eezo.http.Request
import io.eezo.http.Response
import munit.FunSuite

/** The generated table, driven the way a request drives it. */
class RoutesSuite extends FunSuite {

  private def get(path: String, query: Map[String, Seq[String]] = Map.empty): Response =
    Routes.table().dispatch(Request(Method.GET, path, query, Map.empty, Array.empty, Map.empty))

  private def page(response: Response): String = response.body match {
    case Body.Html(html) => html.render
    case other           => fail(s"expected a page, got $other")
  }

  test("the search page opens the dialog, and every page offers the shortcut") {
    val html = page(get("/search"))
    assert(html.contains("search-input"), html)
    assert(html.contains("""data-search-open"""), html)
    assert(page(get("/")).contains("""class="shortcut other""""))
  }

  test("the front page, with its live demo mounted") {
    val response = get("/")
    assertEquals(response.status, 200)
    val html = page(response)
    assert(html.contains("source of truth"))
    assert(html.contains("data-eezo-page="), html)
    assert(html.contains("""<script src="/eezo/live.js" defer>"""), html)
    assert(!html.contains("site.js"), html)
    assert(html.contains("hljs/highlight.min.js?v="), html)
    assert(html.contains("hljs/scala.min.js?v="), html)
    assert(html.contains("<script>hljs.highlightAll()</script>"), html)
  }

  test("highlight.js is served out of the jar") {
    val core = get("/assets/hljs/highlight.min.js")
    assertEquals(core.status, 200)
    assertEquals(core.header("Content-Type"), Some("text/javascript; charset=utf-8"))
    val scala = get("/assets/hljs/scala.min.js")
    assertEquals(scala.status, 200)
    val grammar = scala.body match {
      case Body.Bytes(bytes) => new String(bytes, "UTF-8")
      case other             => fail(s"expected bytes, got $other")
    }
    assert(grammar.contains("extends with derives"), "the Scala grammar knows derives in a class")
    assert(
      grammar.contains("transparent derives opaque infix open using as"),
      "and the Scala 3 soft keywords"
    )
    assertEquals(get("/assets/hljs/nginx.min.js").status, 200)
  }

  test("a docs page mounts the drawer and posts its theme toggle with a token") {
    val html = page(get("/docs/overview"))
    assert(html.contains("""class="drawer""""), html)
    assert(html.contains("""data-eezo-click="toggle""""), html)
    assert(html.contains("""<form class="theme-form" method="post" action="/theme">"""), html)
    assert(html.contains("""name="_csrf""""), html)
  }

  test("the hub, a pillar index, the overview and a page under docs") {
    assert(page(get("/docs")).contains("pillar-cards"))
    assert(page(get("/docs/tutorials")).contains("page-list"))
    assert(page(get("/docs/overview")).contains("<h1"))
    assert(page(get("/docs/tutorials/a-live-page")).contains("live walkthrough"))
  }

  test("an unknown page is a 404") {
    intercept[NotFound](get("/docs/nothing-here"))
    intercept[NotFound](get("/nothing"))
  }

  test("an asset comes back with its content type, and a versioned one is immutable") {
    val response = get("/assets/site.css")
    assertEquals(response.status, 200)
    assertEquals(response.header("Content-Type"), Some("text/css; charset=utf-8"))
    assertEquals(response.header("Cache-Control"), Some("public, max-age=300"))
    assert(response.body.isInstanceOf[Body.Bytes])

    val versioned = get("/assets/site.css", Map("v" -> Seq(Assets.version)))
    assertEquals(versioned.header("Cache-Control"), Some("public, max-age=31536000, immutable"))

    assertEquals(get("/assets/fonts/nunito.woff2").header("Content-Type"), Some("font/woff2"))
  }

  test("the API docs are served under /api, and /api itself is sent to the directory") {
    val bare = get("/api")
    assertEquals(bare.status, 303)
    assertEquals(bare.header("Location"), Some("/api/"))
    if (ApiDocs.present) {
      val index = get("/api/")
      assertEquals(index.status, 200)
      assertEquals(index.header("Content-Type"), Some("text/html; charset=utf-8"))
      assertEquals(index.header("Cache-Control"), Some("no-cache"))
      val html = index.body match {
        case Body.Bytes(bytes) => new String(bytes, "UTF-8")
        case other             => fail(s"expected bytes, got $other")
      }
      assert(html.contains("""class="site-links""""), "the site's links are in scaladoc's header")
      assert(html.contains("""class="logo""""), "the site's logo replaces the project name")
      assert(html.contains("api.css?v="), "the site's stylesheet is linked")
      assert(html.contains("use-dark-theme"), "the theme is seeded")
      assert(html.contains("""name="_csrf""""), "the theme toggle carries a token")
      assertEquals(
        get("/api/styles/theme/bundle.css").header("Cache-Control"),
        Some("public, max-age=3600")
      )
      assertEquals(get("/api/io/eezo/http.html").status, 200)
      assertEquals(get("/api/io/eezo/http/Request.html").status, 200)
      intercept[NotFound](get("/api/io/eezo/http/Nope.html"))
      intercept[NotFound](get("/api/../assets/site.css"))
    } else assume(ApiDocs.present, "run `sbt unidoc` at the repository root to test the API docs")
  }

  test("an asset path cannot leave the directory, and an unknown type is not served") {
    intercept[NotFound](get("/assets/../content/README.md"))
    intercept[NotFound](get("/assets/missing.css"))
    assertEquals(Assets.read("../content/index.txt"), None)
    assertEquals(Assets.contentType("x.exe"), None)
  }
}
