package io.eezo.db.schema

/** FIXME There's a problem here you should notice: checkName derives a constraint name from a hash
  * of the expression, but Introspect doesn't read constraint names back — it only reads the
  * expression. So after a DropCheck/AddCheck cycle the names stay consistent only because both
  * sides compute the same hash. Works, but it's load-bearing coincidence. Alternative is to store
  * the constraint name in ColumnSnap and let it be significant, like index names. Worth thinking
  * about; don't change it yet.
  */
object Ddl {

  private def q(s: String): String = "\"" + s + "\""

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

    case Change.CreateIndex(t, i) => i.createDdl(t)
    case Change.DropIndex(_, n)   => s"drop index ${q(n)}"
  }

  def render(cs: List[Change]): List[String] = cs.map(render)
}
