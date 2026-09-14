package io.eezo.live

import io.eezo.core.html.{Attrs, Html, Key}
import io.eezo.core.html.Tags.*

/** The differ's output, pinned patch by patch: which op, which path, in which order. The round trip
  * suites prove the patches are *correct*; this one proves they are the *fine-grained* ones the
  * design promises (a text edit, not a subtree replace), because a differ that answered every case
  * with `SetChildren` at the root would pass every round trip and destroy focus, caret and scroll
  * on every event.
  */
class DiffSuite extends munit.FunSuite {

  test("a pure text change is one SetText, unescaped, addressed through the tree") {
    def counter(n: String) = div(Attrs.cls := "c", span(n), button("+"))
    assertEquals(
      Differ.diff(counter("0"), counter("""1 < 2 & "so"""")),
      // Root at [0], span at [0,0], its text at [0,0,0].
      List(Patch.SetText(List(0, 0, 0), """1 < 2 & "so""""))
    )
  }

  test("an attribute change is one SetAttr on the element, children untouched") {
    assertEquals(
      Differ.diff(div(Attrs.cls := "a", span("x")), div(Attrs.cls := "b", span("x"))),
      List(Patch.SetAttr(List(0), "div", "class", "b"))
    )
  }

  test("attribute removal, and a bare attribute travelling as the empty string") {
    assertEquals(
      Differ.diff(input(Attrs.tpe := "text", Attrs.disabled := true), input(Attrs.tpe := "text")),
      List(Patch.RemoveAttr(List(0), "input", "disabled"))
    )
    assertEquals(
      Differ.diff(input(Attrs.tpe := "text"), input(Attrs.tpe := "text", Attrs.disabled := true)),
      List(Patch.SetAttr(List(0), "input", "disabled", ""))
    )
  }

  test("a link attribute diffs by the url it will render") {
    assertEquals(
      Differ.diff(a(Attrs.href := "/old"), a(Attrs.href := "/new")),
      List(Patch.SetAttr(List(0), "a", "href", "/new"))
    )
  }

  test("appended children are one AppendChildren, not one patch per node") {
    Differ.diff(ul(li("a")), ul(li("a"), li("b"), li("c"))) match {
      case List(Patch.AppendChildren(List(0), "ul", children)) =>
        assertEquals(children.map(_.render), Vector("<li>b</li>", "<li>c</li>"))
      case other => fail(s"unexpected patches: $other")
    }
  }

  test("trailing removals come highest index first, so each path is true at its turn") {
    assertEquals(
      Differ.diff(ul(li("a"), li("b"), li("c")), ul(li("a"))),
      List(Patch.RemoveNode(List(0, 2), "li"), Patch.RemoveNode(List(0, 1), "li"))
    )
  }

  test("a changed tag is a ReplaceNode carrying the old name as the integrity check") {
    Differ.diff(div(span("x")), div(em("x"))) match {
      case List(Patch.ReplaceNode(List(0, 0), "span", node)) =>
        assertEquals(node.render, "<em>x</em>")
      case other => fail(s"unexpected patches: $other")
    }
  }

  test("a node kind change is a ReplaceNode expecting #text") {
    Differ.diff(div("was text"), div(span("now element"))) match {
      case List(Patch.ReplaceNode(List(0, 0), "#text", _)) => ()
      case other                                           => fail(s"unexpected patches: $other")
    }
  }

  test("any change beside a raw child collapses to SetChildren on the parent") {
    val before = div(Html.raw("<b>x</b><i>y</i>"), span("a"))
    val after  = div(Html.raw("<b>x</b><i>y</i>"), span("b"))
    Differ.diff(before, after) match {
      case List(Patch.SetChildren(List(0), Some("div"), _)) => ()
      case other                                            => fail(s"unexpected patches: $other")
    }
  }

  test("an unchanged raw child costs nothing when only the parent's attributes move") {
    assertEquals(
      Differ.diff(
        div(Attrs.cls := "a", Html.raw("<b>x</b>"), span("s")),
        div(Attrs.cls := "b", Html.raw("<b>x</b>"), span("s"))
      ),
      List(Patch.SetAttr(List(0), "div", "class", "b"))
    )
  }

  test("a key change travels as the attribute it renders as, until M5 makes keys identity") {
    assertEquals(
      Differ.diff(ul(li(Key("a"), "x")), ul(li(Key("b"), "x"))),
      List(Patch.SetAttr(List(0, 0), "li", "data-eezo-key", "b"))
    )
  }

  test("updates precede structural changes in one child list") {
    val patches = Differ.diff(
      ul(li("keep"), li("edit"), li("drop")),
      ul(li("keep"), li("edited"))
    )
    assertEquals(
      patches,
      List(Patch.SetText(List(0, 1, 0), "edited"), Patch.RemoveNode(List(0, 2), "li"))
    )
  }

  test("the root's own tag change replaces the root, still through its path") {
    Differ.diff(div("x"), section("x")) match {
      case List(Patch.ReplaceNode(List(0), "div", _)) => ()
      case other                                      => fail(s"unexpected patches: $other")
    }
  }
}
