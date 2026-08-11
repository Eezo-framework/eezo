package io.eezo.db

enum Check {
  case MinLen(n: Int)
  case MaxLen(n: Int)
  case Positive
  case Between(lo: Long, hi: Long)
  case Raw(sql: String)

  /** `col` is the already-quoted column name. */
  def render(col: String): String = this match {
    case MinLen(n)       => s"length($col) >= $n"
    case MaxLen(n)       => s"length($col) <= $n"
    case Positive        => s"$col > 0"
    case Between(lo, hi) => s"$col between $lo and $hi"
    case Raw(sql)        => sql.replace("{}", col)
  }
}
