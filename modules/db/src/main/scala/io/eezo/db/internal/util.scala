package io.eezo.db.internal

object util {
  def snake(s: String): String =
    s.indices
      .foldLeft(new StringBuilder) { (b, i) =>
        val ch   = s(i)
        val prev = s.lift(i - 1)
        val next = s.lift(i + 1)
        // A capital starts a new word either the usual camelCase way (it follows a lowercase
        // letter or digit), or when it's the last capital of an acronym run and a lowercase
        // letter follows (HTTPLog -> http_log, splitting before the L).
        val newWord = ch.isUpper && (
          prev.exists(p => p.isLower || p.isDigit) ||
            (prev.exists(_.isUpper) && next.exists(_.isLower))
        )
        if (newWord && b.nonEmpty) b += '_'
        b += ch.toLower
      }
      .toString

  def canonicalCheck(s: String): String =
    s.replace("\"", "")
      .replaceAll("\\s+", " ")
      .toLowerCase
      .trim
}
