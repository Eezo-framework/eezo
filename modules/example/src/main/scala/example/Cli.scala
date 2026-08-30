package example

import io.eezo.db.*
import io.eezo.db.Scopes.*
import io.eezo.db.schema.*
import io.eezo.db.migrate.*
import scala.io.StdIn

object Cli extends EezoApp {

  // One source of truth for connection settings: `Db` already reads these, and `Db.withConnection`
  // is still how the chapters that demonstrate raw JDBC get a connection.
  override def databaseUrl: String      = Db.url
  override def databaseUser: String     = Db.user
  override def databasePassword: String = Db.pass

  def boot(args: Array[String]): Unit = {
    val cmd  = args.headOption.getOrElse("help")
    val rest = args.drop(1)
    try {
      cmd match {
        case "status"  => status()
        case "reset"   => reset()
        case "drop"    => drop()
        case "sync"    => sync(rest.contains("--apply"), rest.contains("--force"))
        case "freeze"  => freeze(rest)
        case "migrate" => migrate(rest.contains("--apply"))
        case "dump"    => println(AppSchema.snapshot.render)
        case "ddl"     => AppSchema.ddl.foreach(s => println(s + ";"))
        case _         => help()
      }
    } catch {
      case e: SchemaError =>
        System.err.println(s"\n[eezo] ${e.getMessage}\n")
        sys.exit(1)
    }
  }

  private def help(): Unit = println(
    """eezo db
      |  status            code vs live database
      |  sync [--apply]    apply the code/db diff directly (dev only)
      |  freeze <name>     write a migration from schema.json -> code
      |  migrate [--apply] apply pending migrations, then verify
      |  reset             drop everything and recreate from the model
      |  dump              print the derived snapshot
      |  ddl               print full DDL""".stripMargin
  )

  private def status(): Unit = Db.withConnection { c =>
    val d = Differ.diff(Introspect.snapshot(c), AppSchema.snapshot)
    if (d.isEmpty) println("in sync ✓")
    else {
      println(s"${d.size} difference(s) between model and database:\n")
      d.foreach(ch => println(s"  ${ch.describe}${flag(ch)}"))
      sys.exit(1)
    }
  }

  private def flag(ch: Change): String =
    if (ch.destructive) "  [destructive]" else if (ch.risky) "  [risky]" else ""

  private def drop(): Unit = Db.withConnection { c =>
    val st = c.createStatement()
    Introspect
      .snapshot(c)
      .tables
      .foreach(t => st.execute(s"""drop table if exists "${t.name}" cascade"""))
    st.execute("""drop table if exists "eezo_migrations"""")
    st.close()
    println("dropped ✓")
  }

  private def reset(): Unit = Db.withConnection { c =>
    drop()
    val st = c.createStatement()
    AppSchema.ddl.foreach(st.execute)
    st.close()
    println("reset ✓")
  }

  private def sync(apply: Boolean, force: Boolean): Unit = Db.withConnection { c =>
    val d = Differ.diff(Introspect.snapshot(c), AppSchema.snapshot)
    if (d.isEmpty)
      println("in sync ✓")

    d.foreach(ch => println(s"  ${ch.describe}${flag(ch)}"))

    if (apply) {
      val blocked = d.filter(ch => ch.destructive || ch.risky)
      if (blocked.nonEmpty && !force) {
        println(s"\nrefusing: ${blocked.size} change(s) need review. --force to override.")
        sys.exit(1)
      }
      val st = c.createStatement()
      Ddl.render(d).foreach(st.execute)
      st.close()
      println("\napplied ✓")
    } else {
      println("\n--apply to execute")
    }
  }

  private def freeze(args: Array[String]): Unit = {
    val name = args.headOption.getOrElse(
      throw SchemaError("freeze needs a name: eezo freeze \"add isbn to book\"")
    )
    val to      = AppSchema.snapshot
    val changes = Differ.diff(Freeze.committedSnapshot(), to)
    if (changes.isEmpty) { println("nothing to freeze"); return }

    println(s"${changes.size} change(s) since last freeze:\n")
    val resolutions = changes.map { ch =>
      if (!ch.destructive) {
        println(s"  ${ch.describe}${flag(ch)}"); Resolution(ch, Decision.Accept)
      } else {
        println(s"\n  ⚠ ${ch.describe}")
        print("    [d] drop (data lost)   [k] keep, skip\n  > ")
        if (StdIn.readLine().trim.toLowerCase == "k") Resolution(ch, Decision.Skip)
        else Resolution(ch, Decision.Accept)
      }
    }
    val out = Freeze.write(name, resolutions, to)
    println(s"\nwrote $out")
  }

  private def migrate(apply: Boolean): Unit = Db.withConnection { c =>
    transact { Migrator.status() } match {
      case Migrator.Status.Tampered(problems) =>
        problems.foreach(p => println(s"  ✗ $p"))
        throw SchemaError(
          "migration integrity check failed.\n" +
            "Migrations are generated. Change the model and re-freeze; don't edit the files."
        )
      case Migrator.Status.Ok(Nil) =>
        println("no pending migrations"); verifySync(c)
      case Migrator.Status.Ok(pending) =>
        pending.foreach { case (n, f, s) => println(f"  $n%04d  $f  (${s.size} statements)") }
        if (!apply) println("\n--apply to execute")
        else {
          println()
          transact { Migrator.apply(pending) }
          println("\napplied ✓\n")
          verifySync(c)
        }
    }
  }

  private def verifySync(c: java.sql.Connection): Unit =
    DeployCheck.verify(c, AppSchema.snapshot) match {
      case Right(_) => println("database matches model ✓")
      case Left(d)  =>
        d.foreach(ch => println(s"    ${ch.describe}"))
        throw SchemaError(s"database does not match model (${d.size} difference(s))")
    }
}
