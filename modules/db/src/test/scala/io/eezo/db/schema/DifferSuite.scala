package io.eezo.db.schema

import io.eezo.db.support.Snaps.*

import munit.FunSuite

/** The differ is pure, so this is where most of its behaviour can be pinned cheaply. */
class DifferSuite extends FunSuite {

  private val book =
    tbl("book", id, col("title"), col("author_id", "uuid", references = Some("author")))

  test("a snapshot does not differ from itself") {
    // DESIGN §3.2: equality of two snapshots must mean "no migration needed".
    assertEquals(Differ.diff(snap(book), snap(book)), Nil)
    assertEquals(Differ.diff(empty, empty), Nil)
  }

  test("column order in the snapshot is not a difference") {
    val a = TableSnap("book", List(col("a"), col("b")), Nil)
    val b = TableSnap("book", List(col("b"), col("a")), Nil)
    assertEquals(Differ.diff(SchemaSnap(List(a)), SchemaSnap(List(b))), Nil)
  }

  test("a new table brings its FK and indexes as separate changes") {
    // FKs are emitted apart from the create so table creation order never matters.
    val t = tblIx(
      "book",
      List(id, col("author_id", "uuid", references = Some("author"))),
      List(IndexSnap("idx_book_author_id", List("author_id"), unique = false))
    )
    val d = Differ.diff(empty, snap(t))
    assertEquals(d.collect { case c: Change.CreateTable => c.table.name }, List("book"))
    assertEquals(
      d.collect { case Change.AddForeignKey(t, c, tgt) => (t, c, tgt) },
      List(("book", "author_id", "author"))
    )
    assertEquals(d.collect { case Change.CreateIndex(_, i) => i.name }, List("idx_book_author_id"))
  }

  test("dropping a table is destructive and comes last") {
    val d = Differ.diff(snap(book, tbl("author", id)), snap(tbl("author", id)))
    assertEquals(d, List(Change.DropTable("book")))
    assert(d.head.destructive)
  }

  test("adding a column emits its FK too") {
    val before = tbl("book", id)
    val after  = tbl("book", id, col("author_id", "uuid", references = Some("author")))
    val d      = Differ.diff(snap(before), snap(after))
    assertEquals(d.size, 2)
    assert(d.exists { case Change.AddColumn("book", c) => c.name == "author_id"; case _ => false })
    assert(d.contains(Change.AddForeignKey("book", "author_id", "author")))
  }

  test("dropping a column is destructive") {
    val d = Differ.diff(snap(tbl("book", id, col("title"))), snap(tbl("book", id)))
    assertEquals(d, List(Change.DropColumn("book", "title")))
    assert(d.head.destructive)
  }

  test("a type change is risky and carries both types") {
    val d = Differ.diff(
      snap(tbl("book", id, col("n", "integer"))),
      snap(tbl("book", id, col("n", "bigint")))
    )
    assertEquals(d, List(Change.AlterType("book", "n", "integer", "bigint")))
    assert(d.head.risky)
  }

  test("narrowing to not-null is risky; widening to null is not") {
    val nullable    = snap(tbl("book", id, col("t", nullable = true)))
    val notNullable = snap(tbl("book", id, col("t", nullable = false)))
    val narrowing   = Differ.diff(nullable, notNullable)
    val widening    = Differ.diff(notNullable, nullable)
    assertEquals(narrowing, List(Change.SetNullable("book", "t", false)))
    assertEquals(widening, List(Change.SetNullable("book", "t", true)))
    assert(narrowing.head.risky, "existing NULLs would fail the constraint")
    assert(!widening.head.risky)
  }

  test("checks are diffed as a set, and adding one is risky") {
    val without = snap(tbl("book", id, col("t")))
    val with_   = snap(tbl("book", id, col("t", checks = List("length(t) <= 10"))))
    assertEquals(Differ.diff(without, with_), List(Change.AddCheck("book", "t", "length(t) <= 10")))
    assertEquals(
      Differ.diff(with_, without),
      List(Change.DropCheck("book", "t", "length(t) <= 10"))
    )
    assert(Differ.diff(without, with_).head.risky, "existing rows may violate it")
  }

  test("retargeting a foreign key is a drop and an add") {
    val a = snap(tbl("book", id, col("x", "uuid", references = Some("author"))))
    val b = snap(tbl("book", id, col("x", "uuid", references = Some("publishing_house"))))
    assertEquals(
      Differ.diff(a, b),
      List(
        Change.DropForeignKey("book", "x"),
        Change.AddForeignKey("book", "x", "publishing_house")
      )
    )
  }

  test("an index is keyed by name, so redefining it drops and recreates") {
    // DESIGN §3.2: index names are recorded and significant — they are the only way to tell
    // an index the framework created from one added by hand in production.
    val one  = IndexSnap("idx_book_t", List("t"), unique = false)
    val two  = IndexSnap("idx_book_t", List("t", "u"), unique = false)
    val cols = List(id, col("t"), col("u"))
    val d    = Differ.diff(
      SchemaSnap(List(tblIx("book", cols, List(one)))),
      SchemaSnap(List(tblIx("book", cols, List(two))))
    )
    assertEquals(d, List(Change.DropIndex("book", "idx_book_t"), Change.CreateIndex("book", two)))
  }

  test("changing an index's uniqueness is a change") {
    val plain  = IndexSnap("ix", List("t"), unique = false)
    val unique = IndexSnap("ix", List("t"), unique = true)
    val cols   = List(id, col("t"))
    val d      = Differ.diff(
      SchemaSnap(List(tblIx("book", cols, List(plain)))),
      SchemaSnap(List(tblIx("book", cols, List(unique))))
    )
    assertEquals(d.size, 2)
    assertEquals(d.head, Change.DropIndex("book", "ix"))
  }

  test("every change describes itself without throwing") {
    val d = Differ.diff(snap(book), empty) ++ Differ.diff(empty, snap(book))
    assert(d.nonEmpty)
    d.foreach(c => assert(c.describe.nonEmpty))
  }
}
