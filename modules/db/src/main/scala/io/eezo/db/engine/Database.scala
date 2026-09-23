package io.eezo.db.engine

import java.sql.Connection
import java.time.Duration

/** A database, and the only thing that owns a `Pool`.
  *
  * Users never name this type. It is built once at the edge (`EezoApp` in production, the testkit
  * in tests), installed, and reached only through `transact` and `read`.
  */
final class Database private[eezo] (private[eezo] val pool: Pool) {

  /** Releases what the pool holds. Called by `EezoApp` after `boot` returns. */
  def close(): Unit = pool.close()
}

object Database {

  /** Built at the edge, from parsed config, so that a bad URL fails in `main` with the config parse
    * in the stack trace rather than at whatever moment something first touches a hidden holder.
    */
  def connect(
      url: String,
      user: String,
      password: String,
      init: Connection -> Unit = _ => ()
  ): Database =
    new Database(new Pool(url, user, password, init, size = 10, Duration.ofMillis(5000)))
}

/** Where the installed `Database` lives.
  *
  * `private[eezo]` because installing is the edge's job: `DbApp` in production, the testkit in
  * tests. `Test / parallelExecution := false` is set on the `db` project, which is what makes one
  * install point enough; see the comment on that setting.
  */
private[eezo] object Installed {

  @volatile private var current: Database | Null = null

  private[eezo] def install(d: Database): Unit = { current = d }

  private[eezo] def uninstall(): Unit = { current = null }

  /** Exists so the entry trait suites can observe whether a `Database` was installed around `boot`
    * and `dev`, without a query.
    */
  private[eezo] def installed: Boolean = current != null

  private[eezo] def get: Database = {
    val d = current
    if (d == null)
      throw new IllegalStateException(
        "no database has been installed. In an application this is `DbApp`'s job, and it happens " +
          "before `boot` runs; in a test it is the testkit's. A `transact` reached during static " +
          "initialisation runs before either."
      )
    else d
  }
}
