package io.eezo.http

import io.eezo.core.Id

/** The rows the derived seven read and write, for as long as `modules/db` has no runtime.
  *
  * The suite pins the four properties a derived handler depends on: insertion order, so `index`
  * renders stably; buckets not leaking into one another; the two writes that report a missing row,
  * which is where the derived 404 comes from; and `Id[A]` as the key, so no call site unwraps one.
  */
class StoreSuite extends munit.FunSuite {

  case class Widget(id: Id[Widget], name: String)

  case class Gadget(id: Id[Gadget], name: String)

  private def widget(name: String): (Id[Widget], Widget) = {
    val key = Id.gen[Widget]()
    key -> Widget(key, name)
  }

  test("list comes back in insertion order, not in key order") {
    val store = Store.inMemory()
    val rows  = Seq("first", "second", "third").map(widget)
    rows.foreach { case (key, row) => store.insert("widgets", key, row) }
    assertEquals(store.list[Widget]("widgets").map(_.name), Seq("first", "second", "third"))
  }

  test("an empty bucket lists nothing rather than failing") {
    assertEquals(Store.inMemory().list[Widget]("widgets"), Seq.empty[Widget])
  }

  test("get finds a row by its key, and says nothing about a key that was never inserted") {
    val store      = Store.inMemory()
    val (key, row) = widget("Bolt")
    store.insert("widgets", key, row)
    assertEquals(store.get[Widget]("widgets", key), Some(row))
    assertEquals(store.get[Widget]("widgets", Id.gen[Widget]()), None)
  }

  test("buckets are separate: the same key in two of them holds two rows") {
    val store  = Store.inMemory()
    val shared = Id.gen[Widget]()
    store.insert("widgets", shared, Widget(shared, "Bolt"))
    store.insert("gadgets", Id.apply[Gadget](shared.value), Gadget(Id.apply(shared.value), "Cog"))
    assertEquals(store.get[Widget]("widgets", shared).map(_.name), Some("Bolt"))
    assertEquals(store.list[Gadget]("gadgets").map(_.name), Seq("Cog"))
  }

  test("update replaces the row and keeps its place in the order") {
    val store               = Store.inMemory()
    val (firstKey, first)   = widget("first")
    val (secondKey, second) = widget("second")
    store.insert("widgets", firstKey, first)
    store.insert("widgets", secondKey, second)
    assert(store.update("widgets", firstKey, first.copy(name = "renamed")))
    assertEquals(store.list[Widget]("widgets").map(_.name), Seq("renamed", "second"))
  }

  test("update reports false for a row that is not there, which is the derived 404") {
    val store      = Store.inMemory()
    val (key, row) = widget("Bolt")
    assertEquals(store.update("widgets", key, row), false)
    assertEquals(store.list[Widget]("widgets"), Seq.empty[Widget])
  }

  test("delete removes the row and reports what it did") {
    val store      = Store.inMemory()
    val (key, row) = widget("Bolt")
    store.insert("widgets", key, row)
    assert(store.delete[Widget]("widgets", key))
    assertEquals(store.delete[Widget]("widgets", key), false)
    assertEquals(store.list[Widget]("widgets"), Seq.empty[Widget])
  }

  test("two stores share nothing, which is what makes a test's table its own") {
    val one        = Store.inMemory()
    val other      = Store.inMemory()
    val (key, row) = widget("Bolt")
    one.insert("widgets", key, row)
    assertEquals(other.list[Widget]("widgets"), Seq.empty[Widget])
  }

  test("concurrent inserts all land, and the bucket is not corrupted by them") {
    val store   = Store.inMemory()
    val rows    = (1 to 200).map(n => widget(s"row-$n"))
    val threads = rows.map { case (key, row) =>
      Thread.ofVirtual().start(() => store.insert("widgets", key, row))
    }
    threads.foreach(_.join())
    assertEquals(store.list[Widget]("widgets").size, 200)
    assertEquals(store.list[Widget]("widgets").map(_.name).toSet, rows.map(_._2.name).toSet)
  }
}
