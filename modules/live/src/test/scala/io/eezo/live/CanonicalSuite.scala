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

  test("mixing keyed and unkeyed children is refused, whatever the unkeyed sibling is") {
    import io.eezo.core.html.Key
    refused(ul(li(Key("a"), "a"), li("b")), "mixes keyed and unkeyed")
    refused(ul(li(Key("a"), "a"), "loose text"), "mixes keyed and unkeyed")
    refused(ul(li(Key("a"), "a"), Html.raw("<li>raw</li>")), "mixes keyed and unkeyed")
  }

  test("a duplicate key is refused by value") {
    import io.eezo.core.html.Key
    refused(ul(li(Key("a"), "one"), li(Key("b"), "two"), li(Key("a"), "three")), "key 'a'")
  }

  test("an all-keyed list with unique keys passes") {
    import io.eezo.core.html.Key
    val tree = ul(li(Key("a"), "one"), li(Key("b"), "two"))
    assertEquals(Differ.diff(tree, tree), Nil)
  }

  test("nesting the parser would restructure is refused: block content inside a p") {
    refused(p(div("x")), "closes the open <p>")
    refused(p(em(section("deep"))), "closes the open <p>") // scope, not just the direct child
  }

  test("a heading directly inside a heading is refused; behind a container it is stable") {
    refused(h2(h3("x")), "heading")
    assertEquals(Differ.diff(h2(div(h3("x"))), h2(div(h3("x")))), Nil)
  }

  test("interactive and list auto-closers are refused: a in a, form in form, li in li") {
    refused(a(em(a("nested"))), "adoption")
    refused(form(fieldset(form())), "ignored outright")
    refused(li(div(li("x"))), "closes the <li>")
    // A list boundary resets the rule: nested lists are the ordinary case.
    assertEquals(Differ.diff(li(ul(li("x"))), li(ul(li("x")))), Nil)
  }

  test("table parts outside their chain are refused, including tr directly under table") {
    refused(div(td("stray")), "cells live under <tr>")
    refused(table(tr(td("x"))), "wraps them in a <tbody>")
    refused(table(div("stray")), "foster-parents")
    refused(tbody(tr(td("x"))), "cannot be a component's root")
    assertEquals(
      Differ.diff(table(tbody(tr(td("x")))), table(tbody(tr(td("x"))))),
      Nil
    )
  }

  test("text directly inside the table chain is refused: the parser hoists it out") {
    refused(table("stray", tbody(tr(td("x")))), "foster-parents")
  }

  test("the check reaches nested elements") {
    val bad  = Html.Element("span", Vector.empty, None, Vector(Html.text("a"), Html.text("b")))
    val tree = div(section(bad))
    refused(tree, "adjacent text")
  }
}
