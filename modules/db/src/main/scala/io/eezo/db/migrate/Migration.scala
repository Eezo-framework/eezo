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
    // Still naive about a $$-quoted body, and about a comment line that starts where a
    // literal happens to continue (the filter above drops it as if it were a header line).
    // Neither shows up in anything `freeze` generates on its own, but `Decision.Manual` SQL
    // reaches this file verbatim, so a hand-written function or trigger body can carry either.
    declared.map(_ -> stmts)
  }

  /** Splits on `;`, skipping one found inside a single-quoted literal, a double-quoted identifier,
    * or a `--` comment, so a value like `'semicolon; inside text'`, an identifier like `"o'clock"`,
    * or a trailing `-- comment with an apostrophe` does not break a statement in two or swallow the
    * one after it.
    *
    * Falls back to the plain split, exactly as before this method existed, if a literal or an
    * identifier is still open at the end: that reads as a quote this scanner cannot follow rather
    * than one it should trust, and a statement already split wrong is a smaller problem than every
    * statement after it disappearing into one that never closes.
    */
  private def splitStatements(body: String): List[String] = {
    var inLiteral = false
    var inIdent   = false
    var inComment = false
    var start     = 0
    var done      = List.empty[String]
    var i         = 0
    while (i < body.length) {
      val c = body(i)
      if (inComment) {
        if (c == '\n') inComment = false
      } else if (inLiteral) {
        if (c == '\'') inLiteral = false
      } else if (inIdent) {
        if (c == '"') inIdent = false
      } else if (c == '\'') inLiteral = true
      else if (c == '"') inIdent = true
      else if (c == '-' && i + 1 < body.length && body(i + 1) == '-') inComment = true
      else if (c == ';') {
        done = body.substring(start, i) :: done
        start = i + 1
      }
      i += 1
    }
    val parts =
      if (inLiteral || inIdent) body.split(";").toList
      else (body.substring(start) :: done).reverse
    parts.map(_.trim).filter(_.nonEmpty)
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
