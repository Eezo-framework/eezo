package io.eezo.db

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

final case class Resolution(change: Change, decision: Decision)

/** FIXME Note the invariant: the snapshot is only written when the migration is. If a change is
  * Skipped, the snapshot still records the new code state — which means the next freeze won't
  * re-offer it. That's probably wrong, and it's a decision you should make consciously. The
  * alternative is that skipping means the snapshot keeps the old value for that column, so the
  * drift persists and gets re-offered forever. I lean toward the second (skip = "not yet", not
  * "never"), but it makes the snapshot no longer a pure function of the code, which breaks the
  * verify story. Worth thinking about — for now Skip is rare enough to punt.
  */
enum Decision {
  case Accept
  case Skip
  case Manual(sql: List[String])
}

object Freeze {

  val dbDir: Path         = Paths.get("db")
  val migrationsDir: Path = dbDir.resolve("migrations")
  val snapshotFile: Path  = dbDir.resolve("schema.json")

  def committedSnapshot(): SchemaSnap =
    if (Files.exists(snapshotFile)) SnapshotJson.parse(Files.readString(snapshotFile))
    else SchemaSnap(Nil)

  def existing(): List[(Int, Path)] = {
    if (!Files.exists(migrationsDir)) return Nil
    Files
      .list(migrationsDir)
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

  def nextNumber(): Int = existing().lastOption.map(_._1 + 1).getOrElse(1)

  def slug(s: String): String =
    s.toLowerCase.replaceAll("[^a-z0-9]+", "_").stripPrefix("_").stripSuffix("_")

  def write(name: String, resolutions: List[Resolution], newSnapshot: SchemaSnap): Path = {
    val stmts = resolutions.flatMap {
      case Resolution(c, Decision.Accept)     => Ddl.render(c) :: Nil
      case Resolution(_, Decision.Skip)       => Nil
      case Resolution(_, Decision.Manual(ss)) => ss
    }
    val n = nextNumber()
    val m = Migration(n, slug(name), stmts, Migration.fingerprint(stmts))

    Files.createDirectories(migrationsDir)
    val out = migrationsDir.resolve(m.filename)
    Files.writeString(out, m.render)
    Files.writeString(snapshotFile, newSnapshot.render + "\n")
    out
  }
}
