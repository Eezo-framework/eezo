package io.eezo.db

import io.eezo.core.{Id, Store}
import io.eezo.db.support.{DbSuite, Library, PublishingHouse}

/** `core`'s `Store[A]` as db implements it, against a real Postgres.
  *
  * The suite is written against the `Store[A]` type rather than against the implementation, because
  * everything the derived seven can see is that trait: a test that reached for a db-only member
  * would be testing something `Resource` cannot call.
  */
class JdbcStoreSuite extends DbSuite {

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    create(Library)
  }

  private def store: Store[PublishingHouse] = JdbcStore[PublishingHouse]()

  private def house(name: String) =
    PublishingHouse(Id.gen[PublishingHouse](), name, "London")

  test("insert then find returns the row") {
    val h = house("Faber")
    store.insert(h.id, h)
    assertEquals(store.find(h.id), Some(h))
  }

  test("all returns every row ordered by primary key") {
    val rows = List.fill(5)(house("Faber"))
    rows.foreach(h => store.insert(h.id, h))
    assertEquals(store.all(), rows.sortBy(_.id.show))
  }

  test("update replaces the row and reports it was there") {
    val h = house("Faber")
    store.insert(h.id, h)
    val moved = h.copy(name = "Faber & Faber", location = "Bloomsbury")
    assert(store.update(h.id, moved))
    assertEquals(store.find(h.id), Some(moved))
  }

  test("update reports false for a row that is not there") {
    val h = house("Faber")
    assertEquals(store.update(h.id, h), false)
  }

  test("delete removes the row and reports it was there") {
    val h = house("Faber")
    store.insert(h.id, h)
    assert(store.delete(h.id))
    assertEquals(store.find(h.id), None)
  }

  test("delete reports false for a row that is not there") {
    assertEquals(store.delete(Id.gen[PublishingHouse]()), false)
  }

  test("find reports nothing for an id that is not there") {
    assertEquals(store.find(Id.gen[PublishingHouse]()), None)
  }
}
