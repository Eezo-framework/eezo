package io.eezo.http

import scala.collection.mutable

import io.eezo.core.{Id, Store}

/** The in-memory half of [[io.eezo.core.Store]]: where a model's rows live when nothing persists
  * them.
  *
  * It is what a model deriving `Form, Resource` and no `Table` gets, so the seven routes mount and
  * serve without a database anywhere in the build. The JDBC half lives in `db`, and the generated
  * route table is where the compiler picks between them per model.
  *
  * Named for its implementation rather than `Store`, so that `core`'s trait is the only thing in
  * the build called `Store` and a call site never needs a package to say which one it means. The
  * type is public only because generated code has to name it. No user writes it by hand.
  */
object InMemoryStore {

  /** A store of its own, sharing nothing with any other. That is what makes a test that calls the
    * generated `Routes.table()` start from an empty world with no reset step.
    */
  def apply[A](): Store[A] = new Impl[A]

  /** Rows in a map ordered by the **text** of the key, which is what makes this implementation and
    * a `select * order by id` agree.
    *
    * Not `UUID`'s own ordering: `UUID.compareTo` compares two signed longs, while Postgres compares
    * the sixteen bytes unsigned, so the two disagree about any pair straddling a sign bit. A
    * canonical UUID string is fixed-width lowercase hex with its dashes always in the same places,
    * so comparing that text is comparing those bytes, and the seam's ordering promise holds across
    * both implementations rather than only across the rows a test happened to pick.
    *
    * One lock over the whole store rather than a concurrent map: handlers run on virtual threads,
    * and `all()` iterating while another thread inserts is the corruption this exists to prevent,
    * which a per-operation atomicity guarantee does not cover.
    */
  private final class Impl[A] extends Store[A] {

    private val rows: mutable.TreeMap[String, A] = mutable.TreeMap.empty

    def all(): Seq[A] = synchronized(rows.values.toVector)

    def find(key: Id[A]): Option[A] = synchronized(rows.get(slot(key)))

    def insert(key: Id[A], row: A): Unit =
      synchronized {
        val _ = rows.put(slot(key), row)
      }

    def update(key: Id[A], row: A): Boolean =
      synchronized {
        val at = slot(key)
        if (!rows.contains(at)) false
        else {
          val _ = rows.put(at, row)
          true
        }
      }

    def delete(key: Id[A]): Boolean = synchronized(rows.remove(slot(key)).isDefined)

    private def slot(key: Id[A]): String = key.show
  }
}
