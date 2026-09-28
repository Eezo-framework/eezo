package io.eezo.db.schema

import io.eezo.db.ColumnDef
import io.eezo.core.internal.Json
import io.eezo.db.internal.util.canonicalCheck

final case class ColumnSnap(
    name: String,
    pgType: String,
    nullable: Boolean,
    primaryKey: Boolean,
    checks: List[String],
    references: Option[String]
) {
  private[eezo] def toJson: Json = Json.Obj(
    List(
      "name"       -> Json.Str(name),
      "type"       -> Json.Str(pgType),
      "nullable"   -> Json.Bool(nullable),
      "primaryKey" -> Json.Bool(primaryKey),
      "checks"     -> Json.Arr(checks.map(Json.Str(_))),
      "references" -> references.map(Json.Str(_)).getOrElse(Json.Null)
    )
  )
}

final case class IndexSnap(name: String, columns: List[String], unique: Boolean) {
  private[eezo] def toJson: Json = Json.Obj(
    List(
      "name"    -> Json.Str(name),
      "columns" -> Json.Arr(columns.map(Json.Str(_))),
      "unique"  -> Json.Bool(unique)
    )
  )
}

final case class TableSnap(name: String, columns: List[ColumnSnap], indexes: List[IndexSnap]) {
  private[eezo] def toJson: Json = Json.Obj(
    List(
      "name"    -> Json.Str(name),
      "columns" -> Json.Arr(columns.map(_.toJson)),
      "indexes" -> Json.Arr(indexes.map(_.toJson))
    )
  )
}

final case class SchemaSnap(tables: List[TableSnap]) {
  private[eezo] def toJson: Json = Json.Obj(
    List(
      "version" -> Json.Num(1),
      "tables"  -> Json.Arr(tables.map(_.toJson))
    )
  )
  def render: String = Json.render(toJson)
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
