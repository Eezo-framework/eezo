package example

import io.eezo.db.*

object DbStatus {
  def main(args: Array[String]): Unit = {
    Db.withConnection { c =>
      val a = AppSchema.snapshot.render
      val b = Introspect.snapshot(c).render

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
    }
  }
}
