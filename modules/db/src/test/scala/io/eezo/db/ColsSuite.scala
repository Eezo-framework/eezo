package io.eezo.db

import io.eezo.db.support.{Author, Book, Library, PublishingHouse, Title}
import munit.FunSuite

/** Typed column references, and the two things BACKLOG §7 wanted from them. */
class ColsSuite extends FunSuite {

  private val book = Table[Book]

  test("a column reference is typed by its field") {
    val title: Col[Book, Title]         = book.cols.title
    val isbn: Col[Book, Option[String]] = book.cols.isbn
    assertEquals(title.name, "title")
    assertEquals(isbn.name, "isbn")
  }

  test("a Ref field carries its _id suffix, so nobody writes one") {
    assertEquals(book.cols.author.name, "author_id")
    assertEquals(book.cols.publishedBy.name, "published_by_id")
  }

  test("field names are snake_cased for the database") {
    assertEquals(book.cols.publishedOn.name, "published_on")
  }

  test("a column carries the codec the macro summoned") {
    assertEquals(book.cols.title.codec.pgType, summon[Column[Title]].pgType)
  }

  test("every declared column has a reference, in declaration order") {
    val fromCols =
      book.cols.toTuple.productIterator.map(_.asInstanceOf[Col[Book, ?]].name).toList
    assertEquals(fromCols, book.columns.map(_.name))
  }

  test("a typo does not compile") {
    val e = compileErrors("Table[Book].cols.titel")
    assert(e.contains("titel is not a member"), e)
  }

  test("an index names fields and stores columns") {
    val idx = Library.books.indexes.map(i => (i.name, i.columns, i.unique)).toSet
    assertEquals(
      idx,
      Set(
        (
          "idx_book_author_id_published_on_published_by_id",
          List("author_id", "published_on", "published_by_id"),
          false
        ),
        ("uq_book_title", List("title"), true)
      )
    )
  }

  test("an index on a field that does not exist is a compile error") {
    val e = compileErrors("Library.books.index(_.no_such_field)")
    assert(e.contains("no_such_field is not a member"), e)
  }

  test("cols works for a model with no Refs at all") {
    assertEquals(Table[PublishingHouse].cols.location.name, "location")
    assertEquals(Table[Author].cols.mentor.name, "mentor_id")
  }
}
