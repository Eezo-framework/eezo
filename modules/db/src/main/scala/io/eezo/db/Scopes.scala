package io.eezo.db

import io.eezo.db.capability.*
import io.eezo.db.engine.{Run, Scope}
import scala.compiletime.{error, summonFrom}
import scala.util.control.NonFatal

/** The scopes: the whole database API's entry points. DESIGN §8.2.
  *
  * `import io.eezo.db.Scopes.*` and write `transact { ... }`; no `Database` is ever named.
  */
object Scopes {

  /** Opens a transaction.
    *
    * `inline` only far enough to run `summonFrom`; the body goes to `Run.tx`, which is where the
    * capture contract lives. See `Run` for why that split is load-bearing rather than tidy.
    */
  inline def transact[A](inline body: Tx ?-> A): A =
    summonFrom {
      // Order matters: TxCap <: DBCap, so the transaction branch has to come first, or every
      // nested transaction would report the read-scope message instead.
      case _: TxCap =>
        error(
          "already inside a transaction: remove this `transact`. Writes here already have the Tx.\n" +
            "For a sub-unit that may fail and be recovered, use `attempt`."
        )
      case _: DBCap =>
        error(
          "cannot open a transaction inside a `read` scope.\n" +
            "Move the `transact` outward — reads work inside a transaction, not the reverse."
        )
      case _ => Run.tx(body)
    }

  /** Opens a read scope. */
  inline def read[A](inline body: DB ?-> A): A =
    summonFrom {
      case _: DBCap => error("already inside a database scope: remove this `read`.")
      case _        => Run.read(body)
    }

  /** A sub-unit that may fail and be recovered, on a savepoint. DESIGN §8.12.
    *
    * Never implicit in nesting: past 64 subtransactions PGPROC's cached subxid array overflows and
    * every visibility check hits the subtrans SLRU, which is a cliff for a savepoint per iteration.
    */
  def attempt[A](body: Tx ?-> A)(using tx: Tx): Either[Throwable, A] = {
    val c  = tx.connection
    val sp = c.setSavepoint()
    try {
      val a = body(using tx)
      c.releaseSavepoint(sp)
      Right(a)
    } catch {
      case NonFatal(e) =>
        c.rollback(sp)
        Left(e)
    }
  }

  /** A transaction that commits independently of the caller's.
    *
    * Takes the body rather than merely clearing the marker, because a lexically nested `transact`
    * would be rejected by `summonFrom` — so `detached` opens the scope itself.
    */
  def detached[A](body: Tx ?-> A): A = Scope.detached(Run.tx(body))
}
