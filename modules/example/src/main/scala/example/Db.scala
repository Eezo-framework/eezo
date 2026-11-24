package example

import java.sql.{Connection, DriverManager}

/** The connection settings of the example app, read from `EEZO_DB_URL`, `EEZO_DB_USER` and
  * `EEZO_DB_PASS`. Without them, the settings name the database `eezo` of a local Postgres on
  * port 5442, as user `postgres`.
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
