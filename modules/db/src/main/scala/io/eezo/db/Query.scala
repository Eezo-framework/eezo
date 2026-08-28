package io.eezo.db

import io.eezo.db.capability.*
import java.sql.{PreparedStatement, ResultSet}

/** A query, as a pure description. DESIGN §9.3.
  *
  * It holds no capability, so it can be built anywhere, passed around, composed conditionally and
  * unit-tested without a database. Only the terminals require `DB`, and they are strict — there is
  * no cursor here to outlive the scope that produced it.
  */
final case class Query[T](
    table: Table[T],
    pred: Expr[T] = Expr.All[T](),
    ord: List[Order[T]] = Nil,
    lim: Option[Int] = None,
    off: Option[Int] = None
) {

  def where(f: ColsOf[T] -> Expr[T]): Query[T]    = copy(pred = Expr.and(pred, f(table.cols)))
  def orderBy(f: ColsOf[T] -> Order[T]): Query[T] = copy(ord = ord :+ f(table.cols))
  def limit(n: Int): Query[T]                     = copy(lim = Some(n))
  def offset(n: Int): Query[T]                    = copy(off = Some(n))

  private def tail: String =
    (if (ord.isEmpty) "" else ord.map(_.render).mkString(" order by ", ", ", "")) +
      lim.fold("")(n => s" limit $n") +
      off.fold("")(n => s" offset $n")

  /** The statement and its binds, without running anything. Golden-testable, and what a log line or
    * an `explain` would want.
    */
  def selectSql: (String, List[Bind]) = {
    val (w, binds) = Expr.render(pred)
    (table.tableDef.selectWhere(w) + tail, binds)
  }

  def countSql: (String, List[Bind]) = {
    val (w, binds) = Expr.render(pred)
    (table.tableDef.countWhere(w), binds)
  }

  def list()(using db: DB): List[T] = {
    val (sql, binds) = selectSql
    Query.reading(db.connection, sql, binds) { rs =>
      Iterator.continually(rs).takeWhile(_.next()).map(table.decode(_, 1)).toList
    }
  }

  def first()(using db: DB): Option[T] = limit(1).list().headOption

  def count()(using db: DB): Long = {
    val (sql, binds) = countSql
    Query.reading(db.connection, sql, binds)(rs => if (rs.next()) rs.getLong(1) else 0L)
  }
}

object Query {

  private[eezo] def bindAll(ps: PreparedStatement, binds: List[Bind]): Unit =
    binds.iterator.zipWithIndex.foreach { case (b, i) => b(ps, i + 1) }

  private[eezo] def reading[A](
      c: java.sql.Connection,
      sql: String,
      binds: List[Bind]
  )(f: ResultSet -> A): A = {
    val ps = c.prepareStatement(sql)
    try {
      bindAll(ps, binds)
      val rs = ps.executeQuery()
      try f(rs)
      finally rs.close()
    } finally ps.close()
  }

  private[eezo] def writing(c: java.sql.Connection, sql: String, binds: List[Bind]): Int = {
    val ps = c.prepareStatement(sql)
    try {
      bindAll(ps, binds)
      ps.executeUpdate()
    } finally ps.close()
  }
}
