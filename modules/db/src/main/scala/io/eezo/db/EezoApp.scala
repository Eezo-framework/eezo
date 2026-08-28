package io.eezo.db

import io.eezo.db.engine.{Database, Installed}

import java.sql.Connection

/** The entry point an application extends. DESIGN §8.7.
  *
  * {{{
  * object Main extends EezoApp {
  *   def boot(args: Array[String]): Unit = transact { Table[Book].insert(...) }
  * }
  * }}}
  *
  * It owns the one thing an application should not have to: the `Database`. It is built from config
  * here, installed before `boot` runs, and closed after — so a bad `EEZO_DB_URL` fails in `main`,
  * with the config in the stack trace, rather than at whatever moment something first touches a
  * hidden holder.
  *
  * The `Database` is a local of `main`, never a field. §8.10: a capability in a field forces its
  * enclosing object to be one too, and §8.7 is why nothing user-facing names this type at all.
  *
  * **Scope, deliberately.** This lives in `db` because that is where `Database` lives, and it
  * currently owns the database edge only. When the server side needs the same entry point, either
  * this moves to a module that can see both `db` and `http`, or `Eezo.run` becomes something `boot`
  * calls — the shape of `boot` does not change either way.
  */
trait EezoApp {

  /** Overridable, so a test or a second environment can point elsewhere without touching `boot`. */
  def databaseUrl: String =
    sys.env.getOrElse("EEZO_DB_URL", "jdbc:postgresql://localhost:5442/eezo")

  def databaseUser: String = sys.env.getOrElse("EEZO_DB_USER", "postgres")

  def databasePassword: String = sys.env.getOrElse("EEZO_DB_PASS", "postgres")

  /** Run on **every** connection the pool creates, not once on a borrowed one — DESIGN §8.7.
    *
    * This is where `search_path`, `application_name` and statement timeouts belong. Without it an
    * application that works in a non-`public` schema silently splits in two: its own connections
    * see one schema and eezo's see another, which shows up as a migration that appears not to have
    * run.
    */
  def databaseInit: Connection -> Unit = _ => ()

  protected def database: Database =
    Database.connect(databaseUrl, databaseUser, databasePassword, databaseInit)

  /** The application. A `Database` is installed for its whole duration. */
  def boot(args: Array[String]): Unit

  final def main(args: Array[String]): Unit = {
    val db = database
    Installed.install(db)
    try boot(args)
    finally {
      Installed.uninstall()
      db.close()
    }
  }
}
