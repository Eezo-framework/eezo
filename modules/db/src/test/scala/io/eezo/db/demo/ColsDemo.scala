package io.eezo.db.demo

import io.eezo.db.*
import io.eezo.db.support.{Author, Book, Library, PublishingHouse}

/** A runnable walk through phase 3: typed column references.
  *
  * sbt "db/Test/runMain io.eezo.db.demo.cols"
  *
  * No database: everything here is decided while compiling.
  */
object ColsDemo {

  private def line(n: String): Unit = println(s"\n── $n ${"─" * math.max(0, 58 - n.length)}")

  def main(args: Array[String]): Unit = {
    line("1. what `derives Table` now knows about Book's columns")
    val b = Table[Book]
    b.columns.foreach(c =>
      println(f"   ${c.name}%-18s ${c.pgType.render}%-14s nullable=${c.nullable}")
    )

    line("2. a reference is typed by the field it names")
    println("   Table[Book].cols.title       : Col[Book, Title]")
    println("   Table[Book].cols.publishedOn : Col[Book, Option[LocalDate]]")
    println("   ...so a query DSL can only compare a column with a value of its own type (phase 4)")

    line("3. the field is named; the column is the framework's business")
    println(s"   _.author      names the field  →  column ${Table[Book].cols.author.name}")
    println(s"   _.publishedBy names the field  →  column ${Table[Book].cols.publishedBy.name}")
    println(s"   _.publishedOn names the field  →  column ${Table[Book].cols.publishedOn.name}")
    println(s"   _.mentor on Author             →  column ${Table[Author].cols.mentor.name}")
    println("   Nobody writes `_id`, and nobody writes snake_case.")

    line("4. indexes are built from those references")
    println(
      """   val books = table[Book].index(_.author, _.publishedOn, _.publishedBy).unique(_.title)"""
    )
    Library.books.indexes.foreach { i =>
      val kind = if (i.unique) "unique" else "index "
      println(s"   $kind ${i.name}  on ${i.columns.mkString(", ")}")
    }

    line("5. what the compiler now refuses")
    println("""   Table[Book].cols.titel              value titel is not a member of ColsOf[Book]
   Library.books.index(_.no_such)     value no_such is not a member of ColsOf[Book]

   Both of these used to be strings. The second was a `SchemaError` thrown the first time
   the schema was forced — a runtime failure for a typo the compiler can see (BACKLOG §7).""")

    line("6. and the ordering the whole thing rests on")
    val fromCols = Table[PublishingHouse].cols.toTuple.productIterator
      .map(_.asInstanceOf[Col[PublishingHouse, ?]].name)
      .toList
    println(s"   cols in order:    ${fromCols.mkString(", ")}")
    println(s"   columns in order: ${Table[PublishingHouse].columns.map(_.name).mkString(", ")}")
    println("   The macro builds the tuple in constructor order, which is the order")
    println("   NamedTuple.From[T] labels it in — and the order `decode` reads by offset.")
  }
}

@main def cols(): Unit = ColsDemo.main(Array.empty)
