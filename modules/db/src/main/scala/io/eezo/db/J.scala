package io.eezo.db

enum J {
  case S(v: String)
  case N(v: Long)
  case B(v: Boolean)
  case Nul
  case A(items: List[J])
  case O(fields: List[(String, J)])
}

object J {
  def escape(s: String): String = s.flatMap {
    case '"'          => "\\\""
    case '\\'         => "\\\\"
    case '\n'         => "\\n"
    case '\r'         => "\\r"
    case '\t'         => "\\t"
    case c if c < ' ' => f"\\u${c.toInt}%04x"
    case c            => c.toString
  }

  def render(j: J, indent: Int = 0): String = {
    val pad  = "  " * indent
    val pad1 = "  " * (indent + 1)
    j match {
      case J.S(v)   => "\"" + escape(v) + "\""
      case J.N(v)   => v.toString
      case J.B(v)   => v.toString
      case J.Nul    => "null"
      case J.A(Nil) => "[]"
      case J.A(xs)  =>
        xs.map(x => pad1 + render(x, indent + 1)).mkString("[\n", ",\n", "\n" + pad + "]")
      case J.O(Nil) => "{}"
      case J.O(fs)  =>
        fs.map { case (k, v) => pad1 + "\"" + escape(k) + "\": " + render(v, indent + 1) }
          .mkString("{\n", ",\n", "\n" + pad + "}")
    }
  }
}
