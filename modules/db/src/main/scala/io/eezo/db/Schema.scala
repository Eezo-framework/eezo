package io.eezo.db

import scala.collection.mutable.ListBuffer

final class TableSpec[T](val table: Table[T]) {
  private val idx = ListBuffer.empty[IndexSnap]

  private def add(unique: Boolean, cols: Seq[String]): this.type = {
    val prefix = if (unique) "uq" else "idx"
    idx += IndexSnap(s"${prefix}_${table.tableName}_${cols.mkString("_")}", cols.toList, unique)
    this
  }

  // TODO: string columns are a placeholder — replaced by `_.author` once Cols lands.
  def index(cols: String*): this.type  = add(false, cols)
  def unique(cols: String*): this.type = add(true, cols)

  def indexes: List[IndexSnap] = idx.toList

  def snapshot: TableSnap =
    TableSnap(table.tableName, table.columns.map(Snapshot.column), indexes.sortBy(_.name))
}

abstract class Schema {
  private val specs = ListBuffer.empty[TableSpec[?]]

  protected def table[T](using t: Table[T]): TableSpec[T] = {
    val s = new TableSpec[T](t)
    specs += s
    s
  }

  private lazy val validated: List[TableSpec[?]] = {
    val all   = specs.toList
    val names = all.map(_.table.tableName)

    names.groupBy(identity).collect { case (n, xs) if xs.sizeIs > 1 => n }.foreach { n =>
      sys.error(s"Duplicate table `$n` registered in schema.")
    }

    all.foreach { s =>
      val cols = s.table.columns.map(_.name).toSet

      s.table.columns.foreach { c =>
        c.references.foreach { target =>
          if (!names.contains(target))
            sys.error(
              s"Table `${s.table.tableName}` column `${c.name}` references `$target`, " +
                s"which is not registered in this schema.\n" +
                s"  Registered: ${names.sorted.mkString(", ")}\n" +
                s"  Add: val ${target}s = table[${target.capitalize}]"
            )
        }
      }

      s.indexes.foreach { i =>
        i.columns.filterNot(cols.contains).foreach { bad =>
          sys.error(
            s"Index `${i.name}` on `${s.table.tableName}` references unknown column `$bad`.\n" +
              s"  Available: ${cols.toList.sorted.mkString(", ")}"
          )
        }
      }
    }
    all
  }

  lazy val snapshot: SchemaSnap =
    SchemaSnap(validated.map(_.snapshot).sortBy(_.name))

  /** Full DDL, ordered so FKs land after every table exists. */
  lazy val ddl: List[String] = {
    val ts = validated.map(_.table.tableDef)
    ts.map(_.createTable) ++
      ts.flatMap(_.foreignKeys) ++
      validated.flatMap(s => s.indexes.map(_.createDdl(s.table.tableName)))
  }

  lazy val dropAll: List[String] =
    validated.reverse.map(s => s"""drop table if exists "${s.table.tableName}" cascade""")
}
