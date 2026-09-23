package io.eezo.db.engine

import io.eezo.db.capability.*

/** The non-inline hops behind `Scopes.transact` and `Scopes.read`.
  *
  * This is where the **capture contract** lives (DESIGN §8.4): `body: Tx ?-> A` on a *non-inline*
  * method is what makes a scope unable to escape its block. An inline entry delegating to another
  * inline method drops that contract and every capture guarantee with it, while still compiling —
  * so the signatures below are load-bearing, and the split is not tidiness.
  *
  * **These must stay public.** `private[eezo]` compiles — Scala 3 synthesizes inline accessors —
  * and then fails at run time with `NoClassDefFoundError: io/eezo/db/engine`, because the expansion
  * baked into the caller reaches for the package as a class. Measured both ways on a clean build.
  *
  * It is worth knowing how this hid twice. The expansion lives in the *caller's* bytecode, so
  * changing visibility here only shows up once every caller recompiles; an incremental build keeps
  * the old expansion and reports whatever the previous setting did. That is also why renaming this
  * package once appeared to fix it — the rename forced the full recompile, and the name was never
  * involved.
  */
object Run {

  def tx[A](body: Tx ?-> A): A =
    Scope.enter(ScopeKind.Write) {
      val db = Installed.get
      val c  = db.pool.acquire()
      val h  = new TxHandle(c)
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
