package io.eezo.core

/** The rows of one model, narrowable to the user who owns them.
  *
  * [[by]] answers a plain [[Store]], so a derived handler cannot tell a narrowed store from a whole
  * one, and a row of another owner is simply absent. The database layer narrows with a `where` on
  * the owner column, the in memory store with a filter.
  *
  * @tparam V
  *   the type of the owner, an `Id[U]` for every caller eezo ships.
  */
trait OwnedStore[A, V] {

  /** This owner's rows, as a store of their own. Not memoised: each call builds a new one. */
  def by(owner: V): Store[A]
}

/** Which field of a model records its owner: the name storage knows it by, and the way to read it
  * off a row.
  *
  * The database layer renders [[name]] into the owner condition of its statements, and the http
  * layer calls [[get]] to compare the owner of a row with the current user, so the two must name
  * the same field. Build it from a field selector, as `User.guard.required[Post].owning(_.author)`
  * in `io.eezo.auth` does, and the compiler takes both from that one field. Built by hand, a name
  * that is no column of the table stops the database store at boot, and the name of a different
  * column narrows the rows by that column while the http layer compares through [[get]].
  */
final case class OwnerOf[A, V](name: String, get: A => V)
