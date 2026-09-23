package io.eezo.db

import io.eezo.db.capability.*
import io.eezo.core.Id

/** CRUD by primary key, as extensions on the derived `Table[T]`. DESIGN §8.2.
  *
  * Extensions rather than methods on the trait, so that `Table[T]` stays a plain description: the
  * migration layer, the differ and `Form[A]` all consume it, and none of them should acquire a
  * dependency on a transaction capability. Reading requires `DB`, writing requires `Tx`, and
  * `TxCap <: DBCap` is what lets a read sit inside a transaction and not the reverse.
  *
  * No `Database` appears in any signature here. The scope came from `transact` or `read`.
  */
extension [T](t: Table[T]) {

  def insert(row: T)(using tx: Tx): Unit = {
    val ps = tx.connection.prepareStatement(t.insertSql)
    try {
      t.encode(ps, 1, row)
      ps.executeUpdate(): Unit
    } finally ps.close()
  }

  /** Writes every column of `row`, matched by its own key.
    *
    * The later write wins: two edits to different fields of the same row end with the second
    * overwriting the first, including fields it never touched. That is the accepted default (DESIGN
    * §8.6). A count of 0 is the one conflict it can still report, and it is where an optimistic
    * concurrency check would later answer too: a version in the `where` turns a stale write into
    * the same 0, so the signature would not change.
    */
  def update(row: T)(using tx: Tx): Int = updateById(t.idOf(row), row)

  /** The row to write found by `id` rather than by the key inside `row`.
    *
    * The two coincide for every caller inside db, which is why `update` exists at all. They come
    * apart at `core`'s `Store[A]`, whose `update(key, row)` promises to replace "the row under
    * `key`": binding `id` here is what makes a stale key report `false` rather than write to
    * `row`'s own key and report `true`, which is the answer the in-memory half gives.
    *
    * The count comes back rather than an exception because a write that matched nothing is an
    * answer, not a failure. The caller that cares asks, and raising would have rolled back every
    * other write in the same `transact` over a row that was simply not there.
    *
    * 0 is also all the conflict detection there is. eezo writes by primary key and tracks no
    * versions, so when two writers edit the same row both see 1 and the later one wins silently:
    * the earlier edit is lost without a trace. 0 says only that the row was never inserted or that
    * something deleted it after it was read.
    */
  def updateById(id: Id[T], row: T)(using tx: Tx): Int = {
    val ps = tx.connection.prepareStatement(t.updateByIdSql)
    try {
      t.encode(ps, 1, row)
      Column[Id[T]].put(ps, t.columns.size + 1, id)
      ps.executeUpdate()
    } finally ps.close()
  }

  /** The count rather than an exception, for the reason `updateById` gives: a missing row is an
    * answer, and only the caller knows whether it matters.
    *
    * eezo deletes by primary key and tracks no versions, so an update that lands first is erased by
    * a later delete without either writer learning of the other: the edit is lost silently. An
    * update that lands after the delete is not silent, it sees 0. Here 0 says only that the row was
    * already gone.
    */
  def delete(id: Id[T])(using tx: Tx): Int = {
    val ps = tx.connection.prepareStatement(t.deleteByIdSql)
    try {
      Column[Id[T]].put(ps, 1, id)
      ps.executeUpdate()
    } finally ps.close()
  }

  def findById(id: Id[T])(using db: DB): Option[T] = {
    val ps = db.connection.prepareStatement(t.selectByIdSql)
    try {
      Column[Id[T]].put(ps, 1, id)
      val rs = ps.executeQuery()
      try if (rs.next()) Some(t.decode(rs, 1)) else None
      finally rs.close()
    } finally ps.close()
  }

  /** Starts a query. `Table[Book].where(...)` is shorthand for `Table[Book].query.where(...)`. */
  def query: Query[T] = Query(t)

  def where(f: ColsOf[T] -> Expr[T]): Query[T]    = query.where(f)
  def orderBy(f: ColsOf[T] -> Order[T]): Query[T] = query.orderBy(f)

  /** Deleting takes a predicate, always. There is no `delete()` terminal on `Query`, so a dropped
    * `.where` during a refactor cannot silently become a table wipe — it stops compiling. DESIGN
    * §9.4, and §3.6: destructive and risky are different, and the destructive one says its own
    * name.
    */
  def deleteWhere(f: ColsOf[T] -> Expr[T])(using tx: Tx): Int = {
    val (w, binds) = Expr.render(f(t.cols))
    Query.writing(tx.connection, t.tableDef.deleteWhere(w), binds)
  }

  /** The table wipe. */
  def deleteAll()(using tx: Tx): Int =
    Query.writing(tx.connection, t.tableDef.deleteAll, Nil)

  /** Strict, and deliberately so: a lazy result would hold the scope and escape the block that
    * created it. A cursor arrives with the query DSL (DESIGN §9), declaring its capture.
    */
  def all()(using db: DB): List[T] = {
    val ps = db.connection.prepareStatement(t.selectAllSql)
    try {
      val rs = ps.executeQuery()
      try Iterator.continually(rs).takeWhile(_.next()).map(t.decode(_, 1)).toList
      finally rs.close()
    } finally ps.close()
  }
}
