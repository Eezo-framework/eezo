package io.eezo.db

import java.sql.{PreparedStatement, ResultSet}
import scala.annotation.implicitNotFound
import io.eezo.db.macros.TableMacro

@implicitNotFound(
  "No Table instance for ${T}.\n" +
    "Add `derives Table` to its declaration:\n" +
    "  case class ${T}(id: Id[${T}], ...) derives Table"
)
trait Table[T] {
  def tableName: String
  def columns: List[ColumnDef]

  /** `offset` is the 1-based JDBC index of this row's first column. */
  def encode(ps: PreparedStatement, offset: Int, value: T): Unit
  def decode(rs: ResultSet, offset: Int): T

  /** Typed references to this table's columns, for indexes and for the query DSL.
    *
    * Emitted by the macro in declaration order, which is the order `NamedTuple.From[T]` uses, so a
    * label always names the column beside it.
    */
  def cols: ColsOf[T]

  /** The row's primary key, read from the mandatory `id` field. Emitted by the macro, because only
    * it knows the field exists — the trait cannot express "has an `id`".
    */
  def idOf(value: T): Id[T]

  final def tableDef: TableDef    = TableDef(tableName, columns)
  final def insertSql: String     = tableDef.insert
  final def selectAllSql: String  = tableDef.selectAll
  final def selectByIdSql: String = tableDef.selectById
  final def updateByIdSql: String = tableDef.updateById
  final def deleteByIdSql: String = tableDef.deleteById
}

object Table {
  def apply[T](using t: Table[T]): Table[T] = t
  inline def derived[T]: Table[T]           = ${ TableMacro.derive[T] }
}
