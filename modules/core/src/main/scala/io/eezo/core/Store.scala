package io.eezo.core

/** The rows of one model, read and written by the derived seven.
  *
  * Five operations, one for each thing the seven routes do. A route that wants a filter, a sort or
  * a page is a handwritten route that uses the query DSL of the database layer directly.
  *
  * Nothing here throws. A missing row is a `false` or a `None`, which [[io.eezo.http.Resource]]
  * turns into a 404.
  *
  * @tparam A
  *   the model, whose key is `Id[A]`.
  */
trait Store[A] {

  /** Every row, ordered by primary key. The order is part of the contract. */
  def all(): Seq[A]

  /** The row under `key`, or nothing. */
  def find(key: Id[A]): Option[A]

  /** Adds a row under a key that the store does not hold, such as a key from `Id.gen()`. What a
    * store does with a key that it holds already is not part of this contract.
    */
  def insert(key: Id[A], row: A): Unit

  /** Replaces the row under `key`. `false` when there is none, which is what a derived `update`
    * turns into a 404 without reading the row back first.
    */
  def update(key: Id[A], row: A): Boolean

  /** Removes the row under `key`. `false` when there was none, which is the derived `destroy`'s
    * 404.
    */
  def delete(key: Id[A]): Boolean
}
