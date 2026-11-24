package example

import io.eezo.db.{DbApp, Schema}

/** Runs the `db` commands of the example app against the database that [[Db]] names. That database
  * must be running before you run a command.
  *
  * {{{
  * sbt "example/run db status"
  * sbt "example/run db migrate --apply"
  * }}}
  */
object Cli extends DbApp {

  override def databaseUrl: String      = Db.url
  override def databaseUser: String     = Db.user
  override def databasePassword: String = Db.pass

  override def schema: Schema = AppSchema

  override def boot(): Unit = {
    println(
      "eezo example — run a command: status, sync, freeze, migrate, reset, drop, dump, ddl, help"
    )
  }
}
