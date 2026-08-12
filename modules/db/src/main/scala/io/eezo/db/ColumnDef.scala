package io.eezo.db

final case class ColumnDef(
    name: String,
    pgType: PgType,
    nullable: Boolean,
    primaryKey: Boolean,
    checks: List[Check],
    references: Option[String] = None
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
  def of[A](
      name: String,
      primaryKey: Boolean = false,
      references: Option[String] = None
  )(using c: Column[A]): ColumnDef =
    ColumnDef(name, c.pgType, c.nullable, primaryKey, c.checks, references)
}

final case class TableDef(name: String, columns: List[ColumnDef]) {
  def quoted: String = "\"" + name + "\""

  def createTable: String = {
    val cols = columns.map(c => "  " + c.renderDdl).mkString(",\n")
    s"create table if not exists $quoted (\n$cols\n)"
  }

  /** Emitted separately so table creation order doesn't matter. */
  def foreignKeys: List[String] = columns.flatMap { c =>
    c.references.map { target =>
      s"""alter table $quoted add constraint "fk_${name}_${c.name}" """ +
        s"""foreign key (${c.quoted}) references "$target" ("id")"""
    }
  }

  def insert: String = {
    val names = columns.map(_.quoted).mkString(", ")
    val holes = columns.map(_ => "?").mkString(", ")
    s"insert into $quoted ($names) values ($holes)"
  }

  def selectAll: String = s"select ${columns.map(_.quoted).mkString(", ")} from $quoted"
}
