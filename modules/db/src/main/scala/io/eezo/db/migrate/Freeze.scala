package io.eezo.db.migrate

import io.eezo.db.internal.SnapshotJson
import io.eezo.db.schema.{Change, Ddl, SchemaSnap}

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

final case class Resolution(change: Change, decision: Decision)

/** What the author chose to do with one drifted change.
  *
  * `Skip` currently still records the new state in the snapshot, so a skipped change is not
  * re-offered — see BACKLOG §28, which is the decision that has not been made.
  */
enum Decision {
  case Accept
  case Skip
  case Manual(sql: List[String])
}

object Freeze {

  /** Where a project keeps its migration history, relative to the working directory.
    *
    * Every entry point below takes the directory as a parameter defaulting to this, rather than
    * reading it from a `val`. A hardcoded path is not a configuration problem so much as a
    * testability one: with the path baked in, nothing that writes a migration can be exercised
    * anywhere except the real `db/` of whatever process is running.
    */
  val defaultDbDir: Path = Paths.get("db")

  def migrationsDir(dbDir: Path = defaultDbDir): Path = dbDir.resolve("migrations")
  def snapshotFile(dbDir: Path = defaultDbDir): Path  = dbDir.resolve("schema.json")

  def committedSnapshot(dbDir: Path = defaultDbDir): SchemaSnap = {
    val f = snapshotFile(dbDir)
    if (Files.exists(f)) SnapshotJson.parse(Files.readString(f)) else SchemaSnap(Nil)
  }

  def existing(dbDir: Path = defaultDbDir): List[(Int, Path)] = {
    val dir = migrationsDir(dbDir)
    if (!Files.exists(dir)) return Nil
    Files
      .list(dir)
      .iterator()
      .asScala
      .filter(_.getFileName.toString.endsWith(".sql"))
      .flatMap { p =>
        val n = p.getFileName.toString.takeWhile(_.isDigit)
        if (n.isEmpty) None else Some(n.toInt -> p)
      }
      .toList
      .sortBy(_._1)
  }

  def nextNumber(dbDir: Path = defaultDbDir): Int =
    existing(dbDir).lastOption.map(_._1 + 1).getOrElse(1)

  def slug(s: String): String =
    s.toLowerCase.replaceAll("[^a-z0-9]+", "_").stripPrefix("_").stripSuffix("_")

  def write(
      name: String,
      resolutions: List[Resolution],
      newSnapshot: SchemaSnap,
      dbDir: Path = defaultDbDir
  ): Path = {
    val stmts = resolutions.flatMap {
      case Resolution(c, Decision.Accept)     => Ddl.render(c) :: Nil
      case Resolution(_, Decision.Skip)       => Nil
      case Resolution(_, Decision.Manual(ss)) => ss
    }
    val n   = nextNumber(dbDir)
    val m   = Migration(n, slug(name), stmts, Migration.fingerprint(stmts))
    val dir = migrationsDir(dbDir)

    Files.createDirectories(dir)
    val out = dir.resolve(m.filename)
    Files.writeString(out, m.render)
    Files.writeString(snapshotFile(dbDir), newSnapshot.render + "\n")
    out
  }
}
