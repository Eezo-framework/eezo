package example

import java.sql.{Connection, DriverManager}

/** Connection plumbing for the example app.
  *
  * Deliberately not part of the `db` module: every entry point there takes a `Connection`
  * (`Introspect.snapshot(c)`, `Migrator.apply(c, _)`), so the library never needs to know
  * how one is obtained. Reading a URL into a `val` at object init also can't serve a test
  * whose database doesn't exist until a container starts.
  */
object Db {

  val url: String  = sys.env.getOrElse("EEZO_DB_URL", "jdbc:postgresql://localhost:5442/eezo")
  val user: String = sys.env.getOrElse("EEZO_DB_USER", "postgres")
  val pass: String = sys.env.getOrElse("EEZO_DB_PASS", "postgres")

  def connect(): Connection = DriverManager.getConnection(url, user, pass)

  def withConnection[A](f: Connection => A): A = {
    val c = connect()
    try f(c)
    finally c.close()
  }
}
