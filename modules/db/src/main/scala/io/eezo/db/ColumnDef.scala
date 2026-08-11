package io.eezo.db

final case class ColumnDef(
    name: String,
    pgType: PgType,
    nullable: Boolean,
    primaryKey: Boolean,
    checks: List[Check]
) {
  def quoted: String = "\"" + name + "\""

  def renderDdl: String = {
    val nn = if (nullable || primaryKey) "" else " not null"
    val pk = if (primaryKey) " primary key" else ""
    val ck = checks.map(c => s" check (${c.render(quoted)})").mkString
    s"$quoted ${pgType.render}$nn$pk$ck"
  }
}

object ColumnDef {
  def of[A](name: String, primaryKey: Boolean = false)(using c: Column[A]): ColumnDef =
    ColumnDef(name, c.pgType, c.nullable, primaryKey, c.checks)
}

final case class TableDef(name: String, columns: List[ColumnDef]) {
  def createTable: String = {
    val cols = columns.map(c => "  " + c.renderDdl).mkString(",\n")
    s"create table if not exists \"$name\" (\n$cols\n)"
  }
  def insert: String = {
    val names = columns.map(_.quoted).mkString(", ")
    val holes = columns.map(_ => "?").mkString(", ")
    s"insert into \"$name\" ($names) values ($holes)"
  }
  def selectAll: String =
    s"select ${columns.map(_.quoted).mkString(", ")} from \"$name\""
}
