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
    val stmts    = body.split(";").map(_.trim).filter(_.nonEmpty).toList
    // Naive: a semicolon inside a string literal or a $$-quoted body splits a statement in two,
    // and the comment filter above eats a `--` inside a literal. BACKLOG §26.
    declared.map(_ -> stmts)
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
