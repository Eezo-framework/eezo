package io.eezo.cli

import io.eezo.db.DB

import java.sql.Connection

/** The one place layer 1 reaches under a capability for a raw `Connection`.
  *
  * It exists because the db module is half-ported to capabilities (design/cli.md §3):
  * `Introspect.snapshot` and `DeployCheck.verify` still take a `Connection`, so a command holding a
  * `DB` has to unwrap it to call them. `io.eezo.cli` is inside `io.eezo`, which is what lets it
  * read `DBCap`'s `private[eezo] def connection`; no user code can do the same.
  *
  * When those two entry points take capabilities, this object dies and the commands stop naming
  * `Connection` at all. Keep every unwrap here so that deletion is the whole port.
  */
private[cli] object Conn {
  def connection(using db: DB): Connection = db.connection
}
