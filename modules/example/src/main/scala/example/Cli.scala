package example

import io.eezo.db.{DbApp, Schema}

/** The demo app's entry point.
  *
  * This object used to *be* the CLI: `status`, `sync`, `freeze`, `migrate` and the rest were
  * implemented here, printing as they went, and `design/cli.md` §3 named it the specification for
  * `modules/cli`. That port happened. The command bodies live in `io.eezo.db.cli.Commands` as
  * functions returning values, the rendering in `io.eezo.db.cli.Render`, and the dispatch in
  * `io.eezo.db.DbApp`; what remains here is exactly what an application is supposed to write:
  * name the schema, and say what `boot` does.
  *
  *   sbt "example/run status" sbt "example/run migrate --apply"
  */
object Cli extends DbApp {

  // One source of truth for connection settings: `Db` already reads these, and `Db.withConnection`
  // is still how the chapters that demonstrate raw JDBC get a connection.
  override def databaseUrl: String      = Db.url
  override def databaseUser: String     = Db.user
  override def databasePassword: String = Db.pass

  override def schema: Schema = AppSchema

  override def boot(): Unit = {
    println("eezo example — run a command: status, sync, freeze, migrate, reset, drop, dump, ddl, help")
  }
}
