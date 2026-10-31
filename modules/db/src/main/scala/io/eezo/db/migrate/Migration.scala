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
    * The plain split still sees every line that begins with `--` removed first, which is the
    * reading a file written before the scanner knew about quotes was checked against. The scanned
    * split sees the raw file, and drops such a line only when it starts outside a quote, so a line
    * of a literal or a dollar quoted body is kept.
    *
    * A backslash escaped quote is still not understood, and a `;` inside a `--` comment's own text
    * still cuts. The second is deliberate: `render` writes the terminator of a statement that ends
    * in a comment on that same line, so a `;` there has to end the statement.
    */
  def parse(content: String): Option[(String, List[String])] = {
    val declared = content.linesIterator.collectFirst { case Header(f) => f }
    val filtered = content.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n")
    val scanned  = splitStatements(content)
    val plain    = filtered.split(";").map(_.trim).filter(_.nonEmpty).toList
    declared.map { f =>
      if (fingerprint(scanned) == f || fingerprint(plain) != f) f -> scanned else f -> plain
    }
  }

  /** Splits on `;`, skipping one inside a single quoted literal, a double quoted identifier, a
    * dollar quoted body, or a block comment, so `'semicolon; inside text'` and `"o'clock"` stay
    * whole.
    *
    * A dollar quote is `$$` or `$tag$`, and the tag is letters, digits and underscores. It runs to
    * the next copy of that same opener. Postgres does not nest these. A `$1` is not an opener.
    * Inside, a quote, a comment and a `;` are inert.
    *
    * A block comment is `/* */` and may nest. A quote or a `;` inside it is inert. Unlike a
    * trailing `--` comment, `render` does not hide the terminator inside the block.
    *
    * A `--` comment runs to the end of its line, and inside it a quote is inert: an apostrophe in
    * `-- don't` opens nothing. A `;` there still terminates, because `render` writes the statement
    * terminator on the same line as the statement. A line that is only such a comment, and that
    * starts outside any quote, is left out of the statement text, so the header is not part of a
    * statement and a line of a literal that begins with `--` is kept.
    */
  private def splitStatements(body: String): List[String] = {
    val buf                    = new StringBuilder
    var done                   = List.empty[String]
    var i                      = 0
    var inLiteral              = false
    var inIdent                = false
    var inLineComment          = false
    var blockDepth             = 0
    var dollar: Option[String] = None

    def shielded: Boolean = inLiteral || inIdent || dollar.isDefined || blockDepth > 0

    def emit(): Unit = {
      val statement = buf.toString.trim
      buf.clear()
      if (statement.nonEmpty) done = statement :: done
    }

    def atLineStart(at: Int): Boolean = at == 0 || body(at - 1) == '\n'

    def wholeLineComment(at: Int): Boolean = {
      var j = at
      while (j < body.length && (body(j) == ' ' || body(j) == '\t')) j += 1
      j + 1 < body.length && body(j) == '-' && body(j + 1) == '-'
    }

    def skipLine(at: Int): Int = {
      var j = at
      while (j < body.length && body(j) != '\n') j += 1
      if (j < body.length) j + 1 else j
    }

    def precededByIdentChar(at: Int): Boolean =
      at > 0 && (body(at - 1).isLetterOrDigit || body(at - 1) == '_' || body(at - 1) == '$')

    /** `$$`, or `$tag$` with a tag of letters, digits and underscores. Nothing else, so `$1` stays
      * a parameter. Postgres allows `$` inside an unquoted identifier, so `foo$tag$` is one
      * identifier and not `foo` followed by an opener.
      */
    def dollarDelimiter(at: Int): Option[String] =
      if (at >= body.length || body(at) != '$' || precededByIdentChar(at)) None
      else if (at + 1 < body.length && body(at + 1) == '$') Some("$$")
      else {
        var j = at + 1
        if (j < body.length && (body(j).isLetter || body(j) == '_')) {
          j += 1
          while (j < body.length && (body(j).isLetterOrDigit || body(j) == '_')) j += 1
          if (j < body.length && body(j) == '$') Some(body.substring(at, j + 1)) else None
        } else None
      }

    while (i < body.length) {
      if (!shielded && !inLineComment && atLineStart(i) && wholeLineComment(i))
        i = skipLine(i)
      else {
        val c = body(i)
        if (c == '\n') inLineComment = false
        if (inLineComment) {
          if (c == ';') emit() else buf.append(c)
          i += 1
        } else if (dollar.isDefined && body.startsWith(dollar.get, i)) {
          val delim = dollar.get
          buf.append(delim)
          dollar = None
          i += delim.length
        } else if (dollar.isDefined) {
          buf.append(c)
          i += 1
        } else if (inLiteral) {
          buf.append(c)
          if (c == '\'') inLiteral = false
          i += 1
        } else if (inIdent) {
          buf.append(c)
          if (c == '"') inIdent = false
          i += 1
        } else if (blockDepth > 0) {
          if (body.startsWith("/*", i)) {
            buf.append("/*")
            blockDepth += 1
            i += 2
          } else if (body.startsWith("*/", i)) {
            buf.append("*/")
            blockDepth -= 1
            i += 2
          } else {
            buf.append(c)
            i += 1
          }
        } else
          dollarDelimiter(i) match {
            case Some(delim) =>
              buf.append(delim)
              dollar = Some(delim)
              i += delim.length
            case None if body.startsWith("/*", i) =>
              buf.append("/*")
              blockDepth = 1
              i += 2
            case None if body.startsWith("--", i) =>
              buf.append("--")
              inLineComment = true
              i += 2
            case None if c == '\'' =>
              buf.append(c)
              inLiteral = true
              i += 1
            case None if c == '"' =>
              buf.append(c)
              inIdent = true
              i += 1
            case None if c == ';' =>
              emit()
              i += 1
            case None =>
              buf.append(c)
              i += 1
          }
      }
    }
    emit()
    done.reverse
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
