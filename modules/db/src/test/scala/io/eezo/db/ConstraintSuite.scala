package io.eezo.db

import io.eezo.db.support.*

import java.util.UUID

/** What the generated DDL actually enforces once Postgres has it. Was the assertions buried in
  * `Step3` and `Step4`, which printed and exited rather than asserting.
  */
class ConstraintSuite extends PgSuite {

  private val herbert = Author(Id.gen(), "Frank Herbert", Some("US"), None)

  private def book(title: String, author: Ref[Author] = Ref.to(herbert.id)) =
    Book(Id.gen(), author, Title(title), None, None, None, "paperback")

  test("a foreign key rejects a dangling reference") {
    create(Library)
    insert(Table[Author], herbert)
    assert(
      rejected(insert(Table[Book], book("Ghost", Ref[Author](UUID.randomUUID())))),
      "an author that does not exist was accepted"
    )
  }

  test("a foreign key accepts a reference that resolves") {
    create(Library)
    insert(Table[Author], herbert)
    insert(Table[Book], book("Dune"))
    assertEquals(selectAll(Table[Book]).map(_.title.value), List("Dune"))
  }

  test("a unique index rejects a duplicate") {
    create(Library)
    insert(Table[Author], herbert)
    insert(Table[Book], book("Dune"))
    assert(rejected(insert(Table[Book], book("Dune"))), "uq_book_title did not hold")
  }

  test("a check derived from an opaque type rejects an over-long value") {
    // Title carries Check.MaxLen(100). Nothing in Book mentions it; the constraint arrives
    // through the Column layer (DESIGN §3.1).
    create(Library)
    insert(Table[Author], herbert)
    assert(rejected(insert(Table[Book], book("x" * 101))), "the length check did not hold")
    insert(Table[Book], book("x" * 100))
  }

  test("a not-null column rejects a missing value") {
    create(Library)
    insert(Table[Author], herbert)
    assert(rejected(exec("""insert into "book" ("id", "author_id", "title") values
                            (gen_random_uuid(), gen_random_uuid(), 'x')""")))
  }

  test("a self-referencing foreign key accepts rows inserted parent-first") {
    create(Library)
    val master = Author(Id.gen(), "Old Master", Some("US"), None)
    val pupil  = Author(Id.gen(), "Young Turk", Some("US"), Some(Ref.to(master.id)))
    insert(Table[Author], master, pupil)
    assertEquals(selectAll(Table[Author]).flatMap(_.mentor).map(_.value), List(master.id.value))
  }
}
