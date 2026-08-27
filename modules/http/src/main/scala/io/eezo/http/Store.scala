package io.eezo.http

import java.util.UUID

import scala.collection.mutable

import io.eezo.core.Id

/** Where the derived seven keep their rows, until `modules/db` has a query runtime.
  *
  * It is **app-level and invisible**. There is no `Store[A]`: a store per model would be a second
  * per-model description of the model beside `Table[A]`, one to SQL and one to a `HashMap`, and a
  * connection pool is app-level in every design `research/db-query-layer.md` weighs. The type is
  * public only because generated code has to name it, in `Routes.table()` and in the
  * `Resource#routes(store)` it calls; no user writes it by hand, because that generated `table()`
  * constructs the one instance. So eezo does not teach a `given Store = Store.inMemory()` line that
  * it has to un-teach a few weeks later.
  *
  * A `final class` rather than a trait, deliberately. The seam between eezo and a real query
  * runtime cannot be designed against one implementation, and that one a `HashMap`; class to trait
  * is source-compatible for code that only names the type and calls the factory, so nothing here
  * forecloses it.
  *
  * The five operations are the entire vocabulary a derived handler has. A route that wants a sixth
  * is a signal to revisit the route rather than to widen the store. Nothing here throws: the
  * failure channel is `Errors.scala`, and a store that raised `NotFound` would be deciding an HTTP
  * status one layer too low.
  */
final class Store private (
    private val buckets: mutable.Map[String, mutable.LinkedHashMap[UUID, Any]]
) {

  /** Every row in a bucket, in the order it was inserted.
    *
    * Insertion order rather than key order, because keys are random UUIDs and a derived `index`
    * that reshuffles itself on every write is a page nobody can read.
    */
  def list[A](bucket: String): Seq[A] =
    synchronized(rows(bucket).values.toVector.map(_.asInstanceOf[A]))

  def get[A](bucket: String, key: Id[A]): Option[A] =
    synchronized(rows(bucket).get(key.value).map(_.asInstanceOf[A]))

  /** Adds a row.
    *
    * `Unit`, not an outcome: every key a derived `create` inserts is an `Id.gen()` minted
    * milliseconds earlier, so a taken key is unreachable and a conflict result would be a branch
    * that can never be taken.
    */
  def insert[A](bucket: String, key: Id[A], row: A): Unit =
    synchronized {
      val _ = rows(bucket).put(key.value, row)
    }

  /** Replaces a row, keeping its place in the order. `false` when there is no such row, which is
    * what a derived `update` turns into a 404 without a read first.
    */
  def update[A](bucket: String, key: Id[A], row: A): Boolean =
    synchronized {
      val bucketRows = rows(bucket)
      if (!bucketRows.contains(key.value)) false
      else {
        val _ = bucketRows.put(key.value, row)
        true
      }
    }

  /** Removes a row. `false` when there was none, which is the derived `destroy`'s 404. */
  def delete[A](bucket: String, key: Id[A]): Boolean =
    synchronized(rows(bucket).remove(key.value).isDefined)

  /** The bucket, created empty on first touch. Called only under the lock above.
    *
    * One lock over the whole store rather than one per bucket: handlers run on virtual threads and
    * a map both mutated and iterated from several of them at once is the corruption this exists to
    * prevent, and a throwaway holding a demo's rows has no contention worth splitting it for.
    */
  private def rows(bucket: String): mutable.LinkedHashMap[UUID, Any] =
    buckets.getOrElseUpdate(bucket, mutable.LinkedHashMap.empty)
}

object Store {

  /** A store of its own, sharing nothing with any other. That is what makes a test that calls the
    * generated `Routes.table()` start from an empty world with no reset step.
    */
  def inMemory(): Store = new Store(mutable.Map.empty)
}
