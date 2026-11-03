package io.eezo.db.schema

import io.eezo.core.Id
import io.eezo.db.{Check, Column, ColumnDef, DB, PgType, Schema, Table}
import io.eezo.db.Scopes.{read, transact}
import io.eezo.db.cli.Commands
import io.eezo.db.migrate.{Decision, DeployCheck}
import io.eezo.db.support.DbSuite
import io.eezo.db.support.Snaps.{col, empty, id, snap, tbl}

import java.nio.file.Files

final case class Qty(value: Int)
object Qty {
  given Column[Qty] = Column[Int].imap(Qty(_))(_.value).withCheck(Check.Between(1, 5))
}

final case class Price(value: BigDecimal)
object Price {
  given Column[Price] = Column[BigDecimal].imap(Price(_))(_.value).withCheck(Check.Positive)
}

/** The model of issue 322, verbatim: two checks Postgres spells its own way. */
case class Item(id: Id[Item], qty: Qty, price: Price) derives Table
object Items extends Schema { val items = table[Item] }

final case class Code(value: String)
object Code {
  given Column[Code] =
    Column[String].withType(PgType.Varchar(20)).imap(Code(_))(_.value).withCheck(Check.MinLen(2))
}

final case class Label(value: String)
object Label {
  given Column[Label] =
    Column[String].withType(PgType.Varchar(40)).imap(Label(_))(_.value).withCheck(Check.MaxLen(30))
}

final case class Status(value: String)
object Status {
  given Column[Status] =
    Column[String].imap(Status(_))(_.value).withCheck(Check.Raw("{} in ('a','b')"))
}

/** Every check whose spelling Postgres rewrites, on one table. */
case class Gadget(id: Id[Gadget], qty: Qty, price: Price, code: Code, label: Label, status: Status)
    derives Table
object Gadgets extends Schema { val gadgets = table[Gadget] }

final case class Size(value: String)
object Size {
  given Column[Size] =
    Column[String]
      .withType(PgType.Varchar(10))
      .imap(Size(_))(_.value)
      .withCheck(Check.Raw("{} in ('s','m')"))
}

/** An `in` on a varchar, whose Postgres spelling Postgres spells again differently once run. */
case class Shirt(id: Id[Shirt], size: Size) derives Table
object Shirts extends Schema { val shirts = table[Shirt] }

/** A model's checks against the live ones, compared in Postgres's spelling (issue 322). */
class CheckSpellingSuite extends DbSuite {

  test("status is in sync right after sync created the table") {
    val created = transact { Commands.sync(Items, apply = true, force = false, pgSchema) }
    assert(created.applied)
    val after = read { Commands.status(Items, pgSchema) }
    assert(after.inSync, after.changes.mkString("\n"))
  }

  test("a second unforced sync finds nothing to apply and refuses nothing") {
    transact { Commands.sync(Items, apply = true, force = false, pgSchema) }
    val again = transact { Commands.sync(Items, apply = true, force = false, pgSchema) }
    assertEquals(again.changes, Nil)
    assertEquals(again.blocked, Nil)
    assert(!again.applied)
  }

  /** `before` is what the database holds when sync runs; sync must leave nothing for the next one.
    */
  private def syncSettles(name: String, schema: Schema, force: Boolean = false)(
      before: SchemaSnap => SchemaSnap
  ): Unit =
    test(s"sync settles: $name") {
      exec(Ddl.render(Differ.diff(empty, before(schema.snapshot)))*)
      assert(transact { Commands.sync(schema, apply = true, force, pgSchema) }.applied)
      val after = read { Commands.status(schema, pgSchema) }
      assert(after.inSync, after.changes.mkString("\n"))
      val again = transact { Commands.sync(schema, apply = true, force = false, pgSchema) }
      assertEquals(again.changes, Nil)
      assert(!again.applied)
      assertEquals(DeployCheck.verify(db, schema.snapshot, pgSchema), Right(()))
    }

  private def columns(f: ColumnSnap => Option[ColumnSnap])(s: SchemaSnap): SchemaSnap =
    s.copy(tables = s.tables.map(t => t.copy(columns = t.columns.flatMap(f))))

  syncSettles("every rewritten check, created by sync", Gadgets)(_ => empty)
  syncSettles("an in on a varchar, created by sync", Shirts)(_ => empty)
  syncSettles("an in on a varchar, added as a column", Shirts)(
    columns(c => Option.when(c.name != "size")(c))
  )
  syncSettles("an in on a varchar, added as a check", Shirts, force = true)(
    columns(c => Some(c.copy(checks = Nil)))
  )

  test("verify accepts a database created from the schema's own DDL") {
    create(Gadgets)
    assertEquals(DeployCheck.verify(db, Gadgets.snapshot, pgSchema), Right(()))
  }

  private val numeric = Column[BigDecimal].pgType

  /** A table `t` holding `columns`, spelled the way the model spells them. */
  private def model(columns: ColumnDef*): SchemaSnap =
    SchemaSnap(
      List(TableSnap("t", (id :: columns.map(Snapshot.column).toList).sortBy(_.name), Nil))
    )

  private def roundTrip(name: String)(column: ColumnDef): Unit =
    test(s"round trip: $name") {
      val m = model(column)
      exec(Ddl.render(Differ.diff(empty, m))*)
      assertEquals(CheckSpelling.drift(db, m, pgSchema), Nil)
    }

  private def checked(name: String, tpe: PgType, check: Check): ColumnDef =
    ColumnDef(name, tpe, nullable = false, primaryKey = false, checks = List(check))

  roundTrip("between on an integer")(checked("qty", PgType.Int4, Check.Between(1, 5)))
  roundTrip("between on a numeric")(checked("amount", numeric, Check.Between(1, 5)))
  roundTrip("positive on a numeric")(checked("price", numeric, Check.Positive))
  roundTrip("min length on a varchar")(checked("code", PgType.Varchar(20), Check.MinLen(2)))
  roundTrip("max length on a varchar")(checked("label", PgType.Varchar(40), Check.MaxLen(30)))
  roundTrip("a raw in")(checked("status", PgType.Text, Check.Raw("{} in ('a','b')")))
  roundTrip("a raw in with mixed case literals")(
    checked("status", PgType.Text, Check.Raw("{} in ('Draft','Published')"))
  )
  roundTrip("control, positive on an integer")(checked("n", PgType.Int4, Check.Positive))
  roundTrip("control, max length on a text")(checked("title", PgType.Text, Check.MaxLen(100)))

  private def plain(name: String): ColumnDef =
    ColumnDef(name, PgType.Int4, nullable = false, primaryKey = false, checks = Nil)

  private def scalar(sql: String): Long = {
    val st = db.createStatement()
    try {
      val rs = st.executeQuery(sql)
      try { rs.next(); rs.getLong(1) }
      finally rs.close()
    } finally st.close()
  }

  // Was backlog item 18: the same defect as issue 322, seen from an IN check.
  test("an ordinary IN check does not diff forever") {
    // Postgres rewrites `x in ('a','b')` as `x = ANY (ARRAY[...])` and hands that back.
    val t = snap(tbl("t", id, col("status", "text", checks = List("status in ('a','b')"))))
    exec(Ddl.render(Differ.diff(empty, t))*)
    assertEquals(CheckSpelling.drift(db, t, pgSchema), Nil)
  }

  // Was half of backlog item 17: a literal's case. The quoting half is still open.
  test("a literal's case is reported as drift, in Postgres's spelling") {
    val live =
      snap(tbl("t", id, col("status", "text", checks = List("status in ('draft','published')"))))
    val model =
      snap(tbl("t", id, col("status", "text", checks = List("status in ('Draft','Published')"))))
    exec(Ddl.render(Differ.diff(empty, live))*)
    CheckSpelling.drift(db, model, pgSchema) match {
      case List(Change.DropCheck("t", "status", _), add @ Change.AddCheck("t", "status", _)) =>
        assert(add.risky)
      case other => fail(s"expected one DropCheck and one risky AddCheck on status, got: $other")
    }
  }

  test("a check that differs in meaning is reported, in Postgres's spelling") {
    val wider = Items.snapshot.copy(tables = Items.snapshot.tables.map { t =>
      t.copy(columns = t.columns.map { c =>
        if (c.name == "qty") c.copy(checks = List("qty between 1 and 10")) else c
      })
    })
    exec(Ddl.render(Differ.diff(empty, wider))*)
    read { Commands.status(Items, pgSchema) }.changes match {
      case List(Change.DropCheck("item", "qty", _), add @ Change.AddCheck("item", "qty", e)) =>
        assert(add.risky)
        assertEquals(e, "(qty >= 1) and (qty <= 5)")
      case other => fail(s"expected one DropCheck and one AddCheck on qty, got $other")
    }
  }

  test("a check only the model has is added, one only the database has is dropped") {
    exec(
      Ddl.render(
        Differ.diff(empty, model(plain("qty"), checked("n", PgType.Int4, Check.Positive)))
      )*
    )
    assertEquals(
      CheckSpelling
        .drift(db, model(checked("qty", PgType.Int4, Check.Between(1, 5)), plain("n")), pgSchema),
      List(
        Change.DropCheck("t", "n", "n > 0"),
        Change.AddCheck("t", "qty", "(qty >= 1) and (qty <= 5)")
      )
    )
  }

  test("a check spanning two columns does not cost either column its spelling") {
    // Introspect ignores a check over two columns, so the probe must read its own back the same way.
    val m = model(
      checked("start_at", PgType.Int4, Check.Between(1, 5)),
      checked("end_at", PgType.Int4, Check.Raw("{} > start_at"))
    )
    exec(Ddl.render(Differ.diff(empty, m))*)
    val onStart = CheckSpelling.drift(db, m, pgSchema).filter {
      case Change.AddCheck(_, c, _)  => c == "start_at"
      case Change.DropCheck(_, c, _) => c == "start_at"
      case _                         => false
    }
    assertEquals(onStart, Nil)
  }

  test("a check Postgres refuses keeps the model's spelling, and costs the others nothing") {
    exec(
      Ddl.render(
        Differ.diff(empty, model(checked("qty", PgType.Int4, Check.Between(1, 5)), plain("n")))
      )*
    )
    // length() of an integer does not exist, so the probe cannot create this check.
    val m = model(
      checked("qty", PgType.Int4, Check.Between(1, 5)),
      checked("n", PgType.Int4, Check.MinLen(2))
    )
    assertEquals(
      CheckSpelling.drift(db, m, pgSchema),
      List(Change.AddCheck("t", "n", "length(n) >= 2"))
    )
  }

  test("a read only connection keeps the model's spelling instead of failing") {
    create(Items)
    exec("set session characteristics as transaction read only")
    try {
      DeployCheck.verify(db, Items.snapshot, pgSchema) match {
        case Left(changes) =>
          assert(changes.contains(Change.AddCheck("item", "qty", "qty between 1 and 5")), changes)
        case Right(()) => fail("a read only connection cannot probe, so the spellings still differ")
      }
      assert(db.getAutoCommit)
    } finally exec("set session characteristics as transaction read write")
  }

  test("inside a caller's transaction the probe neither aborts nor undoes it") {
    db.setAutoCommit(false)
    try {
      exec("""create table "kept" ("id" integer)""")
      CheckSpelling.postgres(db, model(checked("n", PgType.Int4, Check.MinLen(2))))
      exec("""insert into "kept" values (1)""")
      assert(!db.getAutoCommit)
      db.commit()
    } finally db.setAutoCommit(true)
    assertEquals(scalar("""select count(*) from "kept""""), 1L)
  }

  test("sync commits its DDL and status hands the connection back in autocommit") {
    val synced = transact { Commands.sync(Items, apply = true, force = false, pgSchema) }
    assert(synced.applied)
    assertEquals(scalar(s"select count(*) from pg_tables where schemaname = '$pgSchema'"), 1L)
    val auto = read { Commands.status(Items, pgSchema); summon[DB].connection.getAutoCommit }
    assert(auto)
    DeployCheck.verify(db, Items.snapshot, pgSchema)
    assert(db.getAutoCommit)
  }

  test("nothing the probe creates outlives it") {
    transact { Commands.sync(Items, apply = false, force = false, pgSchema) }
    transact { Commands.sync(Items, apply = true, force = false, pgSchema) }
    read { Commands.status(Items, pgSchema) }
    DeployCheck.verify(db, Items.snapshot, pgSchema)
    CheckSpelling.postgres(db, model(checked("n", PgType.Int4, Check.MinLen(2))))
    assertEquals(scalar("select count(*) from pg_class where relname = 'eezo_check_probe'"), 0L)
    assertEquals(
      scalar("select count(*) from pg_class where relnamespace = pg_my_temp_schema()"),
      0L
    )
    assertEquals(scalar(s"select count(*) from pg_tables where schemaname = '$pgSchema'"), 1L)
    assertEquals(
      scalar(
        s"""select count(*) from pg_constraint con join pg_namespace n on n.oid = con.connamespace
            where n.nspname = '$pgSchema' and con.contype = 'c'"""
      ),
      2L
    )
  }

  test("freeze keeps the model's spelling") {
    val dir     = Files.createTempDirectory("eezo-check-spelling")
    val written = Commands.freeze(Items, "init", _ => Decision.Accept, dir).migration
    assert(
      written.exists(p => Files.readString(p).contains("check (qty between 1 and 5)")),
      written
    )
  }
}
