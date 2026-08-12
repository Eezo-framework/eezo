package io.eezo.db

import java.sql.{Connection, DriverManager}

object Db {
  // JDBC 4 auto-registration runs `DriverManager`'s service-loader scan exactly once per JVM,
  // against whatever the calling thread's context classloader sees at that first call. Under
  // `sbt run` (unforked), that first call can happen from a different project's classpath than
  // this one, and the scan never reruns, so pgjdbc quietly never registers. Forcing the class to
  // load here calls `Driver`'s static initializer, which registers it directly and sidesteps that
  // whole ordering problem.
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
