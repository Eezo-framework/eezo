package io.eezo.core

import java.util.UUID

class IdSuite extends munit.FunSuite {

  case class Widget()

  test("an Id is its UUID, both ways") {
    val u = UUID.fromString("11111111-2222-3333-4444-555555555555")
    assertEquals(Id[Widget](u).value, u)
  }

  test("gen is a fresh UUID every call") {
    assertNotEquals(Id.gen[Widget](), Id.gen[Widget]())
  }

  test("show is the UUID's own text, so it reads back") {
    val id = Id.gen[Widget]()
    assertEquals(id.show, id.value.toString)
    assertEquals(Id[Widget](UUID.fromString(id.show)), id)
  }
}
