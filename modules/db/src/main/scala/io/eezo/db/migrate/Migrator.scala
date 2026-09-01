package io.eezo.db.migrate

import io.eezo.db.capability.*

import java.nio.file.{Files, Path}
import java.time.Instant

final case class Applied(number: Int, name: String, fingerprint: String, appliedAt: Instant)

/** The migration ledger and the migrations that fill it.
  *
  * Every entry point takes a `Tx`, including the reading ones: `applied` ensures the ledger exists
  * first, and creating a table is a write. Nothing here opens a scope of its own — a caller wraps
  * the whole run in one `transact`, which is what makes a failed batch roll back including its
  * ledger rows.
  */
object Migrator {

  private val Ledger = "eezo_migrations"

  def ensureLedger()(using tx: Tx): Unit = {
    val st = tx.connection.createStatement()
    st.execute(s"""create table if not exists "$Ledger" (
         |  number integer primary key,
         |  name text not null,
         |  fingerprint text not null,
         |  applied_at timestamptz not null default now()
         |)""".stripMargin)
    st.close()
  }

  def applied()(using tx: Tx): List[Applied] = {
    ensureLedger()
    val ps = tx.connection.prepareStatement(
      s"""select number, name, fingerprint, applied_at from "$Ledger" order by number"""
    )
    val rs  = ps.executeQuery()
    val out = Iterator
      .continually(rs)
      .takeWhile(_.next())
      .map { r =>
        Applied(
          r.getInt(1),
          r.getString(2),
          r.getString(3),
          r.getObject(4, classOf[java.time.OffsetDateTime]).toInstant
        )
      }
      .toList
    rs.close(); ps.close()
    out
  }

  sealed trait Status
  object Status {
    case class Ok(pending: List[(Int, String, List[String])]) extends Status
    case class Tampered(problems: List[String])               extends Status
  }

  /** Verifies every on-disk migration, and checks applied ones still match the ledger. */
  def status(dbDir: Path = Freeze.defaultDbDir)(using tx: Tx): Status = {
    val onDisk   = Freeze.existing(dbDir)
    val ledger   = applied().map(a => a.number -> a).toMap
    val problems = List.newBuilder[String]
    val pending  = List.newBuilder[(Int, String, List[String])]

    onDisk.foreach { case (n, path) =>
      val content = Files.readString(path)
      Migration.verify(content) match {
        case Left(err) =>
          problems += s"${path.getFileName}: $err"
        case Right(stmts) =>
          val fp = Migration.fingerprint(stmts)
          ledger.get(n) match {
            case Some(a) if a.fingerprint != fp =>
              problems += s"${path.getFileName}: already applied with fingerprint ${a.fingerprint}, " +
                s"file now hashes to $fp — the file changed after being applied"
            case Some(_) => // already applied, unchanged
            case None    => pending += ((n, path.getFileName.toString, stmts))
          }
      }
    }

    val diskNumbers = onDisk.map(_._1).toSet
    ledger.keys.filterNot(diskNumbers.contains).toList.sorted.foreach { n =>
      problems += s"migration $n is recorded as applied but no file exists on disk"
    }

    val p = problems.result()
    if (p.nonEmpty) Status.Tampered(p) else Status.Ok(pending.result())
  }

  /** Applies a batch. The transaction is the caller's: DESIGN §8.13 — this used to save and restore
    * autocommit, commit and roll back for itself, which is exactly the work `transact` does.
    */
  def apply(pending: List[(Int, String, List[String])])(using tx: Tx): Unit = {
    val c = tx.connection
    ensureLedger()
    pending.foreach { case (n, file, stmts) =>
      val st = c.createStatement()
      // Silent by design: a library that prints cannot be embedded, and everything a front-end
      // might narrate — the file, the statements — is already in `pending`, in its hands before
      // this call. (This replaced two printlns; backlog "Smaller / noted" records the defect.)
      stmts.foreach(sql => st.execute(sql))
      st.close()

      val name = file.dropWhile(_.isDigit).stripPrefix("_").stripSuffix(".sql")
      val ps   = c.prepareStatement(
        s"""insert into "$Ledger" (number, name, fingerprint) values (?, ?, ?)"""
      )
      ps.setInt(1, n)
      ps.setString(2, name)
      ps.setString(3, Migration.fingerprint(stmts))
      ps.execute()
      ps.close()
    }
  }
}
