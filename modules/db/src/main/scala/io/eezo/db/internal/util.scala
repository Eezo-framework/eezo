package io.eezo.db.internal

object util {

  /** Erases the differences Postgres is entitled to introduce when it reformats a check expression,
    * so that a snapshot and an introspection can be compared.
    *
    * Case and spacing fold everywhere outside a single-quoted string literal: Postgres lowercases
    * unquoted identifiers and keywords on its own and reformats space freely, so folding those is
    * comparing like with like. A literal's own content, case and internal spacing both, is left
    * exactly as written, because it is part of what the check enforces: `status in
    * ('Draft','Published')` and `status in ('draft','published')` are different constraints, not
    * two spellings of the same one, and a check against two literal spaces is not one against a
    * single space.
    */
  def canonicalCheck(s: String): String = {
    val unquoted  = s.replace("\"", "")
    val result    = new StringBuilder(unquoted.length)
    var inLiteral = false
    var sawSpace  = false
    for (c <- unquoted) {
      if (inLiteral) {
        result.append(c)
        if (c == '\'') inLiteral = false
      } else if (c == '\'') {
        if (sawSpace && result.nonEmpty) result.append(' ')
        sawSpace = false
        inLiteral = true
        result.append(c)
      } else if (c.isWhitespace) {
        sawSpace = true
      } else {
        if (sawSpace && result.nonEmpty) result.append(' ')
        sawSpace = false
        result.append(c.toLower)
      }
    }
    result.toString.trim
  }
}
