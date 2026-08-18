package example

import io.eezo.db.*

object Step4 {
  given titleCol: Column[String] = Column[String].withCheck(Check.MaxLen(200))

  def main(args: Array[String]): Unit = {
    val recreate = !args.contains("--no-ddl")

    Db.withConnection { c =>
      if (recreate) {
        val st = c.createStatement()
        println("DDL to execute:")
        AppSchema.ddl.foreach(println)

        AppSchema.dropAll.foreach(st.execute)
        AppSchema.ddl.foreach(st.execute)
        println("DDL executed.")
        st.close()

        print("Data about to be inserted. Press any key to continue...")

        val at = Table[Author]
        val bt = Table[Book]

        // plain inserts
        val herbert = Author(Id.gen(), "Frank Herbert", Some("US"), None)
        val ai      = c.prepareStatement(at.insertSql)
        at.encode(ai, 1, herbert)
        ai.execute(); ai.close()

        val bi = c.prepareStatement(bt.insertSql)
        Book.seedBooks.foreach { b =>
          bt.encode(bi, 1, b)
          bi.addBatch()
        }
        bi.executeBatch()
        bi.close()

        // try pushing a duplicate
        val dup = c.prepareStatement(Table[Book].insertSql)
        Table[Book].encode(dup, 1, Book.seedBooks.head)
        val rejected =
          try { dup.execute(); false }
          catch { case _: java.sql.SQLException => true }
        dup.close()
        assert(rejected, "unique index not enforced")

        // self-reference check
        val oldid: Id[Author]   = Id.gen()
        val youngid: Id[Author] = Id.gen()
        val old                 = Author(
          oldid,
          "Old Master",
          Some("US"),
          None
        ) // cyclical refs work if after the prompt: alter table "author" alter constraint "fk_author_mentor_id" deferrable initially deferred;
        val young    = Author(youngid, "Young Turk", Some("US"), Some(Ref.to(oldid)))
        val refcheck = c.prepareStatement(at.insertSql)
        at.encode(refcheck, 1, old)
        refcheck.addBatch()
        at.encode(refcheck, 1, young)
        refcheck.addBatch()
        refcheck.executeBatch()
        refcheck.close()
        // if this passes, all good

        println("All good so far with the data. Checking schemas now:")
      }

      val fromCode = AppSchema.snapshot
      val fromDb   = Introspect.snapshot(c)

      val a = fromCode.render
      val b = fromDb.render

      if (a == b) println("round-trip identical ✓")
      else {
        println("MISMATCH\n")
        println(f"      ${"code"}%-50s\t${"db"}")
        a.linesIterator.zipAll(b.linesIterator, "", "").zipWithIndex.foreach { case ((l, r), i) =>
          val mark = if (l == r) " " else "≠"
          println(f"$i%4d $mark  $l%-50s\t$r")
        }
        sys.exit(1)
      }

      // Now prove it detects a real change.
      val st2 = c.createStatement()
      st2.execute("""alter table "book" add column "isbn" text""")
      st2.close()

      val drifted = Introspect.snapshot(c)
      println(s"\nafter manual ALTER, equal = ${drifted.render == a}  (expect false)")
    }
  }
}
