package io.eezo.db

import java.sql.{Connection, DriverManager}

object Db {
  // force a load of the driver
  Class.forName("org.postgresql.Driver")

  val url: String  = sys.env.getOrElse("EEZO_DB_URL", "jdbc:postgresql://localhost:5443/eezo")
  val user: String = sys.env.getOrElse("EEZO_DB_USER", "postgres")
  val pass: String = sys.env.getOrElse("EEZO_DB_PASS", "postgres")

  def connect(): Connection = DriverManager.getConnection(url, user, pass)

  def withConnection[A](f: Connection => A): A = {
    val c = connect()
    try f(c)
    finally c.close()
  }
}
