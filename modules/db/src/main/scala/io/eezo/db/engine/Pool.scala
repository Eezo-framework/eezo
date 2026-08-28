package io.eezo.db.engine

import java.sql.{Connection, DriverManager}

/** Connections for one database, owned by `Database` and by nothing else. DESIGN §8.7.
  *
  * `init` runs on **every** connection this creates, not once on a borrowed one. That distinction
  * is what makes per-suite `search_path` isolation work: `search_path` is per-connection, so a
  * suite that set it on one borrowed connection would silently read the wrong schema through the
  * next. Production wants the same hook for `application_name` and statement timeouts.
  *
  * Deliberately not a pool yet: `acquire` opens and `release` closes. The interface is the one a
  * real pool needs, so adding reuse later changes this file and nothing else.
  */
final class Pool private[eezo] (
    url: String,
    user: String,
    password: String,
    init: Connection -> Unit
) {

  private[eezo] def acquire(): Connection = {
    val c = DriverManager.getConnection(url, user, password)
    init(c)
    c
  }

  private[eezo] def release(c: Connection): Unit = c.close()

  /** Nothing is retained yet, so nothing to release. Here because the lifecycle is the interface a
    * real pool needs, and `EezoApp` should not learn a new call when one arrives.
    */
  private[eezo] def close(): Unit = ()
}
