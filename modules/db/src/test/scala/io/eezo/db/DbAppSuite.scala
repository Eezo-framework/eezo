package io.eezo.db

import io.eezo.core.support.Captured.captured
import io.eezo.db.Scopes.read
import io.eezo.db.cli.Commands
import io.eezo.db.engine.Installed
import io.eezo.db.support.{Library, Pg, PgSuite}

import java.sql.Connection

/** The database edge's entry trait: what `sbt "run <command>"` answers on an application that has
  * only this edge, against the same testcontainers Postgres `db`'s suites use.
  *
  * `PgSuite` rather than `DbSuite`, because installing the `Database` is exactly what `DbApp` is
  * being tested for: it builds one from `DbInit`'s overrides, installs it around the command, and
  * uninstalls it after. A suite that had already installed one would hide all three.
  */
class DbAppSuite extends PgSuite {

  /** A database-only application whose program records that it ran under an installed database. */
  private class Job extends DbApp {
    var ran         = false
    var sawDatabase = false

    override def schema: Schema         = Library
    override def databaseSchema: String = pgSchema

    override def databaseUrl: String      = Pg.jdbcUrl
    override def databaseUser: String     = Pg.username
    override def databasePassword: String = Pg.password

    override def databaseInit: Connection -> Unit = Pg.searchPath(pgSchema)

    override def boot(): Unit = {
      ran = true
      sawDatabase = read { Commands.status(schema, databaseSchema) }.changes.nonEmpty
    }
  }

  private def installed: Boolean = Installed.installed

  test("no arguments is the program, run with a Database installed and uninstalled after") {
    val app = new Job
    assert(!installed)
    assertEquals(app.run(Nil), 0)
    assert(app.ran)
    assert(app.sawDatabase, "boot queried the live database through the installed Database")
    assert(!installed, "the Database is uninstalled once the program returns")
  }

  test("status is exit 1 on drift and exit 0 once sync --apply converged the database") {
    val app               = new Job
    val (drifted, out, _) = captured(app.run(List("status")))
    assertEquals(drifted, 1)
    assert(out.contains("difference(s) between model and database"), out)

    val (json, jsonOut, _) = captured(app.run(List("status", "--json")))
    assertEquals(json, 1)
    assert(jsonOut.contains("\"command\": \"status\""), jsonOut)

    assertEquals(captured(app.run(List("sync", "--apply")))._1, 0)
    assertEquals(captured(app.run(List("status")))._1, 0)
    assert(!installed)
  }

  test("freeze without a name is a SchemaError: exit 1, the message on stderr, JSON under --json") {
    val app              = new Job
    val (code, out, err) = captured(app.run(List("freeze")))
    assertEquals(code, 1)
    assertEquals(out, "")
    assert(err.contains("freeze needs a name"), err)

    val (_, _, jsonErr) = captured(app.run(List("freeze", "--json")))
    assert(jsonErr.contains("\"error\": \"freeze needs a name"), jsonErr)
  }

  test("ddl and dump answer from the model alone") {
    val app            = new Job
    val (code, ddl, _) = captured(app.run(List("ddl")))
    assertEquals(code, 0)
    assert(ddl.contains("create table"), ddl)

    val (dumpCode, dump, _) = captured(app.run(List("dump")))
    assertEquals(dumpCode, 0)
    assert(dump.contains("\"tables\""), dump)
  }

  test("the http edge's commands are unknown on a database-only application") {
    val app = new Job
    List("routes", "dev").foreach { command =>
      val (code, out, err) = captured(app.run(List(command)))
      assertEquals(code, 2, command)
      assertEquals(out, "", command)
      assert(err.contains(s"unknown command: $command"), err)
    }
    assert(!app.ran)
  }

  test("help names this edge's commands and not the http edge's") {
    val (code, out, _) = captured(new Job().run(List("help")))
    assertEquals(code, 0)
    List("status", "sync", "freeze", "migrate", "reset", "drop", "dump", "ddl").foreach { c =>
      assert(out.contains(c), s"$c missing from:\n$out")
    }
    assert(!out.contains("routes"), out)
    assert(!out.linesIterator.exists(_.trim.startsWith("dev")), out)
  }
}
