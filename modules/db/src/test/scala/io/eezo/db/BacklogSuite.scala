package io.eezo.db

import io.eezo.db.migrate.*
import io.eezo.db.schema.*
import io.eezo.db.support.*
import io.eezo.db.support.Snaps.*

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters.*

/** The backlog, executable.
  *
  * Every test here asserts what eezo *should* do, so every test here fails. That is the
  * point: a red test is a backlog item with a reproduction attached, and it turns green by
  * being fixed rather than by being edited. The number in each name is the item in
  * `design/backlog.md`.
  *
  * The rest of the suite stays green and is the regression signal:
  *
  * {{{
  * sbt "db/testOnly -- --exclude-tags=backlog"   # does everything still work?
  * sbt "db/testOnly *BacklogSuite"               # what is still wrong?
  * }}}
  *
  * Do not make one of these pass by weakening its assertion. If an item is deliberately
  * abandoned, delete the test and say so in the backlog.
  */
class BacklogSuite extends PgSuite {

  private def tmpDir: Path = {
    val d = Files.createTempDirectory("eezo-backlog")
    d.toFile.deleteOnExit()
    d
  }

  // ── 1 ────────────────────────────────────────────────────────────────────────────────
  test("1: a check is dropped before the type it constrains is altered".tag(Backlog)) {
    // Differ.diffColumn emits the type change first, and Postgres will not alter a column
    // out from under a CHECK that references it.
    val from = snap(tbl("t", id, col("c", "text", checks = List("length(c) <= 10"))))
    val to   = snap(tbl("t", id, col("c", "integer")))
    exec(Ddl.render(Differ.diff(empty, from))*)
    exec(Ddl.render(Differ.diff(from, to))*)
    assertEquals(Differ.diff(live(), to), Nil)
  }

  // ── 2 ────────────────────────────────────────────────────────────────────────────────
  test("2: a narrowing type change does not silently truncate".tag(Backlog)) {
    // AlterType renders `using "c"::varchar(100)` unconditionally, and an explicit cast to
    // varchar(n) truncates rather than failing. The data is gone with no warning.
    val from = snap(tbl("t", id, col("c", "varchar(200)")))
    val to   = snap(tbl("t", id, col("c", "varchar(100)")))
    exec(Ddl.render(Differ.diff(empty, from))*)
    exec(s"""insert into "t" ("id", "c") values (gen_random_uuid(), '${"x" * 150}')""")

    assert(
      rejected(exec(Ddl.render(Differ.diff(from, to))*)),
      "150 characters were quietly cut down to 100"
    )
  }

  // ── 3 ────────────────────────────────────────────────────────────────────────────────
  test("3: tables are dropped in an order their foreign keys allow".tag(Backlog)) {
    // Drops are emitted last but in no order among themselves, and `drop table` carries no
    // cascade — so dropping `author` before `book` fails on the FK that still points at it.
    val from = snap(
      tbl("author", id),
      tbl("book", id, col("author_id", "uuid", references = Some("author")))
    )
    exec(Ddl.render(Differ.diff(empty, from))*)
    exec(Ddl.render(Differ.diff(from, empty))*)
    assertEquals(live().tables, Nil)
  }

  // ── 12 ───────────────────────────────────────────────────────────────────────────────
  test("12: two rows that reference each other can be inserted".tag(Backlog)) {
    // Constraints are immediate, so no insert order satisfies a cycle. Two authors
    // mentoring each other is the smallest case; it needs deferrable foreign keys.
    create(Library)
    val a = Id.gen[Author]()
    val b = Id.gen[Author]()
    insert(
      Table[Author],
      Author(a, "A", None, Some(Ref[Author](b.value))),
      Author(b, "B", None, Some(Ref[Author](a.value)))
    )
    assertEquals(selectAll(Table[Author]).size, 2)
  }

  // ── 17 ───────────────────────────────────────────────────────────────────────────────
  test("17: a check on a column whose name needs quoting renders valid SQL".tag(Backlog)) {
    // Note the route: ColumnDef -> Snapshot.column -> ColumnSnap is where canonicalCheck
    // strips the quoting, and Ddl then renders that same string as executable SQL. Building
    // the ColumnSnap by hand would skip the very step under test.
    val cs = Snapshot.column(
      ColumnDef("order", PgType.Text, nullable = false, primaryKey = false,
                checks = List(Check.MaxLen(10)))
    )
    assert(cs.checks.head.contains("\""), s"the quoting is already gone: ${cs.checks.head}")
    exec(Ddl.render(Differ.diff(empty, snap(TableSnap("t", List(id, cs).sortBy(_.name), Nil))))*)
  }

  test("17: a check's string literals keep their case".tag(Backlog)) {
    // canonicalCheck lowercases the whole expression, literals included, and that lowercased
    // form is what lands in the migration file as the constraint's actual meaning.
    val cs = Snapshot.column(
      ColumnDef("status", PgType.Text, nullable = false, primaryKey = false,
                checks = List(Check.Raw("{} in ('Draft','Published')")))
    )
    assert(cs.checks.head.contains("'Draft'"), s"lowercased into: ${cs.checks.head}")
  }

  // ── 18 ───────────────────────────────────────────────────────────────────────────────
  test("18: an ordinary IN check does not diff forever".tag(Backlog)) {
    // Postgres rewrites `x in ('a','b')` as `x = ANY (ARRAY[...])` and hands that back.
    // Introspect does not canonicalise at all, so the two spellings never compare equal and
    // `status` reports a difference no migration can resolve.
    val t = snap(tbl("t", id, col("status", "text", checks = List("status in ('a','b')"))))
    exec(Ddl.render(Differ.diff(empty, t))*)
    assertEquals(Differ.diff(live(), t), Nil)
  }

  // ── 19 ───────────────────────────────────────────────────────────────────────────────
  test("19: a NULL timestamptz decodes as None".tag(Backlog)) {
    // Column[Instant].get calls .toInstant before wasNull is consulted.
    create(Moments)
    val m = Moment(Id.gen(), None)
    insert(Table[Moment], m)
    assertEquals(selectAll(Table[Moment]), List(m))
  }

  test("19: a NULL numeric decodes as None".tag(Backlog)) {
    create(Monies)
    val m = Money(Id.gen(), None)
    insert(Table[Money], m)
    assertEquals(selectAll(Table[Money]), List(m))
  }

  // ── 20 ───────────────────────────────────────────────────────────────────────────────
  test("20: adding a not-null column is flagged risky".tag(Backlog)) {
    val c = ColumnSnap("format", "text", nullable = false, false, Nil, None)
    assert(Change.AddColumn("book", c).risky, "it always fails against a non-empty table")
  }

  test("20: adding a foreign key is flagged risky".tag(Backlog)) {
    assert(Change.AddForeignKey("book", "author_id", "author").risky,
           "existing values may not resolve")
  }

  test("20: creating a unique index is flagged risky".tag(Backlog)) {
    assert(Change.CreateIndex("book", IndexSnap("uq", List("title"), unique = true)).risky,
           "existing rows may already hold duplicates")
    assert(!Change.CreateIndex("book", IndexSnap("ix", List("title"), unique = false)).risky,
           "a plain index cannot fail on data")
  }

  // ── 21 ───────────────────────────────────────────────────────────────────────────────
  test("21: an index name longer than 63 bytes still round-trips".tag(Backlog)) {
    // Postgres truncates identifiers silently. Introspect reads the short name back, the
    // differ sees a mismatch, and emits drop+create on every run forever.
    val long = "idx_t_" + ("x" * 70)
    val t    = snap(tblIx("t", List(id, col("c")), List(IndexSnap(long, List("c"), false))))
    exec(Ddl.render(Differ.diff(empty, t))*)
    assertEquals(Differ.diff(live(), t), Nil)
  }

  test("21: two long constraint names do not collide".tag(Backlog)) {
    // Postgres truncates at 63 bytes, so `ck_<table>_<column>_<hash>` keeps only the first
    // 58 characters of the column name. Two columns agreeing that far get one constraint
    // between them, and the second `add constraint` collides with the first.
    val a = ("x" * 58) + "aaa"
    val b = ("x" * 58) + "bbb"
    val t = snap(tbl("t", id, col(a), col(b)))
    exec(Ddl.render(Differ.diff(empty, t))*)
    exec(Ddl.render(Change.AddCheck("t", a, s"""length("$a") <= 10""")))
    exec(Ddl.render(Change.AddCheck("t", b, s"""length("$b") <= 10""")))
  }

  // ── 22 ───────────────────────────────────────────────────────────────────────────────
  test("22: two migrations with the same number are reported, not executed".tag(Backlog)) {
    val dir = tmpDir
    val v1  = snap(tbl("author", id))
    Freeze.write("first", Differ.diff(SchemaSnap(Nil), v1).map(Resolution(_, Decision.Accept)), v1, dir)
    // A second file claiming the same number, as a branch merge would produce.
    val clash = Freeze.migrationsDir(dir).resolve("0001_other.sql")
    val stmts = List("""create table "other" ("id" uuid primary key)""")
    Files.writeString(clash, Migration(1, "other", stmts, Migration.fingerprint(stmts)).render)

    Migrator.status(db, dir) match {
      case Migrator.Status.Tampered(problems) =>
        assert(problems.exists(_.contains("0001")), problems.mkString("\n"))
      case Migrator.Status.Ok(pending) =>
        fail(s"two migrations numbered 1 were both accepted as pending: ${pending.map(_._2)}")
    }
  }

  // ── 23 ───────────────────────────────────────────────────────────────────────────────
  test("23: schema validation failures are SchemaError, not RuntimeException".tag(Backlog)) {
    object BadIndex extends Schema {
      val authors = table[Author]
      val books   = table[Book].index("no_such_column")
      val houses  = table[PublishingHouse]
    }
    intercept[SchemaError](BadIndex.snapshot)
  }

  // ── 25 ───────────────────────────────────────────────────────────────────────────────
  test("25: a check has one name whether it arrives with its table or after".tag(Backlog)) {
    val withCheck = snap(tbl("author", id, col("name", checks = List("length(name) <= 100"))))
    exec(Ddl.render(Differ.diff(empty, withCheck))*)
    val ps = db.prepareStatement(
      """select con.conname from pg_constraint con
         join pg_class rel on rel.oid = con.conrelid
         join pg_namespace ns on ns.oid = rel.relnamespace
         where con.contype = 'c' and rel.relname = 'author' and ns.nspname = ?"""
    )
    ps.setString(1, pgSchema)
    val rs    = ps.executeQuery()
    val names = try Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toList
                finally { rs.close(); ps.close() }
    assert(names.head.startsWith("ck_author_name_"),
           s"CreateTable left Postgres to name it: ${names.mkString(", ")}")
  }

  test("25: a check created with its table can be dropped".tag(Backlog)) {
    val from = snap(tbl("author", id, col("name", checks = List("length(name) <= 100"))))
    val to   = snap(tbl("author", id, col("name")))
    exec(Ddl.render(Differ.diff(empty, from))*)
    exec(Ddl.render(Differ.diff(from, to))*)
    assertEquals(Differ.diff(live(), to), Nil)
  }
}
