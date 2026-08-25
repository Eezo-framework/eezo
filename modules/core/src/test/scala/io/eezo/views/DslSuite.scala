package io.eezo.views

import io.eezo.core.html.*

/** The DSL as a view file sees it: one import, tags unqualified, attributes through `Attrs`.
  *
  * This suite lives outside `io.eezo.core.html` deliberately. It is what proves that the single
  * import reaches the tags, that no `import scala.language.implicitConversions` is needed, and that
  * `Html.Text`'s constructor is out of reach while its pattern match is not.
  */
class DslSuite extends munit.FunSuite {

  test("a tag takes attributes, strings and numbers in one varargs list") {
    assertEquals(
      p(Attrs.cls := "lead", "answer ", 42).render,
      """<p class="lead">answer 42</p>"""
    )
  }

  test("text passed as a String is escaped") {
    assertEquals(p("<script>").render, "<p>&lt;script&gt;</p>")
  }

  test("an Iterable of children is absorbed with no ceremony") {
    val items = List("a", "b")
    assertEquals(
      ul(items.map(s => li(key(s), s))).render,
      """<ul><li data-eezo-key="a">a</li><li data-eezo-key="b">b</li></ul>"""
    )
  }

  test("a true Boolean attribute renders bare and a false one renders nothing") {
    assertEquals(
      input(Attrs.tpe := "checkbox", Attrs.checked := true).render,
      """<input type="checkbox" checked>"""
    )
    assertEquals(
      input(Attrs.tpe := "checkbox", Attrs.checked := false).render,
      """<input type="checkbox">"""
    )
  }

  test("an Int attribute value is written without a conversion") {
    assertEquals(input(Attrs.size := 4).render, """<input size="4">""")
  }

  test("a duplicate attribute name collapses, last wins") {
    assertEquals(div(Attrs.cls := "a", Attrs.cls := "b").render, """<div class="b"></div>""")
  }

  test("the three Scala keywords are spelled cls, tpe and htmlFor") {
    assertEquals(
      label(Attrs.htmlFor := "n", input(Attrs.tpe := "text", Attrs.cls := "field")).render,
      """<label for="n"><input type="text" class="field"></label>"""
    )
  }

  test("a fragment child is spliced, so tree child index equals DOM child index") {
    val node     = div(span("a"), Html.text("x") ++ Html.text("y"), span("z"))
    val children = node match {
      case Html.Element(_, _, _, cs) => cs
      case other                     => fail(s"expected an element, got $other")
    }
    assertEquals(children.size, 4)
    assert(!children.exists(_.isInstanceOf[Html.Fragment]))
    assertEquals(node.render, "<div><span>a</span>xy<span>z</span></div>")
  }

  test("a whole page is one expression") {
    val page =
      Html.doctype ++ html(
        head(title("eezo")),
        body(h1("hello"), a(Attrs.href := "/next", "next"))
      )
    assertEquals(
      page.render,
      "<!DOCTYPE html><html><head><title>eezo</title></head>" +
        """<body><h1>hello</h1><a href="/next">next</a></body></html>"""
    )
  }

  test("escaped text cannot be forged from outside the html package") {
    assert(compileErrors("""Html.Text("<script>")""").nonEmpty)
  }
}
