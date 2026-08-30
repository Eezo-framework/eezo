package io.eezo.db

import io.eezo.core.Id
import io.eezo.db.support.*

import munit.FunSuite

/** What one `derives Table` produces, with no database involved. */
class TableDerivationSuite extends FunSuite {

  test("the table name is the snake_cased class name") {
    assertEquals(Table[Book].tableName, "book")
    assertEquals(Table[PublishingHouse].tableName, "publishing_house")
  }

  test("columns keep declaration order") {
    assertEquals(Table[Author].columns.map(_.name), List("id", "name", "country", "mentor_id"))
  }

  test("`id` is the primary key, and nothing else is") {
    assertEquals(Table[Book].columns.filter(_.primaryKey).map(_.name), List("id"))
  }

  test("a Ref field becomes <name>_id and carries its FK target") {
    val c = Table[Book].columns.find(_.name == "author_id").get
    assertEquals(c.references, Some("author"))
    assertEquals(c.pgType, PgType.Uuid)
    assertEquals(c.nullable, false)
  }

  test("Option[Ref] is nullable and still resolves its target") {
    // DESIGN §3.1: this works because RefTarget is summoned rather than pattern-matched,
    // so Option[Ref[T]] needs no case of its own.
    val c = Table[Book].columns.find(_.name == "published_by_id").get
    assertEquals(c.references, Some("publishing_house"))
    assertEquals(c.nullable, true)
  }

  test("a self-reference resolves to its own table") {
    val c = Table[Author].columns.find(_.name == "mentor_id").get
    assertEquals(c.references, Some("author"))
  }

  test("an opaque type contributes its CHECK with no macro support") {
    assertEquals(Table[Book].columns.find(_.name == "title").get.checks, List(Check.MaxLen(100)))
  }

  test("Option[A] is nullable, A is not") {
    val nullable = Table[Book].columns.map(c => c.name -> c.nullable).toMap
    assertEquals(nullable("isbn"), true)
    assertEquals(nullable("format"), false)
  }

  test("every scalar given maps to its Postgres type") {
    val t = Table[Widget].columns.map(c => c.name -> c.pgType).toMap
    assertEquals(t("name"), PgType.Text)
    assertEquals(t("count"), PgType.Int4)
    assertEquals(t("size"), PgType.Int8)
    assertEquals(t("active"), PgType.Bool)
    assertEquals(t("price"), PgType.Numeric(19, 4))
    assertEquals(t("external"), PgType.Uuid)
    assertEquals(t("day"), PgType.Date)
    assertEquals(t("at"), PgType.Timestamptz)
    assertEquals(t("when"), PgType.Date)
  }

  test("Option[A] borrows A's Postgres type rather than inventing one") {
    assertEquals(Table[Widget].columns.find(_.name == "tally").get.pgType, PgType.Int4)
  }

  test("an Id key is a non-null uuid, through a given `db` owns and `core` cannot") {
    val c = Table[Author].columns.find(_.name == "id").get
    assertEquals(c.pgType, PgType.Uuid)
    assertEquals(c.nullable, false)
    // Written out, because the derivation above would also pass on a `Column[Id[T]]` found in
    // `Id`'s own companion, which is the arrangement this ticket removes.
    assertEquals(Column[Id[Author]].pgType, PgType.Uuid)
  }
}

/** Cross-table validation, which happens when a `Schema`'s snapshot is first forced. */
class SchemaValidationSuite extends FunSuite {

  test("an index on a column that doesn't exist no longer compiles") {
    // BACKLOG §7's first payoff. This used to be a `SchemaError` thrown when the schema was first
    // forced — a runtime failure for a typo the compiler can see.
    val e = compileErrors("Library.books.index(_.no_such_column)")
    assert(e.contains("no_such_column is not a member"), e)
  }

  test("an FK to an unregistered table is rejected") {
    object Partial extends Schema { val books = table[Book] } // no author, no publishing_house
    val e = intercept[RuntimeException](Partial.snapshot)
    assert(e.getMessage.contains("author"), e.getMessage)
  }

  test("registering the same table twice is rejected") {
    object Twice extends Schema {
      val a = table[Author]
      val b = table[Author]
    }
    val e = intercept[RuntimeException](Twice.snapshot)
    assert(e.getMessage.contains("Duplicate"), e.getMessage)
  }
}
