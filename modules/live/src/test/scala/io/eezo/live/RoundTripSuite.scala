package io.eezo.live

import io.eezo.core.html.{Attrs, Html}
import io.eezo.core.html.Tags.*

/** The invariant, against the reference applier: for any two renders `a` and `b`,
  * `applyRef(a, diff(a, b))` renders what `b` renders. The jsdom harness holds the same corpus
  * against the real client JS; this suite is the fast inner loop that runs on every `sbt test`.
  */
class RoundTripSuite extends munit.FunSuite {

  private def roundTrip(a: Html, b: Html): Unit = {
    val after = RefApplier(Vector(a), Differ.diff(a, b))
    assertEquals(
      after.map(_.render).mkString,
      b.render,
      clues(a.render, b.render)
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

  test("a raw child round trips") {
    roundTrip(div(Html.raw("<b>x</b><i>y</i>"), span("tail")), div(span("tail")))
  }

  test("a keyed list round trips") {
    def item(s: String)          = li(io.eezo.core.html.Key(s), s)
    def list(items: Seq[String]) = ul(items.map(item))
    roundTrip(list(Seq("a", "b", "c")), list(Seq("c", "a", "d")))
  }
}
