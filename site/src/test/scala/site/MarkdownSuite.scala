package site

import munit.FunSuite

class MarkdownSuite extends FunSuite {

  private def html(markdown: String, link: String => String = identity): String =
    Markdown.render(Markdown.parse(markdown), link).render

  test("a heading gets an id from its text, and its anchor link") {
    val out = html("## The dev loop\n")
    assert(out.contains("""<h2 id="the-dev-loop">"""), out)
    assert(out.contains("""href="#the-dev-loop""""), out)
  }

  test("a repeated heading is numbered from its second occurrence") {
    val doc = Markdown.parse("## Setup\n\ntext\n\n## Setup\n\n## Setup\n")
    assertEquals(doc.headings.map(_.id), Vector("setup", "setup-1", "setup-2"))
  }

  test("code spans in a heading are part of its text and its id") {
    val doc = Markdown.parse("# `eezo deploy`, from nothing\n")
    assertEquals(doc.title, Some("eezo deploy, from nothing"))
    assertEquals(doc.headings.head.id, "eezo-deploy-from-nothing")
  }

  test("the description is the first paragraph, clipped on a word") {
    val doc         = Markdown.parse("# Title\n\n" + ("word " * 60).trim + "\n")
    val description = doc.description.getOrElse(fail("no description"))
    assert(description.length <= 161, description)
    assert(description.endsWith("…"), description)
    assert(description.stripSuffix("…").split(" ").forall(_ == "word"), description)
  }

  test("a fenced block is highlighted and its markup escaped") {
    val out = html("```scala\nval x = \"<b>\"\n```\n")
    assert(out.contains("""class="codeblock" data-lang="scala""""), out)
    assert(out.contains("""<span class="tk-kw">val</span>"""), out)
    assert(out.contains("&lt;b&gt;"), out)
    assert(!out.contains("<b>"), out)
  }

  test("a table renders with a head, a body and its alignment") {
    val out = html("| a | b |\n|---|--:|\n| 1 | 2 |\n")
    assert(out.contains("<thead><tr><th>a</th>"), out)
    assert(out.contains("""<td style="text-align:right">2</td>"""), out)
    assert(out.contains("""class="table-scroll""""), out)
  }

  test("a tight list has no paragraphs, a loose one does") {
    assert(!html("- one\n- two\n").contains("<p>"))
    assert(html("- one\n\n- two\n").contains("<li><p>one</p></li>"))
  }

  test("links go through the resolver and external ones say so") {
    val out =
      html("[a](other.md) [b](https://example.com)", d => if (d == "other.md") "/docs/other" else d)
    assert(out.contains("""<a href="/docs/other">a</a>"""), out)
    assert(out.contains("""<a href="https://example.com" rel="noopener">b</a>"""), out)
  }

  test("an angle bracket autolink is a link") {
    val out = html("see <http://localhost:8090>")
    assert(
      out.contains("""<a href="http://localhost:8090" rel="noopener">http://localhost:8090</a>"""),
      out
    )
  }

  test("inline html passes through") {
    val out = html("a <kbd>Ctrl</kbd> b")
    assert(out.contains("<kbd>Ctrl</kbd>"), out)
  }

  test("an ordered list keeps a start other than one") {
    assert(html("3. c\n4. d\n").contains("""<ol start="3">"""))
    assert(!html("1. a\n2. b\n").contains("start="))
  }
}
