package io.eezo.sbt.dev

import Ansi._

class AnsiSuite extends munit.FunSuite {

  implicit val color: Theme = Theme(color = true, unicode = true)

  test("width ignores escape sequences and counts the glyphs the console uses as one column") {
    assertEquals(width("plain"), 5)
    assertEquals(width(Red("red")), 3)
    assertEquals(width(Bold(Dim("ab")) + "c"), 3)
    assertEquals(width("✓ ✗ ⚠ ⠋ ─ …"), 11)
    assertEquals(width(""), 0)
  }

  test("a plain style or colour off leaves text untouched") {
    assertEquals(Plain("x"), "x")
    assertEquals(Red.render("x", Theme(color = false, unicode = true)), "x")
    assertEquals(Red(""), "")
  }

  test("truncate keeps escapes intact and closes an open style at the cut") {
    assertEquals(truncate("abcdef", 3), "abc")
    assertEquals(truncate("abc", 5), "abc")
    assertEquals(truncate("ab", 0), "")
    val cut = truncate(Red("abcdef") + "g", 3)
    assertEquals(width(cut), 3)
    assert(cut.endsWith("\u001b[0m"), cut)
    assert(cut.startsWith("\u001b[31mabc"), cut)
  }

  test("padding and spreading fill to exactly the width") {
    assertEquals(padRight("ab", 4), "ab  ")
    assertEquals(padLeft("ab", 4), "  ab")
    assertEquals(padRight("abcdef", 4), "abcd")
    assertEquals(spread("left", "right", 12), "left   right")
    assertEquals(width(spread(Bold("left"), Green("right"), 12)), 12)
    assertEquals(spread("a long left side", "right", 10), "a lo right")
    assertEquals(spread("left", "a right side too long", 6), "a righ")
  }

  test("shorten keeps both ends of a path") {
    assertEquals(shorten("/books/:id/edit", 100, "…"), "/books/:id/edit")
    assertEquals(shorten("/books/:id/edit", 9, "…"), "/boo…edit")
    assertEquals(shorten("abcdef", 1, "…"), "a")
  }

  test("wrap breaks at spaces and hard breaks a long word") {
    assertEquals(wrap("the quick brown fox", 9), Vector("the quick", "brown fox"))
    assertEquals(wrap("abcdefghij", 4), Vector("abcd", "efgh", "ij"))
    assertEquals(wrap("short", 10), Vector("short"))
    assertEquals(wrap("", 10), Vector(""))
  }

  test("the theme reads NO_COLOR and the encoding") {
    val env = Map("NO_COLOR" -> "1")
    assertEquals(
      Theme.detect(colorEnabled = true, env.get, "UTF-8"),
      Theme(color = false, unicode = true)
    )
    assertEquals(
      Theme.detect(colorEnabled = true, Map.empty[String, String].get, "ISO-8859-1"),
      Theme(color = true, unicode = false)
    )
    assertEquals(
      Theme.detect(colorEnabled = false, Map.empty[String, String].get, "utf8"),
      Theme(color = false, unicode = true)
    )
    assertEquals(Theme(color = false, unicode = false).ok, "ok")
    assertEquals(Theme(color = false, unicode = false).spinner(5).length, 1)
  }
}
