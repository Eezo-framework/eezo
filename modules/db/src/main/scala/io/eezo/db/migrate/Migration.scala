package io.eezo.db.migrate

import java.security.MessageDigest

final case class Migration(
    number: Int,
    name: String,
    statements: List[String],
    fingerprint: String
) {
  def filename: String = f"$number%04d_$name.sql"

  def render: String =
    s"""-- eezo migration $number
       |-- $name
       |-- fingerprint: $fingerprint
       |-- GENERATED. Do not edit; change the model and re-freeze.
       |
       |${statements.map(_ + ";").mkString("\n\n")}
       |""".stripMargin
}

object Migration {
  def fingerprint(statements: List[String]): String = {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(statements.mkString("\n").getBytes("UTF-8"))
    md.digest().take(8).map(b => f"$b%02x").mkString
  }

  private val Header = """-- fingerprint: ([0-9a-f]+)""".r

  def parse(content: String): Option[(String, List[String])] = {
    val declared = content.linesIterator.collectFirst { case Header(f) => f }
    val body     = content.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n")
    val stmts    = splitStatements(body)
    // Still naive about a $$-quoted body, which a migration for a function or trigger would
    // need: a semicolon there would still split a statement in two. Plain DDL, everything
    // `freeze` generates today, has none.
    declared.map(_ -> stmts)
  }

  /** Splits on `;`, skipping one found inside a single-quoted literal so a value like
    * `'semicolon; inside text'` does not become two broken statements.
    */
  private def splitStatements(body: String): List[String] = {
    var inLiteral = false
    var start     = 0
    var done      = List.empty[String]
    for (i <- body.indices) {
      val c = body(i)
      if (c == '\'') inLiteral = !inLiteral
      else if (c == ';' && !inLiteral) {
        done = body.substring(start, i) :: done
        start = i + 1
      }
    }
    (body.substring(start) :: done).reverse.map(_.trim).filter(_.nonEmpty)
  }

  def verify(content: String): Either[String, List[String]] =
    parse(content) match {
      case None                    => Left("no fingerprint header")
      case Some((declared, stmts)) =>
        val actual = fingerprint(stmts)
        if (actual == declared) Right(stmts)
        else Left(s"fingerprint mismatch: file says $declared, content hashes to $actual")
    }
}
