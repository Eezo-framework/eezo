package io.eezo.db.schema

import io.eezo.db.support.Snaps.*

import munit.FunSuite

/** `Ddl` is the only DDL renderer in the framework (DESIGN §4), so this is the only place
  * SQL text is asserted. */
class DdlSuite extends FunSuite {

  test("create table renders pk, not null and checks, and quotes every identifier") {
    val t = tbl("book", id, col("title", checks = List("length(title) <= 100")), col("x", nullable = true))
    val sql = Ddl.render(Change.CreateTable(t))
    assert(sql.startsWith("""create table "book" ("""), sql)
    assert(sql.contains(""""id" uuid primary key"""), sql)
    assert(sql.contains(""""title" text not null check (length(title) <= 100)"""), sql)
    assert(sql.contains(""""x" text"""), sql)
    assert(!sql.contains(""""x" text not null"""), sql)
  }

  test("create table does not use `if not exists`") {
    // Migrations must fail loudly when the table is already there; masking it would hide a
    // real problem. BACKLOG item 4 — this had regressed once through a second renderer.
    assert(!Ddl.render(Change.CreateTable(tbl("book", id))).contains("if not exists"))
    assert(!Ddl.render(Change.CreateIndex("book", IndexSnap("ix", List("t"), false))).contains("if not exists"))
  }

  test("a primary key is never also `not null`") {
    assert(!Ddl.render(Change.CreateTable(tbl("book", id))).contains("not null"))
  }

  test("add and drop column") {
    assertEquals(
      Ddl.render(Change.AddColumn("book", col("isbn", nullable = true))),
      """alter table "book" add column "isbn" text"""
    )
    assertEquals(
      Ddl.render(Change.AddColumn("book", col("fmt"))),
      """alter table "book" add column "fmt" text not null"""
    )
    assertEquals(
      Ddl.render(Change.DropColumn("book", "isbn")),
      """alter table "book" drop column "isbn""""
    )
  }

  test("nullability in both directions") {
    assertEquals(Ddl.render(Change.SetNullable("book", "t", true)),
                 """alter table "book" alter column "t" drop not null""")
    assertEquals(Ddl.render(Change.SetNullable("book", "t", false)),
                 """alter table "book" alter column "t" set not null""")
  }

  test("a foreign key always targets the id column") {
    assertEquals(
      Ddl.render(Change.AddForeignKey("book", "author_id", "author")),
      """alter table "book" add constraint "fk_book_author_id" """ +
        """foreign key ("author_id") references "author" ("id")"""
    )
    assertEquals(
      Ddl.render(Change.DropForeignKey("book", "author_id")),
      """alter table "book" drop constraint "fk_book_author_id""""
    )
  }

  test("a check constraint's name is derived from its expression, so add and drop agree") {
    val add  = Ddl.render(Change.AddCheck("book", "title", "length(title) <= 100"))
    val drop = Ddl.render(Change.DropCheck("book", "title", "length(title) <= 100"))
    val name = add.split("constraint ")(1).split(" ")(0)
    assert(name.startsWith("\"ck_book_title_"), name)
    assert(drop.contains(name), s"$drop does not name $name")
  }

  test("unique and plain indexes") {
    assertEquals(
      Ddl.render(Change.CreateIndex("book", IndexSnap("uq_book_title", List("title"), unique = true))),
      """create unique index "uq_book_title" on "book" ("title")"""
    )
    assertEquals(
      Ddl.render(Change.CreateIndex("book", IndexSnap("ix", List("a", "b"), unique = false))),
      """create index "ix" on "book" ("a", "b")"""
    )
    assertEquals(Ddl.render(Change.DropIndex("book", "ix")), """drop index "ix"""")
  }
}
