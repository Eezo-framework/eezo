package io.eezo.db.migrate

import io.eezo.db.schema.*
import io.eezo.db.support.Snaps.*

import munit.FunSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The tamper-detection story, which is what makes "migrations are generated artifacts" (DESIGN
  * §3.5) enforceable rather than a convention.
  */
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
    val stripped =
      migration.render.linesIterator.filterNot(_.contains("fingerprint")).mkString("\n")
    assertEquals(Migration.verify(stripped), Left("no fingerprint header"))
  }

  test("comments are not fingerprinted, so the header stays human-editable") {
    val recommented = migration.render.replace("-- initial", "-- initial (reviewed)")
    assertEquals(Migration.verify(recommented), Right(stmts))
  }

  /** A statement list through the real `render`, asserted back as that same list.
    *
    * Every quoting case below goes through this rather than a hand-built file, because the shape
    * that breaks a splitter is the one `render` writes: the `;` lands on the same line as the
    * statement, so a statement ending in a comment hides its own terminator inside that comment. A
    * file assembled by hand can put the `;` on its own line and pass without exercising any of it.
    */
  private def roundTrips(name: String)(statements: List[String]): Unit =
    test(name) {
      val rendered = Migration(1, "seed", statements, Migration.fingerprint(statements)).render
      assertEquals(Migration.verify(rendered), Right(statements))
    }

  roundTrips("a semicolon inside a string literal does not split the statement")(
    List("""insert into "post" ("title") values ('semicolon; inside text')""")
  )

  roundTrips("a double quoted identifier with an apostrophe does not swallow the next statement")(
    List("""create table "o'clock" ("id" int)""", """create index "ix" on "o'clock" ("id")""")
  )

  roundTrips("a statement ending in a comment keeps the terminator that follows the comment")(
    List("""update "post" set "n" = 0 -- reset""", "select 1")
  )

  roundTrips("an apostrophe in a trailing comment opens no literal")(
    List("""update post set n = 0 -- it's stale""", "select 1")
  )

  roundTrips("a block comment holding an apostrophe does not swallow the next statement")(
    List("""update post set n = 0 /* don't */""", "select 1")
  )

  roundTrips("a semicolon right after a doubled quote stays inside the literal")(
    List("""insert into "t" ("v") values ('a''; b')""")
  )

  roundTrips("a literal ending in a doubled quote still finds the real terminator after it")(
    List("""select 'a'';'""", "select 1")
  )

  roundTrips("an escaped quote inside one statement leaves the next one whole")(
    List("""insert into t values (E'it\'s')""")
  )

  roundTrips("escaped quotes across two statements do not merge them")(
    List("""insert into t values (E'a\'b')""", """insert into t values (E'c\'d')""")
  )

  roundTrips("plain generated DDL is unaffected")(
    List(
      """create table "book" ("id" uuid primary key)""",
      """create index "ix_book" on "book" ("id")"""
    )
  )

  roundTrips("a dollar quoted body keeps a semicolon and the next statement")(
    List(
      "create function f() returns void as $$ begin perform 1; end $$ language plpgsql",
      "select 1"
    )
  )

  roundTrips("a tagged dollar quote keeps a semicolon and the next statement")(
    List(
      "create function f() returns void as $fn$ begin perform 1; end $fn$ language plpgsql",
      "select 1"
    )
  )

  roundTrips("a dollar quoted body keeps a comment line and a semicolon")(
    List(
      "create function f() returns void as $$\nbegin\n  -- keep this line\n  perform 1;\nend\n$$ language plpgsql",
      "select 1"
    )
  )

  roundTrips("a dollar sign inside an unquoted identifier does not open a dollar quote")(
    List("create table foo$tag$ (v text default 'a; b')")
  )

  roundTrips("a line of a literal that starts with a comment mark is kept")(
    List(
      "insert into t (v) values ('first\n-- not a comment\na; b')",
      "select 1"
    )
  )

  roundTrips("a block comment with an apostrophe does not swallow a later semicolon")(
    List(
      "update post set n = 0 /* don't */",
      "insert into post (t) values ('a; b')"
    )
  )

  roundTrips("an apostrophe in an identifier does not swallow a later semicolon")(
    List(
      """create table "o'clock" ("id" int)""",
      """insert into "o'clock" ("v") values ('a; b')"""
    )
  )

  /** The comment state earns its place here and nowhere else in this suite.
    *
    * The comment carries a quote that closes nothing, and the statement after it carries a `;`
    * inside a literal. Without comment state the stray apostrophe opens a literal that swallows
    * that `;`, the scanned split is wrong, and the plain split is wrong too because of the literal,
    * so neither candidate matches and `verify` refuses a file `render` had just written.
    */
  roundTrips("a comment with an unmatched quote, then a literal holding a semicolon")(
    List("""update "t" set "n" = 0 -- don't""", """insert into "t" ("v") values ('a; b')""")
  )

  test("the plain split is taken when the scanner's reading does not match the fingerprint") {
    // Backslash escapes are not tracked, so the scanner reads this as one statement. The plain
    // split reads two, matches the declared fingerprint, and is what parse answers with.
    val stmts    = List("""insert into t values (E'a\'b')""", """insert into t values (E'c\'d')""")
    val rendered = Migration(1, "seed", stmts, Migration.fingerprint(stmts)).render
    assertEquals(Migration.parse(rendered).map(_._2), Some(stmts))
  }

  test("a file neither reading explains is still refused") {
    val stmts    = List("""create table "book" ("id" uuid primary key)""")
    val tampered =
      Migration(1, "seed", stmts, Migration.fingerprint(stmts)).render.replace("book", "volume")
    assert(Migration.verify(tampered).isLeft, "a tampered file must not verify under either split")
  }

  /** The limit of the line above, recorded rather than fixed.
    *
    * A `;` inside a comment has to cut, because `render` writes the terminator of a statement that
    * ends in a comment inside that comment's own text. So a `;` the comment itself carries cuts
    * there too, and the statement comes back in two pieces. The same file does not verify at the
    * merge base either, where the plain split cuts at both that `;` and the one in the literal, so
    * this is a limit and not something this change took away.
    */
  test("a semicolon in the comment's own text is not supported") {
    val stmts =
      List(
        """update "t" set "n" = 0 -- don't; really""",
        """insert into "t" ("v") values ('a; b')"""
      )
    val rendered = Migration(1, "seed", stmts, Migration.fingerprint(stmts)).render
    assertEquals(
      Migration.parse(rendered).map(_._2),
      Some(
        List(
          """update "t" set "n" = 0 -- don't""",
          "really",
          """insert into "t" ("v") values ('a; b')"""
        )
      )
    )
    assert(Migration.verify(rendered).isLeft, "the fingerprint cannot match a split statement")
  }
}

/** Migration authoring: numbering, resolutions, and the snapshot written alongside. */
class FreezeSuite extends FunSuite {

  private val v1 = snap(tbl("book", id))
  private val v2 = snap(tbl("book", id, col("title")))

  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("eezo-freeze"),
    teardown =
      dir => Files.walk(dir).iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
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
    Freeze.write(
      "initial",
      Differ.diff(SchemaSnap(Nil), v1).map(Resolution(_, Decision.Accept)),
      v1,
      dir
    )
    val second =
      Freeze.write("add title", Differ.diff(v1, v2).map(Resolution(_, Decision.Accept)), v2, dir)
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
    val manual =
      List(Resolution(Change.DropColumn("book", "title"), Decision.Manual(List("select 1"))))
    val out = Freeze.write("manual", manual, v1, dir)
    assertEquals(Migration.verify(Files.readString(out)), Right(List("select 1")))
  }

  test("slug reduces a sentence to a filename") {
    assertEquals(Freeze.slug("add isbn to book"), "add_isbn_to_book")
    assertEquals(Freeze.slug("Add ISBN!"), "add_isbn")
  }
}
