package io.eezo.db.schema

enum Change {
  case CreateTable(table: TableSnap)
  case DropTable(name: String)
  case AddColumn(table: String, column: ColumnSnap)
  case DropColumn(table: String, column: String)
  case AlterType(table: String, column: String, from: String, to: String)
  case SetNullable(table: String, column: String, nullable: Boolean)
  case AddCheck(table: String, column: String, expr: String)
  case DropCheck(table: String, column: String, expr: String)
  case AddForeignKey(table: String, column: String, target: String)
  case DropForeignKey(table: String, column: String)
  case CreateIndex(table: String, index: IndexSnap)
  case DropIndex(table: String, name: String)

  def destructive: Boolean = this match {
    case _: DropTable | _: DropColumn => true
    case _                            => false
  }

  /** Needs data validation or a backfill before it can safely apply. */
  def risky: Boolean = this match {
    case SetNullable(_, _, false) => true   // narrowing: existing NULLs will fail
    case _: AlterType             => true   // may fail on existing values
    case _: AddCheck              => true   // may fail on existing rows
    case _: CreateIndex           => false
    case _                        => false
  }

  def describe: String = this match {
    case CreateTable(t)              => s"create table ${t.name}"
    case DropTable(n)                => s"drop table $n"
    case AddColumn(t, c)             => s"+ ${t}.${c.name} ${c.pgType}${if (c.nullable) " null" else " not null"}"
    case DropColumn(t, c)            => s"- ${t}.$c"
    case AlterType(t, c, f, to)      => s"~ ${t}.$c $f -> $to"
    case SetNullable(t, c, true)     => s"~ ${t}.$c drop not null"
    case SetNullable(t, c, false)    => s"~ ${t}.$c set not null"
    case AddCheck(t, c, e)           => s"+ check ${t}.$c: $e"
    case DropCheck(t, c, e)          => s"- check ${t}.$c: $e"
    case AddForeignKey(t, c, target) => s"+ fk ${t}.$c -> $target"
    case DropForeignKey(t, c)        => s"- fk ${t}.$c"
    case CreateIndex(t, i)           => s"+ index ${i.name} on $t"
    case DropIndex(t, n)             => s"- index $n on $t"
  }
}
