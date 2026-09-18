package io.eezo.live

import io.eezo.core.html.{Attrs, Html, Key}
import io.eezo.core.html.Tags.*

/** The invariant, against the reference applier: for any two renders `a` and `b`,
  * `applyRef(a, diff(a, b))` is DOM-equal to `b`. The jsdom harness holds the same corpus against
  * the real client JS; this suite is the fast inner loop that runs on every `sbt test`.
  * [[DomEqual]] rather than render equality, because the differ follows the DOM in ignoring
  * attribute order and the bare-vs-empty distinction; `DiffSuite` is where patch shapes are pinned
  * exactly.
  */
class RoundTripSuite extends munit.FunSuite {

  private def roundTrip(a: Html, b: Html): Unit = {
    val after = RefApplier(Vector(a), Differ.diff(a, b))
    assert(
      DomEqual.all(after, Vector(b)),
      clues(a.render, b.render, after.map(_.render).mkString)
    )
  }

  test("equal trees diff to no patches at all") {
    val gen = new Gen(seed = 1L)
    for (_ <- 1 to 200) {
      val a = gen.tree(3)
      assertEquals(Differ.diff(a, a), Nil, clues(a.render))
    }
  }

  test("the round trip holds over generated mutations") {
    val gen = new Gen(seed = 2L)
    for (_ <- 1 to 1000) {
      val a = gen.tree(3)
      roundTrip(a, gen.mutate(a))
    }
  }

  test("the round trip holds between unrelated trees") {
    val gen = new Gen(seed = 3L)
    for (_ <- 1 to 300) {
      roundTrip(gen.tree(3), gen.tree(3))
    }
  }

  test("a counter's text change round trips") {
    def counter(n: Int) = div(Attrs.cls := "counter", span(n), button("+"))
    roundTrip(counter(0), counter(1))
  }

  test("text needing escapes round trips escaped") {
    roundTrip(p("safe"), p("""a<b & "c" it's"""))
  }

  test("attribute add, change and removal round trip") {
    roundTrip(div(Attrs.cls := "a", "x"), div(Attrs.cls := "b", Attrs.title := "t", "x"))
    roundTrip(div(Attrs.cls := "a", Attrs.title := "t"), div())
  }

  test("a bare attribute and input value round trip") {
    roundTrip(
      input(Attrs.tpe := "checkbox", Attrs.value   := "v1"),
      input(Attrs.tpe := "checkbox", Attrs.checked := true, Attrs.value := "v2")
    )
  }

  test("an attribute order swap is already DOM-equal and diffs to nothing") {
    val a = div(Attrs.cls := "a", Attrs.title := "t")
    val b = div(Attrs.title := "t", Attrs.cls := "a")
    assertEquals(Differ.diff(a, b), Nil)
    roundTrip(a, b)
  }

  test("a raw child round trips") {
    roundTrip(div(Html.raw("<b>x</b><i>y</i>"), span("tail")), div(span("tail")))
  }

  test("children appended, removed and replaced round trip") {
    roundTrip(ul(li("a")), ul(li("a"), li("b"), li("c")))
    roundTrip(ul(li("a"), li("b"), li("c")), ul(li("a")))
    roundTrip(div(span("x")), div(em("x")))
  }

  test("a node kind change round trips") {
    roundTrip(div("text", span("el")), div(span("el"), "text"))
  }

  test("a keyed list round trips") {
    def item(s: String)          = li(Key(s), s)
    def list(items: Seq[String]) = ul(items.map(item))
    roundTrip(list(Seq("a", "b", "c")), list(Seq("c", "a", "d")))
    roundTrip(list(Seq("a")), list(Seq("b", "a")))
  }

  test("a deep nested change round trips") {
    def page(status: String) =
      div(header(nav(a(Attrs.href := "/", "home"))), main(section(p("status: ", span(status)))))
    roundTrip(page("draft"), page("published"))
  }
}
