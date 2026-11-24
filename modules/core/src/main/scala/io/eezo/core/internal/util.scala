package io.eezo.core.internal

/** Naming helpers with no dependencies and more than one module reading them, which is the rule
  * `core` exists under.
  *
  * `snake` is here rather than in `db` because both sides of the framework name things with it and
  * neither can see the other: `db` turns a class name into a table name, and `http` turns the same
  * class name into a route path. One camel case rule, or the two go wrong differently.
  */
object util {

  /** `BookReview` becomes `book_review`, `HTTPLog` becomes `http_log`. */
  def snake(s: String): String =
    s.indices
      .foldLeft(new StringBuilder) { (b, i) =>
        val ch           = s(i)
        val prev         = s.lift(i - 1)
        val next         = s.lift(i + 1)
        val afterWord    = prev.exists(p => p.isLower || p.isDigit)
        val afterAcronym = prev.exists(_.isUpper) && next.exists(_.isLower)
        val newWord      = ch.isUpper && (afterWord || afterAcronym)
        if (newWord && b.nonEmpty) b += '_'
        b += ch.toLower
      }
      .toString
}
