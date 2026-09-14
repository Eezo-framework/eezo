package io.eezo.live

import io.eezo.core.html.Html
import io.eezo.core.html.Tags.*

/** The differ refuses what `Tag.apply` cannot have built, and says which rule was broken. Every
  * tree the DSL builds passes; every refusal here is a tree assembled by hand around the guard.
  */
class CanonicalSuite extends munit.FunSuite {

  private def refused(tree: Html, naming: String): Unit = {
    val thrown = intercept[NotCanonical](Differ.diff(tree, tree))
    assert(thrown.message.contains(naming), clues(thrown.message, naming))
  }

  test("everything the DSL builds is canonical") {
    val gen = new Gen(seed = 4L)
    for (_ <- 1 to 500) {
      val a = gen.tree(3)
      assertEquals(Differ.diff(a, a), Nil)
    }
  }

  test("a fragment root is refused: a component renders exactly one root element") {
    refused(span("a") ++ span("b"), "fragment")
    refused(Html.empty, "fragment")
  }

  test("a text or raw root is refused") {
    refused(Html.text("bare"), "one root element")
    refused(Html.raw("<b>markup</b>"), "one root element")
  }

  test("a hand-built fragment among children is refused") {
    val tree = Html.Element("div", Vector.empty, None, Vector(span("a") ++ span("b")))
    refused(tree, "fragment")
  }

  test("hand-built adjacent text children are refused") {
    val tree = Html.Element("div", Vector.empty, None, Vector(Html.text("a"), Html.text("b")))
    refused(tree, "adjacent text")
  }

  test("a hand-built empty text child is refused") {
    val tree = Html.Element("div", Vector.empty, None, Vector(Html.text("")))
    refused(tree, "empty text")
  }

  test("a void element with children is refused") {
    val tree = Html.Element("br", Vector.empty, None, Vector(Html.text("x")))
    refused(tree, "void")
  }

  test("the check reaches nested elements") {
    val bad  = Html.Element("span", Vector.empty, None, Vector(Html.text("a"), Html.text("b")))
    val tree = div(section(bad))
    refused(tree, "adjacent text")
  }
}
