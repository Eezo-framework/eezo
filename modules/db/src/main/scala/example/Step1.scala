package example

import java.time.LocalDate
import io.eezo.db.*

object Step1 {

  // Hand-written now; step 3's macro emits exactly this shape.
  val bookTable: TableDef = TableDef(
    "book",
    List(
      ColumnDef.of[Id[Book]]("id", primaryKey = true),
      ColumnDef.of[String]("title"),
      ColumnDef.of[String]("author"),
      ColumnDef.of[Option[LocalDate]]("published_on")
    )
  )

  def encode(ps: java.sql.PreparedStatement, b: Book): Unit = {
    Column[Id[Book]].put(ps, 1, b.id)
    Column[String].put(ps, 2, b.title)
    Column[String].put(ps, 3, b.author)
    Column[Option[LocalDate]].put(ps, 4, b.publishedOn)
  }

  def decode(rs: java.sql.ResultSet): Book = Book(
    Column[Id[Book]].get(rs, 1),
    Column[String].get(rs, 2),
    Column[String].get(rs, 3),
    Column[Option[LocalDate]].get(rs, 4)
  )

  def main(args: Array[String]): Unit = {
    println(bookTable.createTable)
    println()

    Db.withConnection { c =>
      val st = c.createStatement()
      st.execute("drop table if exists \"book\"")
      st.execute(bookTable.createTable)
      st.close()

      val rows = List(
        Book(Id.gen(), "Dune", "Herbert", Some(LocalDate.of(1965, 8, 1))),
        Book(Id.gen(), "Untitled", "Nobody", None)
      )

      val ins = c.prepareStatement(bookTable.insert)
      rows.foreach { b => encode(ins, b); ins.addBatch() }
      ins.executeBatch()
      ins.close()

      val sel = c.prepareStatement(bookTable.selectAll)
      val rs  = sel.executeQuery()
      val out = Iterator.continually(rs).takeWhile(_.next()).map(decode).toList
      rs.close(); sel.close()

      out.foreach(println)
      assert(out.exists(_.publishedOn.isEmpty), "null round-trip failed")
      assert(out.map(_.id).distinct.size == 2, "id collision")
      println("\nok")
    }
  }
}
