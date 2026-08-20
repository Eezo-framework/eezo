package io.eezo.db

import java.nio.file.Files
import java.sql.Connection
import java.time.Instant

final case class Applied(number: Int, name: String, fingerprint: String, appliedAt: Instant)

object Migrator {

  private val Ledger = "eezo_migrations"

  def ensureLedger(c: Connection): Unit = {
    val st = c.createStatement()
    st.execute(
      s"""create table if not exists "$Ledger" (
         |  number integer primary key,
         |  name text not null,
         |  fingerprint text not null,
         |  applied_at timestamptz not null default now()
         |)""".stripMargin)
    st.close()
  }

  def applied(c: Connection): List[Applied] = {
    ensureLedger(c)
    val ps = c.prepareStatement(
      s"""select number, name, fingerprint, applied_at from "$Ledger" order by number""")
    val rs = ps.executeQuery()
    val out = Iterator.continually(rs).takeWhile(_.next()).map { r =>
      Applied(r.getInt(1), r.getString(2), r.getString(3),
        r.getObject(4, classOf[java.time.OffsetDateTime]).toInstant)
    }.toList
    rs.close(); ps.close()
    out
  }

  sealed trait Status
  object Status {
    case class Ok(pending: List[(Int, String, List[String])]) extends Status
    case class Tampered(problems: List[String]) extends Status
  }

  /** Verifies every on-disk migration, and checks applied ones still match the ledger. */
  def status(c: Connection): Status = {
    val onDisk = Freeze.existing()
    val ledger = applied(c).map(a => a.number -> a).toMap
    val problems = List.newBuilder[String]
    val pending = List.newBuilder[(Int, String, List[String])]

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

  def apply(c: Connection, pending: List[(Int, String, List[String])]): Unit = {
    ensureLedger(c)
    val prevAutoCommit = c.getAutoCommit
    c.setAutoCommit(false)
    try {
      pending.foreach { case (n, file, stmts) =>
        println(s"  applying $file")
        val st = c.createStatement()
        stmts.foreach { sql =>
          println(s"    ${sql.linesIterator.next().take(80)}")
          st.execute(sql)
        }
        st.close()

        val name = file.dropWhile(_.isDigit).stripPrefix("_").stripSuffix(".sql")
        val ps = c.prepareStatement(
          s"""insert into "$Ledger" (number, name, fingerprint) values (?, ?, ?)""")
        ps.setInt(1, n)
        ps.setString(2, name)
        ps.setString(3, Migration.fingerprint(stmts))
        ps.execute()
        ps.close()
      }
      c.commit()
    } catch {
      case e: Throwable => c.rollback(); throw e
    } finally c.setAutoCommit(prevAutoCommit)
  }
}
