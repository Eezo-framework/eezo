package io.eezo.db.schema

import io.eezo.db.support.*
import io.eezo.db.support.Snaps.*

/** `apply(diff(a, b))` must land exactly on `b`, executed against a real Postgres.
  *
  * BACKLOG item 15 asks for this over randomly generated snapshots. This is the
  * table-driven form: the same property, with the edits chosen rather than generated. It is
  * the test that covers the differ, the renderer and the introspector at once — a change
  * that any two of them agree on but the third does not shows up here and nowhere else.
  */
class DifferRoundTripSuite extends PgSuite {

  private val author  = tbl("author", id, col("name"))
  private val authorX = tbl("author", id, col("name"), col("x", nullable = true))

  private def roundTrip(name: String)(from: SchemaSnap, to: SchemaSnap): Unit =
    test(name) {
      exec(Ddl.render(Differ.diff(empty, from))*)
      assertEquals(Differ.diff(live(), from), Nil, "setup did not land on `from`")
      exec(Ddl.render(Differ.diff(from, to))*)
      assertEquals(Differ.diff(live(), to), Nil, "diff(from, to) did not land on `to`")
    }

  roundTrip("create a table")(empty, snap(author))
  roundTrip("add a second table")(snap(author), snap(author, tbl("book", id, col("title"))))
  roundTrip("drop a table")(snap(author, tbl("book", id, col("title"))), snap(author))
  roundTrip("drop every table")(snap(author), empty)

  roundTrip("add a nullable column")(snap(author), snap(authorX))
  roundTrip("add a not-null column")(snap(author), snap(tbl("author", id, col("name"), col("x"))))
  roundTrip("drop a column")(snap(authorX), snap(author))

  roundTrip("widen a type")(
    snap(tbl("author", id, col("n", "integer"))),
    snap(tbl("author", id, col("n", "bigint")))
  )
  roundTrip("constrain a type")(
    snap(tbl("author", id, col("name", "text"))),
    snap(tbl("author", id, col("name", "varchar(100)")))
  )

  roundTrip("set not null")(
    snap(tbl("author", id, col("name", nullable = true))),
    snap(tbl("author", id, col("name", nullable = false)))
  )
  roundTrip("drop not null")(
    snap(tbl("author", id, col("name", nullable = false))),
    snap(tbl("author", id, col("name", nullable = true)))
  )

  roundTrip("add a check")(
    snap(author),
    snap(tbl("author", id, col("name", checks = List("length(name) <= 100"))))
  )

  // "drop a check" is deliberately absent from the round-trips above: it does not work.
  // See the two tests at the bottom of this suite and BACKLOG item 25.

  roundTrip("add an index")(
    snap(author),
    snap(tblIx("author", List(id, col("name")), List(IndexSnap("ix_author_name", List("name"), false))))
  )
  roundTrip("drop an index")(
    snap(tblIx("author", List(id, col("name")), List(IndexSnap("ix_author_name", List("name"), false)))),
    snap(author)
  )
  roundTrip("make an index unique")(
    snap(tblIx("author", List(id, col("name")), List(IndexSnap("ix", List("name"), false)))),
    snap(tblIx("author", List(id, col("name")), List(IndexSnap("ix", List("name"), true))))
  )
  roundTrip("add a composite index")(
    snap(tbl("author", id, col("a"), col("b"))),
    snap(tblIx("author", List(id, col("a"), col("b")), List(IndexSnap("ix_ab", List("a", "b"), false))))
  )

  roundTrip("add a foreign key")(
    snap(author, tbl("book", id, col("author_id", "uuid"))),
    snap(author, tbl("book", id, col("author_id", "uuid", references = Some("author"))))
  )
  roundTrip("drop a foreign key")(
    snap(author, tbl("book", id, col("author_id", "uuid", references = Some("author")))),
    snap(author, tbl("book", id, col("author_id", "uuid")))
  )
  roundTrip("retarget a foreign key")(
    snap(author, tbl("house", id), tbl("book", id, col("x", "uuid", references = Some("author")))),
    snap(author, tbl("house", id), tbl("book", id, col("x", "uuid", references = Some("house"))))
  )

  roundTrip("several edits at once")(
    snap(author, tbl("book", id, col("title"), col("old"))),
    snap(
      tblIx("author", List(id, col("name"), col("country", nullable = true)),
            List(IndexSnap("ix_author_name", List("name"), true))),
      tbl("book", id, col("title", checks = List("length(title) <= 50")),
          col("author_id", "uuid", references = Some("author")))
    )
  )

  /** Constraint names as Postgres actually recorded them. */
  private def checkConstraints(table: String): List[String] = {
    val ps = db.prepareStatement(
      """select con.conname from pg_constraint con
         join pg_class rel on rel.oid = con.conrelid
         join pg_namespace ns on ns.oid = rel.relnamespace
         where con.contype = 'c' and rel.relname = ? and ns.nspname = ?
         order by con.conname"""
    )
    ps.setString(1, table)
    ps.setString(2, pgSchema)
    try {
      val rs = ps.executeQuery()
      try Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toList
      finally rs.close()
    } finally ps.close()
  }

  test("BACKLOG 25: a check that arrives with its table gets Postgres's name, not ours") {
    // `Ddl.render(CreateTable)` emits the check inline and unnamed, so Postgres names it
    // <table>_<column>_check. `Ddl.render(AddCheck)` names it ck_<table>_<column>_<hash>.
    // Two spellings for one constraint, and only the second is the one DropCheck knows.
    val withCheck = snap(tbl("author", id, col("name", checks = List("length(name) <= 100"))))
    exec(Ddl.render(Differ.diff(empty, withCheck))*)
    assertEquals(checkConstraints("author"), List("author_name_check"))

    exec(Ddl.render(Differ.diff(empty, snap(tbl("book", id))))*)
    exec(Ddl.render(List(Change.AddCheck("book", "id", "id is not null")))*)
    assert(checkConstraints("book").head.startsWith("ck_book_id_"), checkConstraints("book").toString)
  }

  test("BACKLOG 25: so dropping a check created with its table fails".fail) {
    val from = snap(tbl("author", id, col("name", checks = List("length(name) <= 100"))))
    exec(Ddl.render(Differ.diff(empty, from))*)
    exec(Ddl.render(Differ.diff(from, snap(author)))*)
  }

  test("BACKLOG 17: a check on a column whose name needs quoting renders invalid SQL") {
    // Ddl quotes the column in its definition, but the check expression comes from the
    // snapshot's canonical form, which had its quotes stripped. So the constraint refers to
    // a bare reserved word. Invert this test when item 17 is fixed.
    val t   = tbl("t", id, col("order", checks = List("length(order) <= 10")))
    val sql = Ddl.render(Differ.diff(empty, snap(t)))
    assert(sql.head.contains("check (length(order) <= 10)"), sql.head)
    assert(rejected(exec(sql*)), "Postgres accepted an unquoted reserved word")
  }
}
