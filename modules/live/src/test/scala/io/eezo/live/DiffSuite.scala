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

  test("a key change is an identity change: the old row leaves, the new row arrives") {
    Differ.diff(ul(li(Key("a"), "x")), ul(li(Key("b"), "x"))) match {
      case List(Patch.RemoveNode(List(0, 0), "li"), Patch.InsertChild(List(0), "ul", 0, node)) =>
        assertEquals(node.render, """<li data-eezo-key="b">x</li>""")
      case other => fail(s"unexpected patches: $other")
    }
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

  // ---- keyed lists (M5) ----

  private def keyedList(keys: String*): Html = ul(keys.map(k => li(Key(k), k)))

  test("moving one row is one MoveChild, not a rewrite of every shifted sibling") {
    assertEquals(
      Differ.diff(keyedList("a", "b", "c", "d", "e"), keyedList("e", "a", "b", "c", "d")),
      List(Patch.MoveChild(List(0), "ul", 4, 0))
    )
  }

  test("a swap of two adjacent rows is one move") {
    assertEquals(
      Differ.diff(keyedList("a", "b", "c"), keyedList("a", "c", "b")).length,
      1
    )
  }

  test("prepending one row to a large list is one InsertChild: the O(1) promise") {
    val big       = (1 to 1000).map(i => s"k$i")
    val prepended = "fresh" +: big
    Differ.diff(keyedList(big*), keyedList(prepended*)) match {
      case List(Patch.InsertChild(List(0), "ul", 0, node)) =>
        assertEquals(node.render, """<li data-eezo-key="fresh">fresh</li>""")
      case other => fail(s"expected one insert, got ${other.length} patches: ${other.take(3)}")
    }
  }

  test("removing a middle row is one RemoveNode at its old index") {
    assertEquals(
      Differ.diff(keyedList("a", "b", "c"), keyedList("a", "c")),
      List(Patch.RemoveNode(List(0, 1), "li"))
    )
  }

  test("structure comes before content, and content is addressed at final positions") {
    val before  = ul(li(Key("a"), "a"), li(Key("b"), "old text"))
    val after   = ul(li(Key("b"), "new text"), li(Key("a"), "a"))
    val patches = Differ.diff(before, after)
    assertEquals(
      patches,
      List(
        Patch.MoveChild(List(0), "ul", 1, 0),
        // b now sits at final index 0, and that is where its text edit points.
        Patch.SetText(List(0, 0, 0), "new text")
      )
    )
  }

  test("remove, move, insert and edit compose in one frame and round trip") {
    val before = keyedList("a", "b", "c", "d")
    val after  = ul(li(Key("d"), "d"), li(Key("new"), "new"), li(Key("b"), "B!"), li(Key("a"), "a"))
    val patches = Differ.diff(before, after)
    val applied = RefApplier(Vector(Canonical.root(before)), patches)
    assert(DomEqual.all(applied, Vector(after)), clues(patches, applied.map(_.render)))
  }

  // ---- data-eezo-ignore (M5) ----

  test("children under an ignored element are never patched; its own attributes still are") {
    def widget(cls: String, inner: String) =
      div(section(Live.ignore, io.eezo.core.html.Attrs.cls := cls, p(inner)))

    assertEquals(
      Differ.diff(widget("a", "x"), widget("b", "COMPLETELY DIFFERENT")),
      List(Patch.SetAttr(List(0, 0), "section", "class", "b"))
    )
    assertEquals(Differ.diff(widget("a", "x"), widget("a", "changed")), Nil)
  }
}
