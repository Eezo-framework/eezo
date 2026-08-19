package example

import io.eezo.db.*
import scala.io.StdIn

// checks frozen schema (in schema.json) vs what the AppSchema has (the current state of the code)
object Step6 {
  def main(args: Array[String]): Unit = {
    val name = args.headOption.getOrElse {
      println("usage: Step6 \"migration name\""); sys.exit(1)
    }

    val from    = Freeze.committedSnapshot()
    val to      = AppSchema.snapshot
    val changes = Differ.diff(from, to)

    if (changes.isEmpty) { println("nothing to freeze — schema.json matches the model") }

    println(s"${changes.size} change(s) since last freeze:\n")

    val resolutions = changes.map { ch =>
      if (!ch.destructive) {
        println(s"  ${ch.describe}${if (ch.risky) "  [risky]" else ""}")
        Resolution(ch, Decision.Accept)
      } else {
        println(s"\n  ⚠ ${ch.describe}")
        println("    [d] drop (data is lost)   [k] keep column, skip this change")
        print("  > ")
        StdIn.readLine().trim.toLowerCase match {
          case "k" => Resolution(ch, Decision.Skip)
          case _   => Resolution(ch, Decision.Accept)
        }
      }
    }

    val out = Freeze.write(name, resolutions, to)
    println(s"\nwrote $out")
    println(s"updated ${Freeze.snapshotFile}")
    println("\n" + java.nio.file.Files.readString(out))
  }
}

object Step6_V2 {
  import java.nio.file.{Files, Path, Paths}

  def main(args: Array[String]) = {
    val dbDir: Path         = Paths.get("db")
    val migrationsDir: Path = dbDir.resolve("migrations")
    val migFile: Path       = migrationsDir.resolve("0003_rename_book.sql")
    println(Migration.verify(Files.readString(migFile)))
  }
}
