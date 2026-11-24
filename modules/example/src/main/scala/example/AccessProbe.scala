package example

import io.eezo.db.*
import io.eezo.db.Scopes.*

/** The API as an application sees it: one import, no `Database`, no capability threaded. */
object AccessProbe {
  def needsTx()(using Tx): Unit = ()
  def go(): Unit                = transact { needsTx() }
}
