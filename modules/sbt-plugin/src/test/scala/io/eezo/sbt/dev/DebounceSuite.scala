package io.eezo.sbt.dev

class DebounceSuite extends munit.FunSuite {

  test("events within the quiet window are released together, once it closes") {
    var now = 1000L
    val d   = new Debounce[String](quietMillis = 100, clock = () => now)
    d.offer("A.scala")
    now += 50
    d.offer("B.scala")
    now += 50
    assertEquals(d.due(), None)
    now += 60
    assertEquals(d.due(), Some(Set("A.scala", "B.scala")))
    assertEquals(d.due(), None)
    assert(!d.pending)
  }

  test("nothing offered, nothing due") {
    val d = new Debounce[String](quietMillis = 100, clock = () => 0L)
    assertEquals(d.due(), None)
  }
}
