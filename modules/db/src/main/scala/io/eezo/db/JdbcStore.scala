package io.eezo.db

import io.eezo.core.{Id, Store}
import io.eezo.db.Scopes.{read, transact}
import io.eezo.db.capability.DB

/** The persistent half of [[io.eezo.core.Store]]: a model's rows in Postgres, read and written
  * through its `Table[A]`.
  *
  * It is what a model deriving `Table` gets in the generated route table, where the in-memory half
  * in `http` is what every other model gets. Neither module sees the other; `core`'s trait is the
  * only thing both name.
  *
  * A plain factory rather than a `given`. Implicit search for `io.eezo.core.Store[Post]` looks in
  * `object Store` in `core` and in `object Post`, and a `given` in this package sits in neither, so
  * it would need an import at every call site to be found at all. The generated table names this
  * object outright instead.
  *
  * The `Table[A]` it needs resolves without help wherever it is called, because `derives Table`
  * puts the instance in the model's own companion.
  */
object JdbcStore {

  /** A store over the installed `Database`. Holds no connection of its own: every operation opens
    * its own scope, so one of these is as good in a handler as in a test.
    */
  def apply[A]()(using t: Table[A]): Store[A] = new Impl[A](t)

  /** One scope per operation, and so one scope per request: each of the derived seven makes exactly
    * one store call, so a transaction spanning two of them would have nothing to span. That is why
    * the trait needs no `atomically`, and why a handler that wants two writes under one commit is a
    * handwritten one using `transact` directly.
    *
    * `read` for the two reads and `transact` for the three writes, rather than `transact` for
    * everything: a read scope neither begins nor commits, which is the difference between an
    * `index` and a write on a hot table.
    */
  private final class Impl[A](t: Table[A]) extends Store[A] {

    /** Ordered by primary key, as the trait promises. `Crud`'s own `all` is deliberately unordered
      * — db's query DSL is where a caller states an order — so this reaches for its own statement
      * rather than borrowing that one.
      */
    def all(): Seq[A] =
      read {
        Query.reading(summon[DB].connection, t.tableDef.selectAllOrderedById, Nil) { rs =>
          Iterator.continually(rs).takeWhile(_.next()).map(t.decode(_, 1)).toVector
        }
      }

    def find(key: Id[A]): Option[A] = read(t.findById(key))

    /** `key` is not written: the row carries its own, and `Crud`'s `insert` writes every column
      * including it. The two agree because the only caller is a derived `create`, which mints the
      * key and hands it to `Form.parse`, which puts it in the record before this ever sees it.
      */
    def insert(key: Id[A], row: A): Unit = transact(t.insert(row))

    /** `NoSuchRow` becomes `false` here, and nowhere else. `core`'s trait says nothing throws and
      * `Resource` turns a `false` into a 404; db reports a zero-row write by raising. Converting is
      * this implementation's job precisely so that neither of the other two learns the other's
      * vocabulary.
      */
    def update(key: Id[A], row: A): Boolean =
      transact {
        try { t.update(row); true }
        catch { case _: NoSuchRow => false }
      }

    def delete(key: Id[A]): Boolean =
      transact {
        try { t.delete(key); true }
        catch { case _: NoSuchRow => false }
      }
  }
}
