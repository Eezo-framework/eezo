package io.eezo.http

import io.eezo.core.{Id, Store}

/** The in-memory half of `core`'s `Store[A]` seam.
  *
  * The suite pins the properties a derived handler depends on: `all()`'s promised order, which is
  * by primary key and not by insertion, because that is the only order a `select *` can be made to
  * agree with; one store per model, so no call site names a bucket; and the two writes that report
  * a missing row, which is where the derived 404 comes from.
  */
class InMemoryStoreSuite extends munit.FunSuite {

  case class Widget(id: Id[Widget], name: String)

  case class Gadget(id: Id[Gadget], name: String)

  /** A key whose text sorts where the caller says, so a test can state an expected order rather
    * than discover one. Real keys are `Id.gen()`, and the order is over exactly this text.
    */
  private def keyAt(nth: Int): Id[Widget] =
    Id.apply[Widget](java.util.UUID.fromString(f"$nth%08x-0000-4000-8000-000000000000"))

  private def widget(name: String): (Id[Widget], Widget) = {
    val key = Id.gen[Widget]()
    key -> Widget(key, name)
  }

  test("a store is a core Store, which is the seam db implements the other half of") {
    val store: Store[Widget] = InMemoryStore[Widget]()
    assertEquals(store.all(), Seq.empty[Widget])
  }

  test("all comes back ordered by primary key, not in the order rows were inserted") {
    val store = InMemoryStore[Widget]()
    store.insert(keyAt(3), Widget(keyAt(3), "third"))
    store.insert(keyAt(1), Widget(keyAt(1), "first"))
    store.insert(keyAt(2), Widget(keyAt(2), "second"))
    assertEquals(store.all().map(_.name), Seq("first", "second", "third"))
  }

  test("an empty store lists nothing rather than failing") {
    assertEquals(InMemoryStore[Widget]().all(), Seq.empty[Widget])
  }

  test("find locates a row by its key, and says nothing about a key never inserted") {
    val store      = InMemoryStore[Widget]()
    val (key, row) = widget("Bolt")
    store.insert(key, row)
    assertEquals(store.find(key), Some(row))
    assertEquals(store.find(Id.gen[Widget]()), None)
  }

  test("two models are two stores, so the same key in each holds its own row") {
    val widgets = InMemoryStore[Widget]()
    val gadgets = InMemoryStore[Gadget]()
    val shared  = Id.gen[Widget]()
    widgets.insert(shared, Widget(shared, "Bolt"))
    gadgets.insert(Id.apply[Gadget](shared.value), Gadget(Id.apply(shared.value), "Cog"))
    assertEquals(widgets.find(shared).map(_.name), Some("Bolt"))
    assertEquals(gadgets.all().map(_.name), Seq("Cog"))
  }

  test("update replaces the row and keeps its place in the promised order") {
    val store = InMemoryStore[Widget]()
    store.insert(keyAt(1), Widget(keyAt(1), "first"))
    store.insert(keyAt(2), Widget(keyAt(2), "second"))
    assert(store.update(keyAt(1), Widget(keyAt(1), "renamed")))
    assertEquals(store.all().map(_.name), Seq("renamed", "second"))
  }

  test("update reports false for a row that is not there, which is the derived 404") {
    val store      = InMemoryStore[Widget]()
    val (key, row) = widget("Bolt")
    assertEquals(store.update(key, row), false)
    assertEquals(store.all(), Seq.empty[Widget])
  }

  test("delete removes the row and reports what it did") {
    val store      = InMemoryStore[Widget]()
    val (key, row) = widget("Bolt")
    store.insert(key, row)
    assert(store.delete(key))
    assertEquals(store.delete(key), false)
    assertEquals(store.all(), Seq.empty[Widget])
  }

  test("two stores share nothing, which is what makes a test's table its own") {
    val one        = InMemoryStore[Widget]()
    val other      = InMemoryStore[Widget]()
    val (key, row) = widget("Bolt")
    one.insert(key, row)
    assertEquals(other.all(), Seq.empty[Widget])
  }

  test("concurrent inserts all land, and the store is not corrupted by them") {
    val store   = InMemoryStore[Widget]()
    val rows    = (1 to 200).map(n => widget(s"row-$n"))
    val threads = rows.map { case (key, row) =>
      Thread.ofVirtual().start(() => store.insert(key, row))
    }
    threads.foreach(_.join())
    assertEquals(store.all().size, 200)
    assertEquals(store.all().map(_.name).toSet, rows.map(_._2.name).toSet)
  }
}
