package io.eezo.db.schema

import io.eezo.db.ColumnDef
import io.eezo.db.internal.J
import io.eezo.db.internal.util.canonicalCheck

final case class ColumnSnap(
    name: String,
    pgType: String,
    nullable: Boolean,
    primaryKey: Boolean,
    checks: List[String],
    references: Option[String]
) {
  def toJson: J = J.O(
    List(
      "name"       -> J.S(name),
      "type"       -> J.S(pgType),
      "nullable"   -> J.B(nullable),
      "primaryKey" -> J.B(primaryKey),
      "checks"     -> J.A(checks.map(J.S(_))),
      "references" -> references.map(J.S(_)).getOrElse(J.Nul)
    )
  )
}

final case class IndexSnap(name: String, columns: List[String], unique: Boolean) {
  def toJson: J = J.O(
    List(
      "name"    -> J.S(name),
      "columns" -> J.A(columns.map(J.S(_))),
      "unique"  -> J.B(unique)
    )
  )
  def createDdl(table: String): String = {
    val u    = if (unique) "unique " else ""
    val cols = columns.map(c => "\"" + c + "\"").mkString(", ")
    s"""create ${u}index "$name" on "$table" ($cols)"""
  }
}

final case class TableSnap(name: String, columns: List[ColumnSnap], indexes: List[IndexSnap]) {
  def toJson: J = J.O(
    List(
      "name"    -> J.S(name),
      "columns" -> J.A(columns.map(_.toJson)),
      "indexes" -> J.A(indexes.map(_.toJson))
    )
  )
}

final case class SchemaSnap(tables: List[TableSnap]) {
  def toJson: J = J.O(
    List(
      "version" -> J.N(1),
      "tables"  -> J.A(tables.map(_.toJson))
    )
  )
  def render: String = J.render(toJson)
}

object Snapshot {
  def column(c: ColumnDef): ColumnSnap =
    ColumnSnap(
      c.name,
      c.pgType.render,
      c.nullable,
      c.primaryKey,
      c.checks.map(ck => canonicalCheck(ck.render(c.name))).sorted, // unquoted name
      c.references
    )
}
