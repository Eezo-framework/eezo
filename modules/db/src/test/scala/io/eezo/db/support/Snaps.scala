package io.eezo.db.support

import io.eezo.db.schema.*

/** Snapshot literals, for tests that exercise the differ without deriving anything. */
object Snaps {
  def col(
      name: String,
      tpe: String = "text",
      nullable: Boolean = false,
      pk: Boolean = false,
      checks: List[String] = Nil,
      references: Option[String] = None
  ): ColumnSnap = ColumnSnap(name, tpe, nullable, pk, checks, references)

  val id: ColumnSnap = col("id", "uuid", pk = true)

  def tbl(name: String, cols: ColumnSnap*): TableSnap =
    TableSnap(name, cols.toList.sortBy(_.name), Nil)

  def tblIx(name: String, cols: List[ColumnSnap], ix: List[IndexSnap]): TableSnap =
    TableSnap(name, cols.sortBy(_.name), ix.sortBy(_.name))

  def snap(ts: TableSnap*): SchemaSnap = SchemaSnap(ts.toList.sortBy(_.name))

  val empty: SchemaSnap = SchemaSnap(Nil)
}
