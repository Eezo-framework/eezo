package io.eezo.core.internal

/** Naming helpers that more than one module uses. */
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
