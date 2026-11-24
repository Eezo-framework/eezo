package example

import io.eezo.db.{DbApp, Schema}

/** The example app's entry point. It names the schema and says what `boot` does; `DbApp` gives it
  * the `db` commands.
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
