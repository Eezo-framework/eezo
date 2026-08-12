package example

import io.eezo.db.*
import java.nio.file.{Files, Paths}
import java.time.LocalDate

object Step3 {
  def main(args: Array[String]): Unit = {
    val snap = AppSchema.snapshot.render
    println(snap)

    val out = Paths.get("db")
    Files.createDirectories(out)
    Files.writeString(out.resolve("schema.json"), snap + "\n")
    println("\n-> wrote db/schema.json\n")

    AppSchema.ddl.foreach(s => println(s + ";\n"))

    Db.withConnection { c =>
      val st = c.createStatement()
      AppSchema.dropAll.foreach(st.execute)
      AppSchema.ddl.foreach(st.execute)
      st.close()

      val at = Table[Author]
      val bt = Table[Book]

      val herbert = Author(Id.gen(), "Frank Herbert", Some("US"))
      val ai      = c.prepareStatement(at.insertSql)
      at.encode(ai, 1, herbert)
      ai.execute(); ai.close()

      val bi = c.prepareStatement(bt.insertSql)
      bt.encode(bi, 1, Book(Id.gen(), "Dune", Ref.to(herbert.id), Some(LocalDate.of(1965, 8, 1))))
      bt.encode(bi, 1, Book(Id.gen(), "Untitled", Ref.to(herbert.id), None))
      bi.close()

      // FK must reject a dangling reference
      val bad = c.prepareStatement(bt.insertSql)
      bt.encode(bad, 1, Book(Id.gen(), "Ghost", Ref[Author](java.util.UUID.randomUUID()), None))
      val rejected =
        try { bad.execute(); false }
        catch { case _: java.sql.SQLException => true }
      bad.close()
      assert(rejected, "FK constraint not enforced")

      val sel  = c.prepareStatement(bt.selectAllSql)
      val rs   = sel.executeQuery()
      val rows = Iterator.continually(rs).takeWhile(_.next()).map(bt.decode(_, 1)).toList
      rs.close(); sel.close()

      rows.foreach(b => println(s"${b.title} by ${b.author.value}"))
      assert(rows.sizeIs == 2)
      println("\nok")
    }
  }
}
