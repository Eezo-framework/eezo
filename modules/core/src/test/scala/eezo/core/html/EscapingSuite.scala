package eezo.core.html

/** Escaping happens once, at construction, and `Html.text` is the only door into a `Text` node. */
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
}
