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
    transact { Table[PublishingHouse].update(moved) }
    assertEquals(read { Table[PublishingHouse].findById(h.id) }, Some(moved))
  }

  test("delete removes the row") {
    val h = house("Faber")
    transact { Table[PublishingHouse].insert(h) }
    transact { Table[PublishingHouse].delete(h.id) }
    assertEquals(read { Table[PublishingHouse].findById(h.id) }, None)
  }

  test("all returns every row") {
    val hs = List(house("Faber"), house("Verso"), house("Fitzcarraldo"))
    transact { hs.foreach(Table[PublishingHouse].insert) }
    assertEquals(read { Table[PublishingHouse].all() }.toSet, hs.toSet)
  }

  test("updating a row that is gone raises, rather than reporting success") {
    val h = house("Faber")
    transact { Table[PublishingHouse].insert(h) }
    transact { Table[PublishingHouse].delete(h.id) }

    val e = intercept[NoSuchRow] {
      transact { Table[PublishingHouse].update(h.copy(name = "renamed")) }
    }
    assert(e.getMessage.contains("matched 0 rows"), e.getMessage)
    assert(e.getMessage.contains(h.id.show), e.getMessage)
    assert(e.getMessage.contains("does not track versions"), e.getMessage)
  }

  test("deleting a row that is gone raises too") {
    val e = intercept[NoSuchRow] {
      transact { Table[PublishingHouse].delete(Id.gen[PublishingHouse]()) }
    }
    assert(e.getMessage.contains("delete matched 0 rows"), e.getMessage)
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
