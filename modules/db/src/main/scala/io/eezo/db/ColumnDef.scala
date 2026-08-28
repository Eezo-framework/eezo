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

  /** The primary key column. The `Table` macro refuses a model without an `id`, so this is total in
    * practice; the throw is for a `TableDef` assembled by hand.
    */
  def idColumn: ColumnDef =
    columns
      .find(_.primaryKey)
      .getOrElse(throw new IllegalStateException(s"table $name has no primary key column"))

  def selectById: String = s"$selectAll where ${idColumn.quoted} = ?"

  /** Sets **every** column, including the key, and binds them in declaration order.
    *
    * Setting the key to the value it already has is a no-op, and it buys the one thing that
    * matters: `encode` can be reused exactly as it is. A `set` clause that skipped the key would
    * need its own column ordering, and that is a second ordering decision to keep in step with
    * `decode` — the same trap DESIGN §9.4 avoids for the select list. The key is bound once more at
    * the end, for the `where`.
    */
  def updateById: String = {
    val sets = columns.map(c => s"${c.quoted} = ?").mkString(", ")
    s"update $quoted set $sets where ${idColumn.quoted} = ?"
  }

  def deleteById: String = s"delete from $quoted where ${idColumn.quoted} = ?"
}
