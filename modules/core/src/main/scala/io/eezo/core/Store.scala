package io.eezo.core

/** The rows of one model, read and written by the derived seven.
  *
  * It lives in `core` because it is the one seam `http` and `db` have to meet at, and they are
  * siblings that never see each other: `db` produces one of these from a `Table[A]`, `http`
  * consumes one in [[io.eezo.http.Resource]], and the generated route table is the only place both
  * names appear. A trait rather than a class, because two implementations exist and nobody writes a
  * third by hand.
  *
  * **Its charter is the vocabulary the derivation can speak, not eezo's data-access API.** Five
  * operations, one per thing the seven routes do, and no sixth. A route that wants a filter, a sort
  * or a page is a handwritten route using db's query DSL directly, and it never touches this trait.
  * Widening it to serve one such route would put a second query language in the most expensive
  * module in the build to change.
  *
  * Nothing here throws. A missing row is a `false` or a `None`, which [[io.eezo.http.Resource]]
  * turns into a 404; an implementation that raised would be deciding an HTTP status one layer below
  * the layer that knows what HTTP is.
  *
  * @tparam A
  *   the model, whose key is `Id[A]`. Per model rather than one instance holding every model's
  *   rows: a store that needs telling which bucket to look in is a store that cannot be typed.
  */
trait Store[A] {

  /** Every row, ordered by primary key.
    *
    * The order is part of the contract rather than an implementation's habit, because a derived
    * `index` renders it and a page that reshuffles itself on every write is a page nobody can read.
    * Primary key rather than insertion, because insertion order is the one order a JDBC
    * implementation cannot reproduce without a column invented to hold it, and a promise only one
    * side can keep is not a seam.
    */
  def all(): Seq[A]

  /** The row under `key`, or nothing. */
  def find(key: Id[A]): Option[A]

  /** Adds a row.
    *
    * `Unit`, not an outcome: every key a derived `create` inserts is an `Id.gen()` minted
    * milliseconds earlier, so a taken key is unreachable and a conflict result would be a branch
    * that can never be taken.
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
