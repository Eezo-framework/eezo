package io.eezo.db

import io.eezo.core.support.Captured.captured
import io.eezo.db.Scopes.read
import io.eezo.db.cli.Commands
import io.eezo.db.engine.Installed
import io.eezo.db.support.{Library, Pg, PgSuite}

import java.sql.Connection

/** The database layer's entry trait: what `sbt "run <command>"` answers on an application that has
  * only this layer, against the same testcontainers Postgres `db`'s suites use.
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

    override def boot(): Unit = {
      ran = true
      sawDatabase = read { Commands.status(schema, databaseSchema) }.changes.nonEmpty
    }
  }

  private def installed: Boolean = Installed.installed

  /** An application that names its Postgres schema and nothing else: no `databaseInit`, so whatever
    * puts its tables in that schema is the framework's doing. The schema is one that does not exist
    * yet, so the framework has to create it, and the test drops it after.
    */
  private class Named(name: String, user: String = Pg.username, password: String = Pg.password)
      extends DbApp {
    override def schema: Schema         = Library
    override def databaseSchema: String = name

    override def databaseUrl: String      = Pg.jdbcUrl
    override def databaseUser: String     = user
    override def databasePassword: String = password

    override def boot(): Unit = ()
  }

  /** The same application with a hook of its own for something unrelated to the schema, written
    * without `super`, the way an application adds `application_name` and forgets the rest.
    */
  private class NamedWithHook(name: String) extends Named(name) {
    override def databaseInit: Connection -> Unit = c => {
      val st = c.createStatement()
      try st.execute("set application_name to 'eezo-dbappsuite'"): Unit
      finally st.close()
    }
  }

  /** The schemas that hold a table called `table`, read from the catalog. */
  private def schemasHolding(table: String): Set[String] = {
    val ps =
      db.prepareStatement("select table_schema from information_schema.tables where table_name = ?")
    try {
      ps.setString(1, table)
      val rs = ps.executeQuery()
      try Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toSet
      finally rs.close()
    } finally ps.close()
  }

  /** Runs `f` with `name` absent before and dropped after, so no later suite sees its tables. */
  private def inFreshSchema(name: String)(f: => Unit): Unit = {
    val drop = s"""drop schema if exists "${name.replace("\"", "\"\"")}" cascade"""
    exec(drop)
    try f
    finally exec(drop)
  }

  test("databaseSchema alone puts the tables in that schema, creating it, and status agrees") {
    val name = pgSchema + "_own"
    inFreshSchema(name) {
      val app = new Named(name)
      assertEquals(captured(app.run(List("db", "sync", "--apply")))._1, 0)
      assertEquals(schemasHolding("author"), Set(name))
      val (code, out, _) = captured(app.run(List("db", "status")))
      assertEquals(code, 0, out)
    }
  }

  test("a role that owns its schema but cannot create one on the database still connects") {
    val name = pgSchema + "_owned"
    val role = pgSchema + "_role"
    inFreshSchema(name) {
      exec(
        s"""drop role if exists "$role"""",
        s"""create role "$role" login password 'owner'""",
        s"""create schema "$name" authorization "$role""""
      )
      try {
        val app = new Named(name, user = role, password = "owner")
        assertEquals(captured(app.run(List("db", "sync", "--apply")))._1, 0)
        assertEquals(schemasHolding("author"), Set(name))
      } finally {
        exec(s"""drop schema if exists "$name" cascade""", s"""drop role if exists "$role"""")
      }
    }
  }

  test(
    "a databaseInit of the application's own, without super, keeps the tables in databaseSchema"
  ) {
    val name = pgSchema + "_hook"
    inFreshSchema(name) {
      val app = new NamedWithHook(name)
      assertEquals(captured(app.run(List("db", "sync", "--apply")))._1, 0)
      assertEquals(schemasHolding("author"), Set(name))
      val (code, out, _) = captured(app.run(List("db", "status")))
      assertEquals(code, 0, out)
    }
  }

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

  test("the http layer's commands are unknown on a database-only application") {
    val app = new Job
    List("routes", "dev").foreach { command =>
      val (code, out, err) = captured(app.run(List(command)))
      assertEquals(code, 2, command)
      assertEquals(out, "", command)
      assert(err.contains(s"unknown command: $command"), err)
    }
    assert(!app.ran)
  }

  test("help names this layer's commands under db and not the http layer's, with the note last") {
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
