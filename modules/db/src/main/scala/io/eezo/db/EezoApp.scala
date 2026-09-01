package io.eezo.db

import io.eezo.db.engine.Installed

/** The entry point an application extends. DESIGN §8.7.
  *
  * {{{
  * object Main extends EezoApp {
  *   def boot(args: Array[String]): Unit = transact { Table[Book].insert(...) }
  * }
  * }}}
  *
  * It owns the one thing an application should not have to: the `Database`. It is built from the
  * settings [[DbInit]] declares, installed before `boot` runs, and closed after — so a bad
  * `EEZO_DB_URL` fails in `main`, with the config in the stack trace, rather than at whatever
  * moment something first touches a hidden holder.
  *
  * The `Database` is a local of `main`, never a field. §8.10: a capability in a field forces its
  * enclosing object to be one too, and §8.7 is why nothing user-facing names this type at all.
  *
  * **Scope, deliberately.** This lives in `db` because that is where `Database` lives, and it
  * currently owns the database edge only. When the server side needs the same entry point, either
  * this moves to a module that can see both `db` and `http`, or `Eezo.run` becomes something `boot`
  * calls — the shape of `boot` does not change either way.
  */
trait EezoApp extends DbInit {

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
