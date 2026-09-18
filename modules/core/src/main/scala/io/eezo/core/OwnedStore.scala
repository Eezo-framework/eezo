package io.eezo.core

/** The rows of one model, narrowable to the user who owns them.
  *
  * It sits beside [[Store]] rather than inside it because the five operations are the vocabulary
  * the derived seven speak and a sixth would be a query language on the seam. Narrowing is a
  * different question: it answers a `Store[A]` and hands it back, so everything downstream of a
  * narrowing still sees exactly the five, and a derived handler cannot tell a scoped store from a
  * whole one. That is the point. The refusal of a foreign row is then the absence of a row rather
  * than a check somebody has to remember to write.
  *
  * Both halves of `Store` implement it, `db`'s through a `where` on the owner column and `http`'s
  * through a filter, and the generated route table is the only file where both names appear.
  *
  * @tparam V
  *   the owner's own type, which is an `Id[U]` for every caller eezo ships. It is a parameter and
  *   not `Id[?]` because `db` needs a `Column[V]` to bind it, and a wildcard has none.
  */
trait OwnedStore[A, V] {

  /** This owner's rows, as a store of their own.
    *
    * Not memoised. One of these per call is an allocation, and a cache keyed by owner is a map that
    * grows with the number of users and never shrinks.
    */
  def by(owner: V): Store[A]
}

/** Which field of a model records its owner: the name storage knows it by, and the way to read it
  * off a row.
  *
  * Two things because two modules need different halves of the same fact. `db` renders [[name]]
  * into the owner condition of four statements, and `http` calls [[get]] to compare a row's owner
  * with the current user, which is what decides whether a show page offers an edit link. Deriving
  * one from the other is not possible in either direction: a getter cannot be read for a column
  * name at runtime, and a name cannot be turned into a typed getter without a mirror.
  *
  * It is built at compile time, from a field selector, so the name is the model's own field and not
  * a string somebody typed. See `io.eezo.auth.Guard`'s `owning`.
  */
final case class OwnerOf[A, V](name: String, get: A => V)
