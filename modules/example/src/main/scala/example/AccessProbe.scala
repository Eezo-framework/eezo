package example

import io.eezo.db.*
import io.eezo.db.Scopes.*

/** The API as an application sees it: one import, no `Database`, no capability threaded.
  *
  * Kept as a compile-time guard on the seam rather than for what it does. It is the only thing that
  * exercises `io.eezo.db.Exports` and the inline entry points from outside `io.eezo`, so it fails
  * if the type aliases stop being exported or an entry point stops being callable from a foreign
  * package.
  */
object AccessProbe {
  def needsTx()(using Tx): Unit = ()
  def go(): Unit                = transact { needsTx() }
}
