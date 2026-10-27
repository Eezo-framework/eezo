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

  /** The statements a migration file holds, and the fingerprint it declares.
    *
    * Two candidate splits are offered, and the declared fingerprint chooses between them: the
    * scanned one, which knows about quoting, and the plain one this method used before it knew
    * anything. The scanned split wins when it matches, the plain split is taken when the scanned
    * one does not match and it does, and when neither matches the scanned one is returned so that
    * `verify` reports the mismatch it would have reported anyway. A file that verified under the
    * plain split therefore still verifies, whatever the scanner makes of its quoting.
    *
    * Still naive about a `$$`-quoted body, and about a line inside a literal whose own text begins
    * with `--`, which the line filter above drops as if it were a header. Neither arrives from
    * anything `freeze` renders itself, but `Decision.Manual` SQL reaches the file verbatim, so a
    * hand-written function or trigger body can carry either (issue 352).
    */
  def parse(content: String): Option[(String, List[String])] = {
    val declared = content.linesIterator.collectFirst { case Header(f) => f }
    val body     = content.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n")
    val scanned  = splitStatements(body)
    val plain    = body.split(";").map(_.trim).filter(_.nonEmpty).toList
    declared.map { f =>
      if (fingerprint(scanned) == f || fingerprint(plain) != f) f -> scanned else f -> plain
    }
  }

  /** Splits on `;`, skipping one inside a single-quoted literal or a double-quoted identifier, so a
    * value like `'semicolon; inside text'` and an identifier like `"o'clock"` stay whole.
    *
    * A `--` comment runs to the end of its line, and inside it a quote is inert: an apostrophe in
    * `-- don't` opens nothing. A `;` there still terminates, because `render` writes the statement
    * terminator on the same line as the statement, so the one that closes a statement ending in a
    * comment sits inside that comment's text.
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
      if (c == '\n') inComment = false
      if (inComment) ()
      else if (inLiteral) {
        if (c == '\'') inLiteral = false
      } else if (inIdent) {
        if (c == '"') inIdent = false
      } else if (c == '\'') inLiteral = true
      else if (c == '"') inIdent = true
      else if (c == '-' && i + 1 < body.length && body(i + 1) == '-') inComment = true
      if (c == ';' && !inLiteral && !inIdent) {
        done = body.substring(start, i) :: done
        start = i + 1
      }
      i += 1
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
