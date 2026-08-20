package example

import io.eezo.db.*

object Step7 {
  def main(args: Array[String]): Unit = {
    Db.withConnection { c =>
      Migrator.status(c) match {
        case Migrator.Status.Tampered(problems) =>
          println("[eezo] migration integrity check failed:\n")
          problems.foreach(p => println(s"  ✗ $p"))
          println("\nMigrations are generated artifacts. To change the schema,")
          println("change the model and run freeze — do not edit migration files.")
          sys.exit(1)

        case Migrator.Status.Ok(Nil) =>
          println("no pending migrations")
          checkSync(c)

        case Migrator.Status.Ok(pending) =>
          println(s"${pending.size} pending migration(s):\n")
          pending.foreach { case (n, f, s) => println(f"  $n%04d  $f  (${s.size} statements)") }

          if (!args.contains("--apply")) {
            println("\nrun with --apply to execute")
          } else {
            println()
            Migrator.apply(c, pending)
            println("\napplied ✓\n")
            checkSync(c)
          }
      }
    }
  }

  private def checkSync(c: java.sql.Connection): Unit =
    DeployCheck.verify(c, AppSchema.snapshot) match {
      case Right(_) => println("database matches model ✓")
      case Left(d) =>
        println(s"⚠ database does NOT match model — ${d.size} difference(s):")
        d.foreach(ch => println(s"    ${ch.describe}"))
        sys.exit(1)
    }
}
