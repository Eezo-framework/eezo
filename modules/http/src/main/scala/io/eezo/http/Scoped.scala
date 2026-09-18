package io.eezo.http

import io.eezo.core.{Id, OwnedStore, Store}

/** The store an owned model's routes are mounted over: the whole table, and the narrowing of it.
  *
  * A `Store[A]` itself, so that [[Resource.routes]] keeps the one store parameter it has always had
  * and the generated route table keeps the row it has always emitted. An owned model's routes are
  * mounted by the same line as every other model's; the difference travels inside the value.
  *
  * The owner's type is gone from the signature on purpose, and this is the one place in eezo where
  * it is. It is `Id[U]` for every caller, `U` is known only where the model's own declaration is
  * written, and nothing between that declaration and here needs to know it: the value that reaches
  * [[by]] is the one [[Owned.owner]] read out of the request, and the function that consumes it is
  * the one [[Scoped.apply]] closed over the matching [[io.eezo.core.OwnedStore]]. The two are built
  * from the same declaration, so the cast inside `apply` cannot see a value of another type.
  *
  * Public only because the generated route table has to name it, the same way [[InMemoryStore]] is.
  * No user writes one.
  */
final class Scoped[A] private (whole: Store[A], narrow: Any => Store[A]) extends Store[A] {

  def all(): Seq[A] = whole.all()

  def find(key: Id[A]): Option[A] = whole.find(key)

  def insert(key: Id[A], row: A): Unit = whole.insert(key, row)

  def update(key: Id[A], row: A): Boolean = whole.update(key, row)

  def delete(key: Id[A]): Boolean = whole.delete(key)

  /** This owner's rows, as a store of their own. */
  def by(owner: Any): Store[A] = narrow(owner)
}

object Scoped {

  /** The pair, from the two halves an edge builds: its plain store and its owner aware one, over
    * the same rows.
    */
  def apply[A, V](whole: Store[A], owned: OwnedStore[A, V]): Scoped[A] =
    new Scoped[A](whole, owner => owned.by(owner.asInstanceOf[V]))
}
