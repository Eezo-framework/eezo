package io.eezo.core.html

import io.eezo.core.html.Html.*

/** What the four cases render to. Elements are built here by hand, so this suite says nothing about
  * the DSL: [[DslSuite]] owns that.
  */
class RenderingSuite extends munit.FunSuite {

  test("an element renders its attributes in insertion order") {
    val node = Element(
      "a",
      Vector(Attr("href", Some("/posts")), Attr("class", Some("link"))),
      key = None,
      children = Vector(Html.text("posts"))
    )
    assertEquals(node.render, """<a href="/posts" class="link">posts</a>""")
  }

  test("an attribute with no value renders bare, and an empty value renders empty") {
    val bare  = Element("input", Vector(Attr("disabled", None)), None, Vector.empty)
    val empty = Element("input", Vector(Attr("value", Some(""))), None, Vector.empty)
    assertEquals(bare.render, "<input disabled>")
    assertEquals(empty.render, """<input value="">""")
  }

  test("an attribute value is escaped") {
    val node =
      Element("a", Vector(Attr("title", Some("""a "quote" & <tag>"""))), None, Vector.empty)
    assertEquals(node.render, """<a title="a &quot;quote&quot; &amp; &lt;tag&gt;"></a>""")
  }

  test("a void tag renders without a closing tag and without the XHTML slash") {
    assertEquals(Element("br", Vector.empty, None, Vector.empty).render, "<br>")
    assertEquals(
      Element("img", Vector(Attr("src", Some("/a.png"))), None, Vector.empty).render,
      """<img src="/a.png">"""
    )
  }

  test("a void tag ignores children rather than closing itself around them") {
    assertEquals(Element("br", Vector.empty, None, Vector(Html.text("x"))).render, "<br>")
  }

  test("key renders as data-eezo-key, after the attributes") {
    val node = Element("li", Vector(Attr("class", Some("row"))), Some("7"), Vector(Html.text("a")))
    assertEquals(node.render, """<li class="row" data-eezo-key="7">a</li>""")
  }

  test("a fragment renders its children and nothing of its own") {
    val node = Fragment(Vector(Html.text("a"), Html.text("b")))
    assertEquals(node.render, "ab")
  }

  test("empty renders to nothing and doctype to the declaration") {
    assertEquals(Html.empty.render, "")
    assertEquals(Html.doctype.render, "<!DOCTYPE html>")
  }

  test("++ flattens adjacent fragments rather than nesting them") {
    val joined = (Html.text("a") ++ Html.text("b")) ++ Html.text("c")
    assertEquals(joined, Fragment(Vector(Html.text("a"), Html.text("b"), Html.text("c"))))
    assertEquals(joined.render, "abc")
  }

  test("when renders its body only when the condition holds") {
    assertEquals(Html.when(true)(Html.text("yes")).render, "yes")
    assertEquals(Html.when(false)(Html.text("yes")).render, "")
  }
}
