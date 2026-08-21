package io.eezo.db

import java.sql.{PreparedStatement, ResultSet}
import io.eezo.db.macros.TableMacro

trait Table[T] {
  def tableName: String
  def columns: List[ColumnDef]

  /** `offset` is the 1-based JDBC index of this row's first column. */
  def encode(ps: PreparedStatement, offset: Int, value: T): Unit
  def decode(rs: ResultSet, offset: Int): T

  final def tableDef: TableDef   = TableDef(tableName, columns)
  final def insertSql: String    = tableDef.insert
  final def selectAllSql: String = tableDef.selectAll
}

object Table {
  def apply[T](using t: Table[T]): Table[T] = t
  inline def derived[T]: Table[T]           = ${ TableMacro.derive[T] }
}
