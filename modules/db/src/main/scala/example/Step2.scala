package example

import java.time.LocalDate
import io.eezo.db.*

object Step2 {
  // def main(args: Array[String]): Unit = {
  //   val t  = Table[Book]
  //   val t2 = Table[Book]
  //   println(t.createTable)
  //   println(t2.columns.map(_.renderDdl))
  //   println()

  //   Db.withConnection { c =>
  //     val st = c.createStatement()
  //     st.execute("drop table if exists \"book\"")
  //     st.execute(t.createTable)
  //     st.close()

  //     val rows = List(
  //       Book(Id.gen(), "Dune", "Herbert", Some(LocalDate.of(1965, 8, 1))),
  //       Book(Id.gen(), "Untitled", "Nobody", None)
  //     )

  //     val ins = c.prepareStatement(t.insertSql)
  //     rows.foreach { b => t.encode(ins, 1, b); ins.addBatch() }
  //     ins.executeBatch()
  //     ins.close()

  //     val sel = c.prepareStatement(t.selectAllSql)
  //     val rs  = sel.executeQuery()
  //     val out = Iterator.continually(rs).takeWhile(_.next()).map(t.decode(_, 1)).toList
  //     rs.close(); sel.close()

  //     out.foreach(println)
  //     assert(out.exists(_.publishedOn.isEmpty), "null round-trip failed")
  //     assert(out.map(_.title).toSet == Set("Dune", "Untitled"), "round-trip mismatch")
  //     println("\nok")
  //   }
  // }
}
