package io.eezo.http

import scala.collection.mutable

import io.eezo.core.{Id, OwnedStore, OwnerOf, Store}

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

  /** One owner's rows, held by this call alone.
    *
    * Unlike `JdbcStore.owned`, which narrows the very table `JdbcStore[A]()` already reads, this
    * call has nowhere to take somebody else's rows from: its only argument is the `OwnerOf`, so the
    * map underneath is fresh and reachable by nobody but the value this returns. A mount that wants
    * a whole store and a narrowing over the same rows together wants `scoped`, not this alongside a
    * separately built `InMemoryStore[A]()`, which would read and write two unrelated maps.
    *
    * What `by` does share, across every call made on the one value this returns, is that same map:
    * an insert through one owner's narrowing is visible to a later call narrowed to that owner, and
    * to no other.
    *
    * No given, which is the other difference from the JDBC half: that one needs a `Column[V]` to
    * bind the owner into a statement, and this one compares two values the model already holds.
    * `ownerOf.get` is the comparison, so a model whose owner field is an `Id[User]` is scoped by
    * `Id`'s own equality and nothing here learns what a user is.
    */
  def owned[A, V](ownerOf: OwnerOf[A, V]): OwnedStore[A, V] = narrowing(new Impl[A], ownerOf)

  /** The pair an owned model's routes are mounted over: the whole table for the actions ownership
    * does not cover, and the narrowing for the ones it does, over one set of rows.
    *
    * One call rather than two, because two would be two maps: a derived `index` reading a store no
    * `create` ever wrote to is an empty page that no test of either half on its own would catch.
    */
  def scoped[A, V](ownerOf: OwnerOf[A, V]): Scoped[A] = {
    val rows = new Impl[A]
    Scoped(rows, narrowing(rows, ownerOf))
  }

  /** One owner's view of rows somebody else holds.
    *
    * Written over the seam rather than inside [[Impl]] so that there is one narrowing and not two:
    * [[owned]] and [[scoped]] differ in what they hand back, never in what a narrowed store does.
    *
    * Every operation reads the whole and then filters through `ownerOf.get`, rather than keeping an
    * index per owner. A second map would have to be kept in step on every write, the write that
    * changes a row's owner included, and this is the half that exists so a model with no database
    * still mounts.
    *
    * A foreign row is absent rather than refused, which is what makes a narrowed `find` answer
    * `None` and a narrowed `update` answer `false` without either learning what an HTTP status is.
    * The check and the write it guards are taken under the rows' own monitor, which is [[Impl]]'s,
    * so a foreign row cannot become the caller's between the two.
    */
  private[http] def narrowing[A, V](rows: Store[A], ownerOf: OwnerOf[A, V]): OwnedStore[A, V] =
    new OwnedStore[A, V] {
      def by(owner: V): Store[A] = new Store[A] {

        def all(): Seq[A] = rows.all().filter(mine)

        def find(key: Id[A]): Option[A] = rows.find(key).filter(mine)

        /** The row exactly as it was given, owner field included: the handler above is what fills
          * that field from the signed in user, so narrowing gets no second say in it.
          */
        def insert(key: Id[A], row: A): Unit = rows.insert(key, row)

        def update(key: Id[A], row: A): Boolean =
          rows.synchronized(find(key).isDefined && rows.update(key, row))

        def delete(key: Id[A]): Boolean =
          rows.synchronized(find(key).isDefined && rows.delete(key))

        private def mine(row: A): Boolean = ownerOf.get(row) == owner
      }
    }

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
