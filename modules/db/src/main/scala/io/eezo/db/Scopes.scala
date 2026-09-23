package io.eezo.db

import io.eezo.db.capability.*
import io.eezo.db.engine.{Run, Scope}
import scala.annotation.publicInBinary
import scala.compiletime.{error, summonFrom}
import scala.reflect.TypeTest
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

  /** A sub unit that may fail and be recovered, on a savepoint. DESIGN §8.12.
    *
    * The savepoint owns only the failure its caller names, because recovering is a claim that the
    * transaction is still fine to use. A duplicate key is something the caller expected and knows
    * how to answer; a defect, or a scope guard's refusal, is something nobody inside the
    * transaction planned for, and turning it into a value would let the request carry on as if it
    * had. So anything else thrown inside still ends the request, with the enclosing `transact`
    * rolling back. Naming a broad type such as `RuntimeException` brings that swallowing back,
    * guard refusals included, which is why the choice is left in plain sight at the call site.
    *
    * `attempt[E]` returns an [[Attempt]] rather than taking the body itself because Scala 3 cannot
    * apply one type argument and infer the other: with `[E, A]` on one method every caller would
    * have to spell out `A` as well. A bare `attempt { ... }` would infer `E` as Nothing and own no
    * failure at all, while reading as if it recovered; naming Throwable would own every failure.
    * Neither is a choice the caller made, so both are refused at compile time.
    *
    * Never implicit in nesting: past 64 subtransactions PGPROC's cached subxid array overflows and
    * every visibility check hits the subtrans SLRU, which is a cliff for a savepoint per iteration.
    */
  inline def attempt[E <: Throwable](using TypeTest[Throwable, E]): Attempt[E] =
    summonFrom {
      case _: (E =:= Throwable) => nameTheFailure()
      case _: (E =:= Nothing)   => nameTheFailure()
      case _                    => new Attempt[E]
    }

  private inline def nameTheFailure(): Nothing =
    error(
      "name the failure this savepoint owns: `attempt[SQLException] { ... }`.\n" +
        "Anything else thrown inside still rolls the transaction back."
    )

  /** The second half of [[attempt]], split off only so the caller names `E` and `A` is inferred.
    *
    * The failure is judged by a TypeTest rather than a ClassTag because a union such as
    * `SQLException | TimeoutException` has no single class: its ClassTag is the common superclass,
    * Exception, and would quietly own every defect the caller never named.
    *
    * The type stays public, so a caller can hold one and call `apply`, but the constructor does
    * not: a public one would let `new Attempt[Throwable]` or `new Attempt[Nothing]` build the same
    * unguarded savepoint that `attempt`'s `summonFrom` refuses to hand out. `publicInBinary` is
    * there only because `attempt` is `inline` and expands `new Attempt[E]` at the call site; without
    * it the private constructor cannot be reached from outside `Scopes` at all.
    */
  final class Attempt[E <: Throwable] @publicInBinary private[Scopes] (using
      owns: TypeTest[Throwable, E]
  ) {

    /** The savepoint is rolled back before the failure is judged, so the sub unit's writes are
      * gone whether it comes back as a `Left` or keeps travelling. If that rollback fails the
      * transaction is no longer where the caller thinks, so nothing is owned: the body's failure
      * travels on, carrying the rollback's as suppressed.
      */
    def apply[A](body: Tx ?-> A)(using tx: Tx): Either[E, A] = {
      val c  = tx.connection
      val sp = c.setSavepoint()
      try {
        val a = body(using tx)
        c.releaseSavepoint(sp)
        Right(a)
      } catch {
        case NonFatal(e) =>
          try c.rollback(sp)
          catch {
            case NonFatal(r) =>
              e.addSuppressed(r)
              throw e
          }
          e match {
            case owns(owned) => Left(owned)
            case _           => throw e
          }
      }
    }
  }

  /** A transaction that commits independently of the caller's.
    *
    * Takes the body rather than merely clearing the marker, because a lexically nested `transact`
    * would be rejected by `summonFrom` — so `detached` opens the scope itself.
    */
  def detached[A](body: Tx ?-> A): A = Scope.detached(Run.tx(body))
}
