package io.eezo.db

import io.eezo.db.schema.{Ddl, Differ, IndexSnap, SchemaSnap, Snapshot, TableSnap}

import scala.collection.mutable.ListBuffer

final class TableSpec[T](val table: Table[T]) {
  private val idx = ListBuffer.empty[IndexSnap]

  private def add(unique: Boolean, cols: Seq[String]): this.type = {
    val prefix = if (unique) "uq" else "idx"
    idx += IndexSnap(s"${prefix}_${table.tableName}_${cols.mkString("_")}", cols.toList, unique)
    this
  }

  /** `index(_.author, _.publishedOn)`. BACKLOG §7.
    *
    * Two payoffs over the strings this replaced. Renaming or deleting a field is now a compile
    * error at the index rather than a `SchemaError` thrown when the schema is first forced. And the
    * `_id` suffix on a `Ref` column is the framework's job: the selector names the *field*, the
    * `Col` it returns knows the *column*.
    *
    * The arrow is `->`, not `=>`: a column selector captures nothing, and saying so is what lets a
    * vararg of them be used at all — an impure function in varargs leaks its reach capability out
    * of the method. Same reason `Column.imap` takes pure arrows.
    */
  def index(sel: (ColsOf[T] -> Col[T, ?])*): this.type =
    add(false, sel.map(_(table.cols).name))

  def unique(sel: (ColsOf[T] -> Col[T, ?])*): this.type =
    add(true, sel.map(_(table.cols).name))

  def indexes: List[IndexSnap] = idx.toList

  def snapshot: TableSnap =
    TableSnap(
      table.tableName,
      table.columns.map(Snapshot.column).sortBy(_.name),
      indexes.sortBy(_.name)
    )
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

  /** Full DDL for an empty database: the diff from nothing to this schema.
    *
    * Deliberately the same code path migrations take, so `reset` and `migrate` cannot produce
    * different databases. Ordering (tables, then FKs, then indexes) comes from `Differ.diff`;
    * column order is alphabetical, per DESIGN §3.2.
    */
  lazy val ddl: List[String] = Ddl.render(Differ.diff(SchemaSnap(Nil), snapshot))

  lazy val dropAll: List[String] =
    validated.reverse.map(s => s"""drop table if exists "${s.table.tableName}" cascade""")
}
