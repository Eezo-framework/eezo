package io.eezo.db.migrate

import io.eezo.db.schema.*
import io.eezo.db.support.Snaps.*

import munit.FunSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The tamper-detection story, which is what makes "migrations are generated artifacts"
  * (DESIGN §3.5) enforceable rather than a convention. */
class MigrationSuite extends FunSuite {

  private val stmts = List(
    """create table "book" ("id" uuid primary key)""",
    """create index "ix_book" on "book" ("id")"""
  )
  private def migration = Migration(1, "initial", stmts, Migration.fingerprint(stmts))

  test("a fingerprint depends on the statements, their content and their order") {
    assertEquals(Migration.fingerprint(stmts), Migration.fingerprint(stmts))
    assertNotEquals(Migration.fingerprint(stmts), Migration.fingerprint(stmts.reverse))
    assertNotEquals(Migration.fingerprint(stmts), Migration.fingerprint(stmts :+ "drop table x"))
    assertNotEquals(Migration.fingerprint(Nil), Migration.fingerprint(stmts))
  }

  test("a freshly rendered migration verifies, and parses back to its statements") {
    assertEquals(Migration.verify(migration.render), Right(stmts))
  }

  test("the filename is zero-padded so files sort in apply order") {
    assertEquals(Migration(1, "initial", Nil, "x").filename, "0001_initial.sql")
    assertEquals(Migration(42, "add_isbn", Nil, "x").filename, "0042_add_isbn.sql")
  }

  test("editing a statement is detected") {
    val tampered = migration.render.replace("book", "volume")
    assert(Migration.verify(tampered).isLeft, "an edited body must not verify")
  }

  test("deleting a statement is detected") {
    val tampered = migration.render.replace("""create index "ix_book" on "book" ("id");""", "")
    assert(Migration.verify(tampered).isLeft, "a removed statement must not verify")
  }

  test("removing the fingerprint header is detected") {
    val stripped = migration.render.linesIterator.filterNot(_.contains("fingerprint")).mkString("\n")
    assertEquals(Migration.verify(stripped), Left("no fingerprint header"))
  }

  test("comments are not fingerprinted, so the header stays human-editable") {
    val recommented = migration.render.replace("-- initial", "-- initial (reviewed)")
    assertEquals(Migration.verify(recommented), Right(stmts))
  }
}

/** Migration authoring: numbering, resolutions, and the snapshot written alongside. */
class FreezeSuite extends FunSuite {

  private val v1 = snap(tbl("book", id))
  private val v2 = snap(tbl("book", id, col("title")))

  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("eezo-freeze"),
    teardown = dir =>
      Files.walk(dir).iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
  )

  tmp.test("an absent snapshot file reads as the empty schema") { dir =>
    assertEquals(Freeze.committedSnapshot(dir), SchemaSnap(Nil))
    assertEquals(Freeze.existing(dir), Nil)
    assertEquals(Freeze.nextNumber(dir), 1)
  }

  tmp.test("write emits a numbered migration and the snapshot it lands on") { dir =>
    val changes = Differ.diff(SchemaSnap(Nil), v1)
    val out     = Freeze.write("initial", changes.map(Resolution(_, Decision.Accept)), v1, dir)

    assertEquals(out.getFileName.toString, "0001_initial.sql")
    assertEquals(Migration.verify(Files.readString(out)).map(_.size), Right(changes.size))
    // The snapshot is written with the migration, so the next freeze diffs against it.
    assertEquals(Freeze.committedSnapshot(dir), v1)
    assertEquals(Freeze.nextNumber(dir), 2)
  }

  tmp.test("numbering continues from the highest file present") { dir =>
    Freeze.write("initial", Differ.diff(SchemaSnap(Nil), v1).map(Resolution(_, Decision.Accept)), v1, dir)
    val second = Freeze.write("add title", Differ.diff(v1, v2).map(Resolution(_, Decision.Accept)), v2, dir)
    assertEquals(second.getFileName.toString, "0002_add_title.sql")
    assertEquals(Freeze.existing(dir).map(_._1), List(1, 2))
  }

  tmp.test("a skipped change is left out of the SQL") { dir =>
    val drop = Differ.diff(v2, v1) // drop the title column
    assertEquals(drop.map(_.describe), List("- book.title"))
    val out = Freeze.write("skip it", drop.map(Resolution(_, Decision.Skip)), v1, dir)
    assertEquals(Migration.verify(Files.readString(out)), Right(Nil))
  }

  tmp.test("a manual resolution substitutes its own SQL") { dir =>
    val manual = List(Resolution(Change.DropColumn("book", "title"), Decision.Manual(List("select 1"))))
    val out    = Freeze.write("manual", manual, v1, dir)
    assertEquals(Migration.verify(Files.readString(out)), Right(List("select 1")))
  }

  test("slug reduces a sentence to a filename") {
    assertEquals(Freeze.slug("add isbn to book"), "add_isbn_to_book")
    assertEquals(Freeze.slug("Add ISBN!"), "add_isbn")
  }
}
