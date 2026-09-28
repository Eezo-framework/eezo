package io.eezo.http

import io.eezo.core.html.Html
import io.eezo.core.html.Tags.*

/** The reload client's injection, as a pure function over a response.
  *
  * The contract (the reload contract ticket, issue 153): `Body.Html` only, the script appended as
  * the last child of the first `body` element, nothing when there is no `body`.
  */
class ReloadSuite extends munit.FunSuite with ResourceFixtures {

  private val dev  = HttpConfig(dev = true)
  private val prod = HttpConfig(dev = false)

  test("a full document gets the script as the last child of body when dev is on") {
    val page = Response.Ok(Html.doctype ++ html(head(title("t")), body(h1("hi"), p("x"))))
    assertEquals(
      markup(Reload.inject(page, dev)),
      "<!DOCTYPE html><html><head><title>t</title></head>" +
        s"<body><h1>hi</h1><p>x</p>${Reload.tag.render}</body></html>"
    )
    assert(clue(Reload.tag.render).startsWith("<script>"))
    assert(clue(Reload.tag.render).contains("/eezo/reload"))
  }

  test("nothing is injected when dev is off") {
    val page = Response.Ok(html(body(h1("hi"))))
    assertEquals(Reload.inject(page, prod), page)
  }

  test("a fragment with no body element is left as it is") {
    val fragment = Response.Ok(div(p("a")) ++ div(p("b")))
    assertEquals(markup(Reload.inject(fragment, dev)), "<div><p>a</p></div><div><p>b</p></div>")
  }

  test("a raw document is opaque and gets no script") {
    val raw = Response.Ok(Html.raw("<html><body><h1>hi</h1></body></html>"))
    assertEquals(markup(Reload.inject(raw, dev)), "<html><body><h1>hi</h1></body></html>")
  }

  test("a byte body is never touched, even with an HTML content type") {
    val bytes = Response(
      200,
      Seq(Response.HtmlContentType),
      Body.Bytes("<html><body></body></html>".getBytes("UTF-8"))
    )
    assertEquals(Reload.inject(bytes, dev), bytes)
  }

  test("an error page is injected too: injection follows the body, not the status") {
    val page = Response.Ok(html(body(h1("500")))).copy(status = 500)
    assert(clue(markup(Reload.inject(page, dev))).contains("<script>"))
    assertEquals(Reload.inject(page, dev).status, 500)
  }

  test("only the first body element takes the tag") {
    val page = Response.Ok(html(body(p("one")), body(p("two"))))
    val out  = markup(Reload.inject(page, dev))
    assertEquals(out.split("<script>").length - 1, 1)
    assert(clue(out).startsWith("<html><body><p>one</p><script>"))
  }
}
