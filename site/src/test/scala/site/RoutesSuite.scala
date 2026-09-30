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

  test("the front page, with its live demo mounted") {
    val response = get("/")
    assertEquals(response.status, 200)
    val html = page(response)
    assert(html.contains("source of truth"))
    assert(html.contains("data-eezo-page="), html)
    assert(html.contains("""<script src="/eezo/live.js" defer>"""), html)
    assert(!html.contains("site.js"), html)
  }

  test("a docs page mounts the drawer and posts its theme toggle with a token") {
    val html = page(get("/docs"))
    assert(html.contains("""class="drawer""""), html)
    assert(html.contains("""data-eezo-click="toggle""""), html)
    assert(html.contains("""<form class="theme-form" method="post" action="/theme">"""), html)
    assert(html.contains("""name="_csrf""""), html)
  }

  test("the overview and a page under docs") {
    assert(page(get("/docs")).contains("<h1"))
    assert(page(get("/docs/live")).contains("live walkthrough"))
    assert(
      page(get("/docs/adr/0006-a-failure-travels-to-the-nearest-boundary-that-owns-it")).contains(
        "<h1"
      )
    )
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

  test("an asset path cannot leave the directory, and an unknown type is not served") {
    intercept[NotFound](get("/assets/../content/README.md"))
    intercept[NotFound](get("/assets/missing.css"))
    assertEquals(Assets.read("../content/index.txt"), None)
    assertEquals(Assets.contentType("x.exe"), None)
  }
}
