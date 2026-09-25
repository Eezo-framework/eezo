package io.eezo.db.schema

/** Renders every `Change` the differ can emit, and is the only place that renders DDL.
  *
  * Constraint names come from a hash of the expression, which holds together only because both
  * sides of a drop/add cycle compute the same hash — see BACKLOG §27, and §25, which needs the same
  * decision.
  */
object Ddl {

  private def q(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

  private def checkName(table: String, column: String, expr: String): String = {
    val h = Integer.toHexString(expr.hashCode & 0xffffff)
    s"ck_${table}_${column}_$h"
  }

  def render(c: Change): String = c match {
    case Change.CreateTable(t) =>
      val cols = t.columns
        .map { c =>
          val nn = if (c.nullable || c.primaryKey) "" else " not null"
          val pk = if (c.primaryKey) " primary key" else ""
          val ck = c.checks.map(e => s" check ($e)").mkString
          s"  ${q(c.name)} ${c.pgType}$nn$pk$ck"
        }
        .mkString(",\n")
      s"create table ${q(t.name)} (\n$cols\n)"

    case Change.DropTable(n) => s"drop table ${q(n)}"

    case Change.AddColumn(t, c) =>
      val nn = if (c.nullable) "" else " not null"
      val ck = c.checks.map(e => s" check ($e)").mkString
      s"alter table ${q(t)} add column ${q(c.name)} ${c.pgType}$nn$ck"

    case Change.DropColumn(t, c) => s"alter table ${q(t)} drop column ${q(c)}"

    case Change.AlterType(t, c, _, to) =>
      s"alter table ${q(t)} alter column ${q(c)} type $to using ${q(c)}::$to"

    case Change.SetNullable(t, c, true) => s"alter table ${q(t)} alter column ${q(c)} drop not null"
    case Change.SetNullable(t, c, false) => s"alter table ${q(t)} alter column ${q(c)} set not null"

    case Change.AddCheck(t, c, e) =>
      s"alter table ${q(t)} add constraint ${q(checkName(t, c, e))} check ($e)"
    case Change.DropCheck(t, c, e) =>
      s"alter table ${q(t)} drop constraint ${q(checkName(t, c, e))}"

    case Change.AddForeignKey(t, c, target) =>
      s"alter table ${q(t)} add constraint ${q(s"fk_${t}_$c")} " +
        s"foreign key (${q(c)}) references ${q(target)} (${q("id")})"
    case Change.DropForeignKey(t, c) =>
      s"alter table ${q(t)} drop constraint ${q(s"fk_${t}_$c")}"

    case Change.CreateIndex(t, i) =>
      val u = if (i.unique) "unique " else ""
      s"create ${u}index ${q(i.name)} on ${q(t)} (${i.columns.map(q).mkString(", ")})"
    case Change.DropIndex(_, n) => s"drop index ${q(n)}"
  }

  def render(cs: List[Change]): List[String] = cs.map(render)
}
