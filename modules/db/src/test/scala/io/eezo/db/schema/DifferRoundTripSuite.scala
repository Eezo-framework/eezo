package io.eezo.db.schema

import io.eezo.db.support.*
import io.eezo.db.support.Snaps.*

/** `apply(diff(a, b))` must land exactly on `b`, executed against a real Postgres.
  *
  * BACKLOG item 15 asks for this over randomly generated snapshots. This is the table-driven form:
  * the same property, with the edits chosen rather than generated. It is the test that covers the
  * differ, the renderer and the introspector at once — a change that any two of them agree on but
  * the third does not shows up here and nowhere else.
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

  // No round trip above drops a check, because a check created inline with its table cannot be
  // dropped yet (BacklogSuite, item 25). The test below drops a check added apart from its table.

  test("change a type whose check the old type needed") {
    // The check is added apart from its table so it carries the name DropCheck renders.
    val check = "length(name) <= 10"
    val from  = snap(tbl("author", id, col("name", "text", checks = List(check))))
    val to    = snap(tbl("author", id, col("name", "integer")))
    exec(Ddl.render(Differ.diff(empty, snap(tbl("author", id, col("name", "text")))))*)
    exec(Ddl.render(Change.AddCheck("author", "name", check)))
    assertEquals(Differ.diff(live(), from), Nil, "setup did not land on `from`")
    exec(Ddl.render(Differ.diff(from, to))*)
    assertEquals(Differ.diff(live(), to), Nil, "diff(from, to) did not land on `to`")
  }

  roundTrip("add an index")(
    snap(author),
    snap(
      tblIx("author", List(id, col("name")), List(IndexSnap("ix_author_name", List("name"), false)))
    )
  )
  roundTrip("drop an index")(
    snap(
      tblIx("author", List(id, col("name")), List(IndexSnap("ix_author_name", List("name"), false)))
    ),
    snap(author)
  )
  roundTrip("make an index unique")(
    snap(tblIx("author", List(id, col("name")), List(IndexSnap("ix", List("name"), false)))),
    snap(tblIx("author", List(id, col("name")), List(IndexSnap("ix", List("name"), true))))
  )
  roundTrip("add a composite index")(
    snap(tbl("author", id, col("a"), col("b"))),
    snap(
      tblIx("author", List(id, col("a"), col("b")), List(IndexSnap("ix_ab", List("a", "b"), false)))
    )
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
      tblIx(
        "author",
        List(id, col("name"), col("country", nullable = true)),
        List(IndexSnap("ix_author_name", List("name"), true))
      ),
      tbl(
        "book",
        id,
        col("title", checks = List("length(title) <= 50")),
        col("author_id", "uuid", references = Some("author"))
      )
    )
  )
}
