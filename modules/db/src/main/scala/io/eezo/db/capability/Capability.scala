package io.eezo.db.capability

import java.sql.Connection
import scala.annotation.implicitNotFound

/** Read access to the database.
  *
  */
@implicitNotFound(
  "no database scope here.\n" +
    "Take `(using DB)` if your caller has one, or run inside `read { ... }` / `transact { ... }`."
)
sealed trait DBCap {
  private[eezo] def connection: Connection
}

/** Read and write access. `TxCap <: DBCap`, so reads work inside a transaction and writes do not
  * work inside a read.
  */
@implicitNotFound(
  "this writes to the database, which requires a transaction.\n" +
    "Take `(using Tx)` if your caller has one, or run inside `transact { ... }`.\n" +
    "A `DB` is not enough: reads work inside a transaction, writes do not work inside a read."
)
sealed trait TxCap extends DBCap

/** The spellings users write so users don't have to write ^
  */
type DB = DBCap^
type Tx = TxCap^

/** The one door to the JDBC handle, and both runtime checks live in it.
  *
  * Nothing in eezo may cache what comes out of `connection`: a cursor holds its scope and re-enters
  * the accessor per use, so that a retired or off-thread scope is caught at the point of use rather
  * than silently working against a connection that now belongs to someone else.
  */
private[eezo] sealed abstract class Handle(c: Connection) {

  /** A virtual thread's name is empty unless one was given, and "used from ." helps nobody. */
  private def name(t: Thread): String =
    if (t.getName.isEmpty) s"an unnamed virtual thread (#${t.threadId})" else t.getName

  private val owner        = Thread.currentThread()
  @volatile private var on = true

  /** Called by the scope that opened this handle, in a `finally`. */
  private[eezo] def retire(): Unit = { on = false }

  protected def checked: Connection = {
    if (!on)
      throw EscapedScope(
        "this scope has outlived the block that created it. Its connection has been returned to " +
          "the pool and may now belong to someone else's transaction.\n" +
          "A value returned from `transact` or `read` must not hold the scope — a lazy Iterator " +
          "or LazyList over a ResultSet is the usual cause. Force it inside the block."
      )
    else if (Thread.currentThread() ne owner)
      throw OffThread(
        s"this scope was opened on ${name(owner)} and is being used from " +
          s"${name(Thread.currentThread())}. A JDBC connection is not safe for concurrent use.\n" +
          "Do the work on the opening thread, or give the task its own scope."
      )
    else c
  }
}

private[eezo] final class ReadHandle(c: Connection) extends Handle(c) with DBCap {
  private[eezo] def connection: Connection = checked
}

private[eezo] final class TxHandle(c: Connection) extends Handle(c) with TxCap {
  private[eezo] def connection: Connection = checked
}

/** A scope used after the block that created it returned. */
final case class EscapedScope(msg: String) extends IllegalStateException(msg)

/** A scope used from a thread other than the one that opened it. */
final case class OffThread(msg: String) extends IllegalStateException(msg)
