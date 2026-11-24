package io.eezo.views

import io.eezo.core.html.*

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

  test("a duplicate attribute name collapses, last wins, and class is no exception") {
    assertEquals(div(Attrs.cls := "a", Attrs.cls := "b").render, """<div class="b"></div>""")
  }

  test("a repeated attribute keeps its last value at the position of its last occurrence") {
    assertEquals(
      div(Attrs.cls := "a", Attrs.id := "x", Attrs.cls := "b").render,
      """<div id="x" class="b"></div>"""
    )
  }

  test("the three Scala keywords are spelled cls, tpe and htmlFor") {
    assertEquals(
      label(Attrs.htmlFor := "n", input(Attrs.tpe := "text", Attrs.cls := "field")).render,
      """<label for="n"><input type="text" class="field"></label>"""
    )
  }

  test("a fragment child is spliced and its text merged, so tree children count as DOM children") {
    val node     = div(span("a"), Html.text("x") ++ Html.text("y"), span("z"))
    val children = node match {
      case Html.Element(_, _, _, cs) => cs
      case other                     => fail(s"expected an element, got $other")
    }
    assertEquals(children.size, 3)
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

  test("a Url is a value href, action and src accept, and no other attribute name does") {
    assertEquals(
      a(Attrs.href := Url.Mounted("posts"), "next").render,
      """<a href="/posts">next</a>"""
    )
    assertEquals(
      form(Attrs.action := Url.Mounted("/posts"), "x").render,
      """<form action="/posts">x</form>"""
    )
    assertEquals(
      img(Attrs.src := Url.Absolute("https://eezo.io/logo.png")).render,
      """<img src="https://eezo.io/logo.png">"""
    )
    assert(compileErrors("""Attrs.cls := Url.Mounted("/posts")""").nonEmpty)
    assert(compileErrors("""Attrs.title := Url.Mounted("/posts")""").nonEmpty)
  }

  test("a url bearing attribute name still takes a String, an Int and a Boolean") {
    assertEquals(
      a(Attrs.href := "/next", Attrs.src := 4, Attrs.action := true).render,
      """<a href="/next" src="4" action></a>"""
    )
    assertEquals(
      form(Attrs.action := "/posts", img(Attrs.src := "/a.png")).render,
      """<form action="/posts"><img src="/a.png"></form>"""
    )
  }

  test("adjacent text children merge into one Text child in the tree, not only in the markup") {
    assertEquals(div("a", "b"), div("ab"))
    assertEquals(div("a", 4, "b"), div("a4b"))
  }

  test("text merges across a spliced fragment boundary") {
    assertEquals(div(span("s"), Html.text("x") ++ Html.text("y"), "z"), div(span("s"), "xyz"))
  }

  test("empty text children are dropped") {
    assertEquals(div("", span("s"), ""), div(span("s")))
    assertEquals(div(""), div())
  }

  test("merged text is escaped exactly as its pieces were") {
    assertEquals(div("a<b", "&c").render, "<div>a&lt;b&amp;c</div>")
  }

  test("escaped text cannot be forged from outside the html package") {
    assert(compileErrors("""Html.Text("<script>")""").nonEmpty)
  }

  test("UrlAttrName cannot be constructed from outside the html package") {
    assert(compileErrors("""UrlAttrName("class")""").nonEmpty)
  }

  test("an Attr and a Link cannot be built outside the html package") {
    assert(compileErrors("""Attr("data-to", Some(AttrValue.Literal("x")))""").nonEmpty)
    assert(compileErrors("""AttrValue.Link(Url.Mounted("/posts"))""").nonEmpty)
  }

  test("a tag refuses at compile time an Iterable of a type the Mod union does not name") {
    assert(compileErrors("div(List(true))").nonEmpty)
    assert(compileErrors("div(Vector(Some(\"a\")))").nonEmpty)
  }

  test("children that need no merging come out of a tag as they went in") {
    val children = Vector(span("a"), Html.text("b"), Html.raw("<i>c</i>"), span("d"))
    div(children) match {
      case Html.Element(_, _, _, out) => assertEquals(out, children)
      case other                      => fail(s"expected an element, got $other")
    }
  }
}
