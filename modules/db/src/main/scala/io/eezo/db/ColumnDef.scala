package io.eezo.db

/** The runtime description of one column, produced by the `Table` macro.
  *
  * This type deliberately renders no DDL. Every `create table`, `alter table`, and `create index`
  * in the framework comes out of `schema.Ddl`, driven by a `Change` — the fresh-database path
  * included, via `Differ.diff(SchemaSnap(Nil), _)`. One renderer means the migration path and the
  * reset path cannot drift apart (BACKLOG §4).
  */
final case class ColumnDef(
    name: String,
    pgType: PgType,
    nullable: Boolean,
    primaryKey: Boolean,
    checks: List[Check],
    references: Option[String] = None
) {
  def quoted: String = "\"" + name + "\""
}

object ColumnDef {
  def of[A](
      name: String,
      primaryKey: Boolean = false,
      references: Option[String] = None
  )(using c: Column[A]): ColumnDef =
    ColumnDef(name, c.pgType, c.nullable, primaryKey, c.checks, references)
}

/** A table's columns, for the statements that address rows rather than shape. */
final case class TableDef(name: String, columns: List[ColumnDef]) {
  def quoted: String = "\"" + name + "\""

  def insert: String = {
    val names = columns.map(_.quoted).mkString(", ")
    val holes = columns.map(_ => "?").mkString(", ")
    s"insert into $quoted ($names) values ($holes)"
  }

  def selectAll: String = s"select ${columns.map(_.quoted).mkString(", ")} from $quoted"
}
