package io.eezo.db

import io.eezo.core.{Id, OwnedStore, OwnerOf, Store}
import io.eezo.core.internal.util.snake
import io.eezo.db.Scopes.{read, transact}
import io.eezo.db.capability.{DB, Tx}

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

  /** The same rows, narrowable to the owner one column records.
    *
    * The column is resolved once, here, and not on every call: `by` is called on every covered
    * request, and a lookup through the column list per request would be this table's shape read
    * thousands of times to answer a question that cannot change. Resolving it here is also what
    * makes a wrong owner field a failure at boot rather than a wrong query in production, which is
    * the precedent `TableDef.idColumn` set: the route table is built while the application starts,
    * so an application whose declaration names a field its table has no column for does not serve.
    *
    * The name is read through `snake` because that is the one rule the `Table` macro names columns
    * by, and an `OwnerOf` carries the model's own field label. A model whose owner is `createdBy`
    * would otherwise be looked up under a name no table ever has.
    *
    * `Column[V]` and not a cast: the owner is bound into four statements through the same `put` any
    * other value of its type goes through, so a value that spells a quotation mark is a value and
    * never a fragment of SQL.
    */
  def owned[A, V](ownerOf: OwnerOf[A, V])(using t: Table[A], c: Column[V]): OwnedStore[A, V] = {
    val wanted = snake(ownerOf.name)
    val column = t.columns
      .find(_.name == wanted)
      .getOrElse(
        throw new IllegalStateException(
          s"""table "${t.tableName}" has no column "$wanted" to own its rows by, so the ownership
             |declaration naming `${ownerOf.name}` cannot be scoped. The model's owner field has to
             |be a column of its table: add it to the model, or name the field that is already
             |there.""".stripMargin.replace("\n", " ")
        )
      )
    new OwnedImpl[A, V](t, column, c)
  }

  /** Every row of a result set, decoded. Shared by the whole table's `all` and one owner's. */
  private def rowsOf[A](t: Table[A])(rs: java.sql.ResultSet): Vector[A] =
    Iterator.continually(rs).takeWhile(_.next()).map(t.decode(_, 1)).toVector

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
        Query.reading(summon[DB].connection, t.selectAllOrderedByIdSql, Nil)(rowsOf(t))
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
        try { t.updateById(key, row); true }
        catch { case _: NoSuchRow => false }
      }

    def delete(key: Id[A]): Boolean =
      transact {
        try { t.delete(key); true }
        catch { case _: NoSuchRow => false }
      }
  }

  /** One owner's rows, as the four owner aware statements address them.
    *
    * Every statement carries the owner condition, which is what makes a foreign row absent rather
    * than refused: `find` answers `None`, `update` and `delete` answer `false`, and [[Resource]]
    * turns those into the status its declaration asked for without db learning what a status is.
    *
    * Binding `update` and `delete` by key **and** owner is what keeps that promise on a write with
    * no read in front of it. A `find` before each write would be a second query on every write and
    * a race in the gap between the two; the owner in the `where` is the same check done by the
    * database, atomically, for nothing. The price is that a row whose owner changed underneath
    * answers `false` where db's documented last write wins would have written, which is the answer
    * a caller scoped to an owner should get.
    *
    * The statements are `TableDef`'s. Nothing here composes SQL.
    */
  private final class OwnedImpl[A, V](t: Table[A], owner: ColumnDef, c: Column[V])
      extends OwnedStore[A, V] {

    private val allSql    = t.tableDef.selectAllOrderedByIdOwnedBy(owner)
    private val findSql   = t.tableDef.selectByIdOwnedBy(owner)
    private val updateSql = t.tableDef.updateByIdOwnedBy(owner)
    private val deleteSql = t.tableDef.deleteByIdOwnedBy(owner)

    def by(value: V): Store[A] = new Store[A] {

      private val bindOwner: Bind = (ps, i) => c.put(ps, i, value)

      def all(): Seq[A] =
        read {
          Query.reading(summon[DB].connection, allSql, List(bindOwner))(rowsOf(t))
        }

      def find(key: Id[A]): Option[A] =
        read {
          Query.reading(summon[DB].connection, findSql, List(bindKey(key), bindOwner)) { rs =>
            if (rs.next()) Some(t.decode(rs, 1)) else None
          }
        }

      /** The row exactly as it was given, the owner column included. The handler above fills that
        * column from who is signed in, so narrowing gets no second say in it, and an `insert` needs
        * no owner condition: there is no row yet to belong to anybody.
        */
      def insert(key: Id[A], row: A): Unit = transact(t.insert(row))

      /** The statement written out rather than composed from binds, because `encode` writes every
        * column of the row from one offset and there is no per-column entry point to hand a list of
        * binds. It is `Crud.updateById`'s own shape, with the owner bound one place further on.
        */
      def update(key: Id[A], row: A): Boolean =
        transact {
          val ps = summon[Tx].connection.prepareStatement(updateSql)
          try {
            t.encode(ps, 1, row)
            Column[Id[A]].put(ps, t.columns.size + 1, key)
            c.put(ps, t.columns.size + 2, value)
            ps.executeUpdate() == 1
          } finally ps.close()
        }

      def delete(key: Id[A]): Boolean =
        transact {
          Query.writing(summon[Tx].connection, deleteSql, List(bindKey(key), bindOwner)) == 1
        }

      private def bindKey(key: Id[A]): Bind = (ps, i) => Column[Id[A]].put(ps, i, key)
    }
  }
}
