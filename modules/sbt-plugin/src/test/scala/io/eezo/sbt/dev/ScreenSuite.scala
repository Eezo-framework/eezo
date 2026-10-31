package io.eezo.sbt.dev

class ScreenSuite extends munit.FunSuite {

  private def rig(): (Screen, () => Vector[String]) = {
    val writes = Vector.newBuilder[String]
    val screen = new Screen(s => writes += s)
    (screen, () => writes.result())
  }

  test("the first frame writes every row by absolute position, with no newline anywhere") {
    val (screen, writes) = rig()
    screen.draw(Vector("one", "two"), 10, 2)
    val out = writes().mkString
    assert(out.contains("\u001b[1;1Hone\u001b[K"), out)
    assert(out.contains("\u001b[2;1Htwo\u001b[K"), out)
    assert(!out.contains("\n"))
  }

  test("an unchanged frame writes nothing, a changed row writes only that row") {
    val (screen, writes) = rig()
    screen.draw(Vector("one", "two"), 10, 2)
    val before = writes().size
    screen.draw(Vector("one", "two"), 10, 2)
    assertEquals(writes().size, before)
    screen.draw(Vector("one", "TWO"), 10, 2)
    val last = writes().last
    assert(last.contains("\u001b[2;1HTWO"), last)
    assert(!last.contains("one"), last)
  }

  test("a resize clears and redraws everything") {
    val (screen, writes) = rig()
    screen.draw(Vector("one", "two"), 10, 2)
    screen.draw(Vector("one", "two"), 12, 2)
    val last = writes().last
    assert(last.contains(Screen.ClearAll), last)
    assert(last.contains("one") && last.contains("two"), last)
  }

  test("enter and leave bracket the alternate screen and the cursor") {
    val (screen, writes) = rig()
    screen.enter()
    screen.leave()
    assertEquals(writes().head.take(Screen.EnterAlternate.length), Screen.EnterAlternate)
    assert(writes().head.contains(Screen.HideCursor))
    assertEquals(writes().last, Screen.ShowCursor + Screen.LeaveAlternate)
  }
}
