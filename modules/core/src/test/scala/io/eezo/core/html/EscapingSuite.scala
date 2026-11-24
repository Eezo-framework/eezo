package io.eezo.core.html

class EscapingSuite extends munit.FunSuite {

  test("text escapes all five characters, in one pass") {
    val node = Html.text("""<a href="x">Tom & Jerry's</a>""")
    assertEquals(
      node.render,
      "&lt;a href=&quot;x&quot;&gt;Tom &amp; Jerry&#39;s&lt;/a&gt;"
    )
  }

  test("text leaves ordinary prose alone") {
    assertEquals(Html.text("hello").render, "hello")
  }

  test("raw is the single unescaped path") {
    assertEquals(Html.raw("<b>bold</b>").render, "<b>bold</b>")
  }

  test("unescape inverts escape, entity-looking input included") {
    val hostile = List(
      """<a href="x">Tom & Jerry's</a>""",
      "&lt;",
      "&amp;lt;",
      "a&&b",
      "plain",
      ""
    )
    hostile.foreach { s =>
      assertEquals(Html.unescape(Html.escape(s)), s, clues(Html.escape(s)))
    }
  }

  test("text and an attribute value escape the same five characters, the same way") {
    val five = """<>&"'"""
    assertEquals(
      Tags.p(Attrs.title := five, five).render,
      """<p title="&lt;&gt;&amp;&quot;&#39;">&lt;&gt;&amp;&quot;&#39;</p>"""
    )
  }
}
