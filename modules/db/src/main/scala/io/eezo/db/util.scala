package io.eezo.db

object util {
  def snake(s: String): String =
    s.foldLeft(new StringBuilder) { (b, ch) =>
      if (ch.isUpper && b.nonEmpty) { b += '_'; b += ch.toLower }
      else b += ch.toLower
    }.toString
}
