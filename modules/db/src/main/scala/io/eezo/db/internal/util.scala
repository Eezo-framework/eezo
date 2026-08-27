package io.eezo.db.internal

object util {

  /** Erases the differences Postgres is entitled to introduce when it reformats a check expression,
    * so that a snapshot and an introspection can be compared.
    */
  def canonicalCheck(s: String): String =
    s.replace("\"", "")
      .replaceAll("\\s+", " ")
      .toLowerCase
      .trim
}
