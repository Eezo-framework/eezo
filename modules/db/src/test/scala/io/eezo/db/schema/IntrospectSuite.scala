package io.eezo.db.schema

import io.eezo.db.migrate.Migrator
import io.eezo.db.support.*

/** Code → DDL → catalog → snapshot must be the identity, and any hand edit to the database
  * must show up as a difference. Was `Step4` and `DbStatus`, which printed a two-column
  * diff for a human to read. */
class IntrospectSuite extends PgSuite {

  test("a schema created from the model introspects back to the model") {
    // DESIGN §1, property 2: code vs. live database is a single `==` on rendered snapshots.
    create(Library)
    assertNoDiff(live().render, Library.snapshot.render)
    assertEquals(Differ.diff(live(), Library.snapshot), Nil)
  }

  test("the migration ledger is not part of the schema") {
    create(Library)
    Migrator.ensureLedger(db)
    assert(!live().tables.map(_.name).contains("eezo_migrations"))
    assertEquals(Differ.diff(live(), Library.snapshot), Nil)
  }

  test("primary-key indexes are not recorded") {
    // Postgres creates them itself, so recording them would diff forever (DESIGN §3.2).
    create(Library)
    assertEquals(live().tables.flatMap(_.indexes).filter(_.name.endsWith("_pkey")), Nil)
    assertEquals(live().tables.flatMap(_.indexes).map(_.name).sorted,
                 List("idx_book_author_id_published_on_published_by_id", "uq_book_title"))
  }

  test("a column added by hand is detected") {
    create(Library)
    exec("""alter table "book" add column "extra" text""")
    assertEquals(Differ.diff(live(), Library.snapshot).map(_.describe), List("- book.extra"))
  }

  test("a column dropped by hand is detected") {
    create(Library)
    exec("""alter table "book" drop column "isbn"""")
    assertEquals(Differ.diff(live(), Library.snapshot).map(_.describe), List("+ book.isbn text null"))
  }

  test("a type changed by hand is detected") {
    create(Library)
    exec("""alter table "book" alter column "format" type varchar(20)""")
    assertEquals(Differ.diff(live(), Library.snapshot).map(_.describe),
                 List("~ book.format varchar(20) -> text"))
  }

  test("a dropped not-null is detected") {
    create(Library)
    exec("""alter table "book" alter column "format" drop not null""")
    assertEquals(Differ.diff(live(), Library.snapshot).map(_.describe),
                 List("~ book.format set not null"))
  }

  test("an index added by hand is detected, and named") {
    // The name is the only way to tell a framework index from a hand-made one (DESIGN §3.2).
    create(Library)
    exec("""create index "by_hand" on "book" ("format")""")
    assertEquals(Differ.diff(live(), Library.snapshot).map(_.describe), List("- index by_hand on book"))
  }

  test("a dropped foreign key is detected") {
    create(Library)
    exec("""alter table "book" drop constraint "fk_book_author_id"""")
    assertEquals(Differ.diff(live(), Library.snapshot).map(_.describe),
                 List("+ fk book.author_id -> author"))
  }

  test("a table dropped by hand is detected") {
    create(Library)
    exec("""drop table "book" cascade""")
    assert(Differ.diff(live(), Library.snapshot).exists {
      case Change.CreateTable(t) => t.name == "book"
      case _                     => false
    })
  }

  test("an empty schema introspects as empty") {
    assertEquals(live(), SchemaSnap(Nil))
  }
}
