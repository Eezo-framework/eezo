package io.eezo.db.support

import java.net.ServerSocket

/** A place where no database answers, kept apart from [[Pg]] so a suite that needs no Postgres does
  * not reach for the object that owns the container.
  */
object Refusing {

  /** A url on a local port nobody listens on, so every connect is refused at once and what a test
    * measures is the pool's wait rather than the network's.
    */
  def jdbcUrl(): String = {
    val socket = new ServerSocket(0)
    val port   =
      try socket.getLocalPort
      finally socket.close()
    s"jdbc:postgresql://127.0.0.1:$port/none"
  }
}
