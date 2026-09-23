package io.eezo.db

import io.eezo.db.Scopes.*
import io.eezo.db.support.{DbSuite, Library, PublishingHouse}
import io.eezo.core.Id

/** CRUD by primary key, against a real Postgres.
  *
  * `PublishingHouse` rather than `Book`: it has no foreign keys, so a round trip is about the CRUD
  * path and not about fixture ordering.
  */
class CrudSuite extends DbSuite {

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    create(Library)
  }

  private def house(name: String) =
    PublishingHouse(Id.gen[PublishingHouse](), name, "London")

  test("insert then findById returns the row") {
    val h = house("Faber")
    transact { Table[PublishingHouse].insert(h) }
    assertEquals(read { Table[PublishingHouse].findById(h.id) }, Some(h))
  }

  test("findById returns None for an id that is not there") {
    assertEquals(read { Table[PublishingHouse].findById(Id.gen[PublishingHouse]()) }, None)
  }

  test("update writes every column and keeps the key") {
    val h = house("Faber")
    transact { Table[PublishingHouse].insert(h) }
    val moved = h.copy(name = "Faber & Faber", location = "Bloomsbury")
    assertEquals(transact { Table[PublishingHouse].update(moved) }, 1)
    assertEquals(read { Table[PublishingHouse].findById(h.id) }, Some(moved))
  }

  test("updateById matches on the id it is given, not the one inside the row") {
    val h = house("Faber")
    transact { Table[PublishingHouse].insert(h) }
    val moved   = h.copy(name = "Faber & Faber")
    val matched = transact { Table[PublishingHouse].updateById(Id.gen[PublishingHouse](), moved) }
    assertEquals(matched, 0)
    assertEquals(read { Table[PublishingHouse].findById(h.id) }, Some(h))
  }

  test("delete removes the row") {
    val h = house("Faber")
    transact { Table[PublishingHouse].insert(h) }
    assertEquals(transact { Table[PublishingHouse].delete(h.id) }, 1)
    assertEquals(read { Table[PublishingHouse].findById(h.id) }, None)
  }

  test("all returns every row") {
    val hs = List(house("Faber"), house("Verso"), house("Fitzcarraldo"))
    transact { hs.foreach(Table[PublishingHouse].insert) }
    assertEquals(read { Table[PublishingHouse].all() }.toSet, hs.toSet)
  }

  test("updating a row that is gone matches no row, rather than reporting success") {
    val h = house("Faber")
    transact { Table[PublishingHouse].insert(h) }
    transact { Table[PublishingHouse].delete(h.id) }

    assertEquals(transact { Table[PublishingHouse].update(h.copy(name = "renamed")) }, 0)
    assertEquals(read { Table[PublishingHouse].findById(h.id) }, None)
  }

  test("deleting a row that is gone matches no row too") {
    assertEquals(transact { Table[PublishingHouse].delete(Id.gen[PublishingHouse]()) }, 0)
  }

  test("a write that matches no row leaves its transaction usable, and the rest commits") {
    val h       = house("Faber")
    val matched = transact {
      val updated = Table[PublishingHouse].updateById(Id.gen[PublishingHouse](), h)
      val deleted = Table[PublishingHouse].delete(Id.gen[PublishingHouse]())
      Table[PublishingHouse].insert(h)
      (updated, deleted)
    }
    assertEquals(matched, (0, 0))
    assertEquals(read { Table[PublishingHouse].findById(h.id) }, Some(h))
  }

  test("a write and the read that checks it share one transaction") {
    val h    = house("Faber")
    val seen = transact {
      Table[PublishingHouse].insert(h)
      Table[PublishingHouse].findById(h.id) // Tx <: DB: the read joins, it does not open a scope
    }
    assertEquals(seen, Some(h))
  }

  test("a failed transaction leaves nothing behind") {
    val h = house("Faber")
    intercept[RuntimeException] {
      transact {
        Table[PublishingHouse].insert(h)
        throw new RuntimeException("boom")
      }
    }
    assertEquals(read { Table[PublishingHouse].all() }, Nil)
  }
}
