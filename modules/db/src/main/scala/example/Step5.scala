package example

import io.eezo.db.*

object Step5 {

  def main(args: Array[String]): Unit = {
    Db.withConnection { c =>
      val fromDb   = Introspect.snapshot(c)
      val fromCode = AppSchema.snapshot

      val changes = Differ.diff(fromDb, fromCode)

      if (changes.isEmpty) { println("in sync ✓") }

      println(s"${changes.size} change(s):\n")
      changes.foreach { ch =>
        val flag =
          if (ch.destructive) " [destructive]"
          else if (ch.risky) " [risky]"
          else ""
        println(s"  ${ch.describe}$flag")
      }

      println("\nSQL:\n")
      Ddl.render(changes).foreach(s => println(s + ";"))

      if (args.contains("--apply")) {
        val blocked = changes.filter(ch => ch.destructive || ch.risky)
        if (blocked.nonEmpty && !args.contains("--force")) {
          println(s"\nrefusing: ${blocked.size} change(s) need review. --force to override.")
          sys.exit(1)
        }
        val st = c.createStatement()
        Ddl.render(changes).foreach { sql =>
          print(s"  $sql ... ")
          st.execute(sql)
          println("ok")
        }
        st.close()
        println("\napplied ✓")

        val after = Differ.diff(Introspect.snapshot(c), AppSchema.snapshot)
        println(if (after.isEmpty) "verified in sync ✓" else s"STILL DRIFTED: ${after.size}")
      }
    }
  }
}
