package io.eezo.db.internal

object util {

  /** Erases the differences Postgres is entitled to introduce when it reformats a check expression,
    * so that a snapshot and an introspection can be compared.
    *
    * Case folds everything outside a single-quoted string literal: Postgres lowercases unquoted
    * identifiers and keywords on its own, so folding those is comparing like with like. A literal's
    * own content is left alone, because its case is part of what the check enforces — `status in
    * ('Draft','Published')` and `status in ('draft','published')` are different constraints, not
    * two spellings of the same one.
    */
  def canonicalCheck(s: String): String = {
    val collapsed = s.replace("\"", "").replaceAll("\\s+", " ").trim
    val result    = new StringBuilder(collapsed.length)
    var inLiteral = false
    for (c <- collapsed) {
      if (c == '\'') inLiteral = !inLiteral
      result.append(if (inLiteral) c else c.toLower)
    }
    result.toString
  }
}
