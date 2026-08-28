package io.eezo.db.migrate

import io.eezo.db.schema.*
import io.eezo.db.Scopes.*
import io.eezo.db.support.*
import io.eezo.db.support.Snaps.*

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The deploy path: replay committed files, then verify the result against the model. Was `Step6`
  * (freeze) and `Step7` (migrate), which prompted on stdin and called sys.exit.
  */
class MigrateSuite extends DbSuite {

  private var dir: Path = null

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    dir = Files.createTempDirectory("eezo-migrate")
  }

  override def afterEach(context: AfterEach): Unit = {
    Files.walk(dir).iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
    super.afterEach(context)
  }

  private def freeze(name: String, from: SchemaSnap, to: SchemaSnap): Path =
    Freeze.write(name, Differ.diff(from, to).map(Resolution(_, Decision.Accept)), to, dir)

  /** Apply everything pending, failing the test if the integrity check objects. */
  // One transaction for the whole run — status, the batch, and its ledger rows — which is how an
  // application does it, and what makes a failed batch roll its ledger rows back with it.
  private def migrate(): Int = transact {
    Migrator.status(dir) match {
      case Migrator.Status.Tampered(problems) => fail(problems.mkString("\n"))
      case Migrator.Status.Ok(pending)        => Migrator.apply(pending); pending.size
    }
  }

  test("freeze then migrate replays from empty and lands exactly on the model") {
    // DESIGN §1, property 4.
    freeze("initial", SchemaSnap(Nil), Library.snapshot)
    assertEquals(migrate(), 1)
    assertEquals(DeployCheck.verify(db, Library.snapshot, pgSchema), Right(()))
    assertEquals(Differ.diff(live(), Library.snapshot), Nil)
  }

  test("a second migration applies on top of the first") {
    val v1 = snap(tbl("author", id, col("name")))
    val v2 = snap(tbl("author", id, col("name"), col("country", nullable = true)))

    freeze("initial", SchemaSnap(Nil), v1)
    assertEquals(migrate(), 1)
    assertEquals(Differ.diff(live(), v1), Nil)

    freeze("add country", v1, v2)
    assertEquals(migrate(), 1)
    assertEquals(Differ.diff(live(), v2), Nil)
    assertEquals(transact { Migrator.applied() }.map(_.number), List(1, 2))
  }

  test("nothing is pending after a successful migrate") {
    freeze("initial", SchemaSnap(Nil), Library.snapshot)
    migrate(): Unit
    assertEquals(transact { Migrator.status(dir) }, Migrator.Status.Ok(Nil))
  }

  test("the ledger records the number, name and fingerprint of what ran") {
    val file = freeze("initial", SchemaSnap(Nil), Library.snapshot)
    migrate(): Unit
    val Right(stmts) = Migration.verify(Files.readString(file)): @unchecked
    val applied      = transact { Migrator.applied() }
    assertEquals(applied.map(a => (a.number, a.name)), List((1, "initial")))
    assertEquals(applied.head.fingerprint, Migration.fingerprint(stmts))
  }

  test("an edited migration halts before executing anything") {
    // DESIGN §1, property 5, and §3.5: migrations are generated artifacts.
    val file = freeze("initial", SchemaSnap(Nil), Library.snapshot)
    Files.writeString(file, Files.readString(file).replace(""""author"""", """"writer""""))

    transact { Migrator.status(dir) } match {
      case Migrator.Status.Ok(_)              => fail("an edited migration was accepted")
      case Migrator.Status.Tampered(problems) =>
        assert(problems.exists(_.contains("fingerprint")), problems.mkString("\n"))
    }
    assertEquals(live().tables, Nil, "nothing may run when the integrity check fails")
  }

  test("editing a migration after it has been applied is detected too") {
    val file = freeze("initial", SchemaSnap(Nil), Library.snapshot)
    migrate(): Unit
    val stmts = Migration.verify(Files.readString(file)).toOption.get
    val extra = stmts :+ """create table "sneaky" ("id" uuid)"""
    Files.writeString(file, Migration(1, "initial", extra, Migration.fingerprint(extra)).render)

    transact { Migrator.status(dir) } match {
      case Migrator.Status.Ok(_)              => fail("a rewritten applied migration was accepted")
      case Migrator.Status.Tampered(problems) =>
        assert(
          problems.exists(_.contains("the file changed after being applied")),
          problems.mkString("\n")
        )
    }
  }

  test("a migration recorded as applied but missing from disk is reported") {
    val file = freeze("initial", SchemaSnap(Nil), Library.snapshot)
    migrate(): Unit
    Files.delete(file)
    transact { Migrator.status(dir) } match {
      case Migrator.Status.Ok(_)              => fail("a missing migration was not reported")
      case Migrator.Status.Tampered(problems) =>
        assert(problems.exists(_.contains("no file exists on disk")), problems.mkString("\n"))
    }
  }

  test("a failing migration rolls back every statement in its batch") {
    // DESIGN §3.7: Postgres has transactional DDL and Migrator leans on it deliberately.
    val stmts = List("""create table "t1" ("id" uuid primary key)""", "this is not valid sql")
    intercept[java.sql.SQLException](transact { Migrator.apply(List((1, "0001_bad.sql", stmts))) })
    assertEquals(live().tables.map(_.name), Nil, "the first statement was not rolled back")
    assertEquals(
      transact { Migrator.applied() },
      Nil,
      "a failed migration must not enter the ledger"
    )
  }

  test("a migration that fails leaves the database migratable afterwards") {
    val bad = List("""create table "t1" ("id" uuid primary key)""", "nonsense")
    intercept[java.sql.SQLException](transact { Migrator.apply(List((1, "0001_bad.sql", bad))) })
    freeze("initial", SchemaSnap(Nil), Library.snapshot)
    assertEquals(migrate(), 1)
    assertEquals(Differ.diff(live(), Library.snapshot), Nil)
  }

  test("migrate verifies against the model, and notices when the file set is short") {
    val v1 = snap(tbl("author", id, col("name")))
    val v2 = snap(tbl("author", id, col("name"), col("country", nullable = true)))
    freeze("initial", SchemaSnap(Nil), v1)
    migrate(): Unit
    // The model moved on but nobody froze it: replaying every file still lands on v1.
    assertEquals(DeployCheck.verify(db, v2, pgSchema).isLeft, true)
    assertEquals(DeployCheck.verify(db, v1, pgSchema), Right(()))
  }
}
