package io.eezo.db.engine

import io.eezo.db.capability.*

/** The non-inline hops behind `Scopes.transact` and `Scopes.read`.
  *
  * This is where the **capture contract** lives (DESIGN §8.4): `body: Tx ?-> A` on a *non-inline*
  * method is what makes a scope unable to escape its block. An inline entry delegating to another
  * inline method drops that contract and every capture guarantee with it, while still compiling —
  * so the signatures below are load-bearing, and the split is not tidiness.
  *
  * Visibility is not part of that. `transact` is `inline`, so its body is beta-reduced into the
  * caller, which may be any user's file; Scala 3 synthesizes inline accessors for members that are
  * not visible there, so `private[eezo]` is fine. Verified on a clean cross-module build: the
  * `example` project, which is outside `io.eezo`, compiles against these hops unchanged.
  */
private[eezo] object Run {

  def tx[A](body: Tx ?-> A): A =
    Scope.enter(ScopeKind.Write) {
      val db   = Installed.get
      val c    = db.pool.acquire()
      val h    = new TxHandle(c)
      val prev = c.getAutoCommit
      c.setAutoCommit(false)
      try {
        val a = body(using h)
        c.commit()
        a
      } catch {
        case e: Throwable =>
          c.rollback()
          throw e
      } finally {
        h.retire()
        c.setAutoCommit(prev)
        db.pool.release(c)
      }
    }

  def read[A](body: DB ?-> A): A =
    Scope.enter(ScopeKind.Read) {
      val db = Installed.get
      val c  = db.pool.acquire()
      val h  = new ReadHandle(c)
      try body(using h)
      finally {
        h.retire()
        db.pool.release(c)
      }
    }
}
