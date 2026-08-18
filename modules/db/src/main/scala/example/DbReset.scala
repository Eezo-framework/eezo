package example

import io.eezo.db.*

object DbReset {

  def wipe(c: java.sql.Connection): Unit = {
    val st = c.createStatement()
    st.execute("drop schema public cascade")
    st.execute("create schema public")
    st.close()
  }

  def create(c: java.sql.Connection): Unit = {
    val st = c.createStatement()
    AppSchema.ddl.foreach { sql =>
      st.execute(sql)
    }
    st.close()
  }

  def seed(c: java.sql.Connection): Unit = {
    val at = Table[Author]
    val bt = Table[Book]

    val herbert = Author(Id.gen(), "Frank Herbert", Some("US"), None)
    val student = Author(Id.gen(), "Kevin Anderson", Some("US"), Some(Ref.to(herbert.id)))

    val ai = c.prepareStatement(at.insertSql)
    at.encode(ai, 1, herbert); ai.addBatch()
    at.encode(ai, 1, student); ai.addBatch()
    ai.executeBatch(); ai.close()

    val bi = c.prepareStatement(bt.insertSql)
    Book.seedBooks.foreach { b => bt.encode(bi, 1, b); bi.addBatch() }
    bi.executeBatch(); bi.close()
  }

  def main(args: Array[String]): Unit = {
    val withSeed = !args.contains("--no-seed")

    Db.withConnection { c =>
      print("wiping public schema ... ")
      wipe(c)
      println("ok")

      print("applying ddl ... ")
      create(c)
      println(s"ok (${AppSchema.ddl.size} statements)")

      if (withSeed) {
        print("seeding ... ")
        seed(c)
        println("ok")
      }

      val drift = Differ.diff(Introspect.snapshot(c), AppSchema.snapshot)
      if (drift.isEmpty) println("\nin sync ✓")
      else {
        println(s"\nUNEXPECTED DRIFT after reset (${drift.size}):")
        drift.foreach(ch => println("  " + ch.describe))
        sys.exit(1)
      }
    }
  }
}
