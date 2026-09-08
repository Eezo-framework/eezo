package io.eezo.db.cli

import io.eezo.db.Scopes.{read, transact}
import io.eezo.db.migrate.Decision
import io.eezo.db.schema.Change
import io.eezo.db.support.{DbSuite, Library}

import java.nio.file.Files

/** The database-backed commands, against the same testcontainers Postgres `db`'s suites use.
  *
  * `DbSuite` installs a `Database` isolated by Postgres schema and points `search_path` at it, so
  * `transact`/`read` land in `pgSchema` — which is why every call below passes it where
  * `example/Cli.scala` relied on `public`.
  */
class CommandsSuite extends DbSuite {

  test("status: an empty database drifts by every table, and is in sync after create") {
    val before = read { Commands.status(Library, pgSchema) }
    assert(!before.inSync)
    assertEquals(before.changes.count { case _: Change.CreateTable => true; case _ => false }, 3)

    create(Library)
    assert(read { Commands.status(Library, pgSchema) }.inSync)
  }

  test("sync: previews without --apply, refuses destructive without --force, applies with it") {
    create(Library)
    exec("""create table "stray" (id integer primary key)""")

    val preview = transact { Commands.sync(Library, apply = false, force = false, pgSchema) }
    assert(!preview.applied)
    assert(preview.blocked.exists { case Change.DropTable("stray") => true; case _ => false })

    val refused = transact { Commands.sync(Library, apply = true, force = false, pgSchema) }
    assert(!refused.applied)
    assert(refused.blocked.nonEmpty)

    val forced = transact { Commands.sync(Library, apply = true, force = true, pgSchema) }
    assert(forced.applied)
    assert(read { Commands.status(Library, pgSchema) }.inSync)
  }

  test("migrate: pending without --apply, applied and verified with it, then up to date") {
    val dir = Files.createTempDirectory("eezo-cli-migrate")
    assert(Commands.freeze(Library, "init", _ => Decision.Accept, dir).migration.isDefined)

    transact { Commands.migrate(Library, apply = false, dir, pgSchema) } match {
      case MigrateResult.Pending(pending) => assertEquals(pending.map(_.number), List(1))
      case other                          => fail(s"expected Pending, got $other")
    }
    // Nothing ran: the preview left the database untouched.
    assert(!read { Commands.status(Library, pgSchema) }.inSync)

    transact { Commands.migrate(Library, apply = true, dir, pgSchema) } match {
      case MigrateResult.Applied(applied, drift) =>
        assertEquals(applied.map(_.number), List(1))
        assertEquals(drift, Nil)
      case other => fail(s"expected Applied, got $other")
    }

    transact { Commands.migrate(Library, apply = true, dir, pgSchema) } match {
      case MigrateResult.UpToDate(Nil) => ()
      case other                       => fail(s"expected UpToDate with no drift, got $other")
    }
  }

  test("reset: drops everything including the ledger and recreates from the model") {
    create(Library)
    exec(
      """create table "stray" (id integer primary key)""",
      """create table "eezo_migrations" (number integer primary key)"""
    )

    val result = transact { Commands.reset(Library, pgSchema) }
    assert(result.dropped.contains("stray"))

    assert(read { Commands.status(Library, pgSchema) }.inSync)
    // The ledger went too — `Introspect` hides it from snapshots, so ask Postgres directly.
    assert(rejected(exec("""select 1 from "eezo_migrations"""")))
  }
}
