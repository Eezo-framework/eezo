package io.eezo.live.harness

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import io.eezo.core.html.{Attrs, Html}
import io.eezo.core.html.Tags.*
import io.eezo.core.internal.Json
import io.eezo.live.{Differ, Gen, Wire}

/** Writes the round trip corpus the jsdom harness replays: `(old html, new html, patch frame)`
  * triples, from the same hand written cases `RoundTripSuite` pins and the same generator, so the
  * real `applier.js` is held to exactly what the reference applier already passed.
  *
  * Test scope on purpose: the corpus is a build artifact of the test suite, not of the library.
  * `research/harnesses/live-roundtrip/run.sh` is the caller.
  *
  * Usage: `live/Test/runMain io.eezo.live.harness.ExportCorpus <out.json> [count] [seed]`
  */
object ExportCorpus {

  private def handWritten: List[(String, Html, Html)] = {
    def counter(n: Int)          = div(Attrs.cls := "counter", span(n), button("+"))
    def item(s: String)          = li(io.eezo.core.html.Key(s), s)
    def list(items: Seq[String]) = ul(items.map(item))

    List(
      ("identical trees", div(span("same")), div(span("same"))),
      ("a counter's text change", counter(0), counter(1)),
      ("text needing every escape", p("safe"), p("""a<b & "c" it's""")),
      ("an attribute change", div(Attrs.cls := "a", "x"), div(Attrs.cls := "b", "x")),
      ("a raw child appearing", div(span("tail")), div(Html.raw("<b>x</b><i>y</i>"), span("tail"))),
      ("a keyed list reordered", list(Seq("a", "b", "c")), list(Seq("c", "a", "d"))),
      ("a void element gaining an attribute", div(input()), div(input(Attrs.value := ""))),
      ("nesting change", div(p("deep")), div(section(p("deep")))),
      (
        "input value and checked change",
        form(input(Attrs.tpe := "checkbox", Attrs.value := "v1", Attrs.checked := true)),
        form(input(Attrs.tpe := "checkbox", Attrs.value := "v2"))
      ),
      (
        "attribute order swap, a difference no browser can observe",
        div(Attrs.cls   := "a", Attrs.title := "t", "x"),
        div(Attrs.title := "t", Attrs.cls   := "a", "x")
      ),
      (
        "trailing removals and a deep text edit in one frame",
        ul(li("keep"), li("edit"), li("drop"), li("drop too")),
        ul(li("keep"), li("edited"))
      )
    )
  }

  def main(args: Array[String]): Unit = {
    val out   = Path.of(args(0))
    val count = if (args.length > 1) args(1).toInt else 1000
    val seed  = if (args.length > 2) args(2).toLong else 20260914L

    val gen = new Gen(seed)

    val generated = (1 to count).toList.map { index =>
      val a = gen.tree(3)
      val b = if (index % 3 == 0) gen.tree(3) else gen.mutate(a)
      (s"generated $index (seed $seed)", a, b)
    }

    val cases = (handWritten ++ generated).map { case (name, a, b) =>
      Json.Obj(
        List(
          "name"    -> Json.Str(name),
          "old"     -> Json.Str(a.render),
          "new"     -> Json.Str(b.render),
          "patches" -> Json.Str(Wire.patches(Differ.diff(a, b)))
        )
      )
    }

    Files.createDirectories(out.toAbsolutePath.getParent)
    Files.write(
      out,
      Json.render(Json.Obj(List("cases" -> Json.Arr(cases)))).getBytes(StandardCharsets.UTF_8)
    )
    println(s"wrote ${cases.length} cases to $out")
  }
}
