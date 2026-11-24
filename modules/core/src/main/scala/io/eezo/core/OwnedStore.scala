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
  * layer calls [[get]] to compare the owner of a row with the current user. Build it from a field
  * selector, with the `owning` of `io.eezo.auth.Guard`.
  */
final case class OwnerOf[A, V](name: String, get: A => V)
