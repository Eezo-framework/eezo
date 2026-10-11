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

  /** A database only application whose program records that it ran under an installed database. */
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

  test("db status is exit 1 on drift and exit 0 once db sync --apply converged the database") {
    val app               = new Job
    val (drifted, out, _) = captured(app.run(List("db", "status")))
    assertEquals(drifted, 1)
    assert(out.contains("difference(s) between model and database"), out)

    val (json, jsonOut, _) = captured(app.run(List("db", "status", "--json")))
    assertEquals(json, 1)
    assert(jsonOut.contains("\"command\": \"status\""), jsonOut)

    assertEquals(captured(app.run(List("db", "sync", "--apply")))._1, 0)
    assertEquals(captured(app.run(List("db", "status")))._1, 0)
    assert(!installed)
  }

  test("the bare spellings still answer, for release commands written before the db namespace") {
    val app            = new Job
    val (code, out, _) = captured(app.run(List("status")))
    assertEquals(code, 1)
    assert(out.contains("difference(s) between model and database"), out)
    assertEquals(captured(app.run(List("ddl")))._1, 0)
    assert(!installed)
  }

  test("db alone and db help list the namespace; an unknown db command is exit 2") {
    val app            = new Job
    val (code, out, _) = captured(app.run(List("db")))
    assertEquals(code, 0)
    val lines = out.linesIterator.toList
    assertEquals(lines.head, "eezo db <command>")
    List("status", "sync", "freeze", "migrate", "reset", "drop", "dump", "ddl").foreach { c =>
      assert(lines.exists(_.trim.startsWith(c)), s"$c missing from:\n$out")
    }
    assert(!out.contains("routes"), out)
    assertEquals(captured(app.run(List("db", "help")))._2, out)

    val (unknown, none, err) = captured(app.run(List("db", "bogus")))
    assertEquals(unknown, 2)
    assertEquals(none, "")
    assert(err.contains("unknown db command: bogus"), err)
    assert(!installed)
  }

  test("db freeze without a name is a SchemaError: exit 1, on stderr, JSON under --json") {
    val app              = new Job
    val (code, out, err) = captured(app.run(List("db", "freeze")))
    assertEquals(code, 1)
    assertEquals(out, "")
    assert(err.contains("freeze needs a name"), err)

    val (_, _, jsonErr) = captured(app.run(List("db", "freeze", "--json")))
    assert(jsonErr.contains("\"error\": \"freeze needs a name"), jsonErr)
  }

  test("db ddl and db dump answer from the model alone") {
    val app            = new Job
    val (code, ddl, _) = captured(app.run(List("db", "ddl")))
    assertEquals(code, 0)
    assert(ddl.contains("create table"), ddl)

    val (dumpCode, dump, _) = captured(app.run(List("db", "dump")))
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

  test("help names this edge's commands under db and not the http edge's, with the note last") {
    val (code, out, _) = captured(new Job().run(List("help")))
    assertEquals(code, 0)
    val lines = out.linesIterator.toList
    List("status", "sync", "freeze", "migrate", "reset", "drop", "dump", "ddl").foreach { c =>
      assert(lines.exists(_.trim.startsWith(s"db $c")), s"db $c missing from:\n$out")
    }
    assert(lines.exists(_.trim.startsWith("help")), out)
    // The note is a caveat on the whole table, so it follows every row, `help`'s included.
    val note = lines.indexWhere(_.startsWith("db freeze takes --accept-all"))
    val help = lines.indexWhere(_.trim.startsWith("help"))
    assert(note > help, out)
    assert(!out.contains("routes"), out)
    assert(!lines.exists(_.trim.startsWith("dev")), out)
  }
}
