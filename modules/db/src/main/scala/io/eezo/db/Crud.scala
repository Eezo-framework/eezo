package io.eezo.db

import io.eezo.db.capability.*
import java.sql.PreparedStatement

/** An `update` or `delete` that matched no row. */
final case class NoSuchRow(msg: String) extends RuntimeException(msg)

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
    * Last-write-wins: two edits to different fields of the same row end with the second overwriting
    * the first, including fields it never touched. That is the accepted default (DESIGN §8.6); the
    * zero-rows check below is the one conflict it can still detect, and it is the branch an
    * optimistic-concurrency check would later reuse without changing this signature.
    */
  def update(row: T)(using tx: Tx): Unit = {
    val id = t.idOf(row)
    val ps = tx.connection.prepareStatement(t.updateByIdSql)
    try {
      t.encode(ps, 1, row)
      Column[Id[T]].put(ps, t.columns.size + 1, id)
      expectOne(ps.executeUpdate(), "update", t.tableName, id)
    } finally ps.close()
  }

  def delete(id: Id[T])(using tx: Tx): Unit = {
    val ps = tx.connection.prepareStatement(t.deleteByIdSql)
    try {
      Column[Id[T]].put(ps, 1, id)
      expectOne(ps.executeUpdate(), "delete", t.tableName, id)
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

private def expectOne[T](rows: Int, verb: String, table: String, id: Id[T]): Unit =
  if (rows != 1)
    throw NoSuchRow(
      s"""|$verb matched $rows rows in "$table" for id ${id.show}, expected 1.
          |
          |The row is not there. Something else deleted it between the read and this write, or it
          |was never inserted. eezo writes by primary key and does not track versions, so a missing
          |row is the one conflict it can detect: if two writers edited the same row, the later
          |write wins silently.
          |""".stripMargin
    )
