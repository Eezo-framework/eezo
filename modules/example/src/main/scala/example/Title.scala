package example

import io.eezo.db.*

opaque type Title = String

object Title {
  def apply(s: String): Title = s

  extension (t: Title) {
    def value: String = t
  }

  given Column[Title] =
    Column[String].withCheck(Check.MaxLen(100)).imap[Title](s => s)(t => t)
}
