package io.eezo.core.internal

/** A JSON value and its writer, hand rolled and deliberately small.
  *
  * It lives in `core` for the reason `build.sbt` gives for everything here: no dependencies of its
  * own and two dependents that never see each other. The database edge writes the schema snapshot
  * and its command results with it, the http edge writes the route listing, and a second hand
  * written escape in either would be the same code twice. It is not a JSON library, and
  * `derives Api` will not be built on it.
  */
enum Json {
  case Str(v: String)
  case Num(v: Long)
  case Bool(v: Boolean)
  case Null
  case Arr(items: List[Json])
  case Obj(fields: List[(String, Json)])
}

object Json {
  def escape(s: String): String = s.flatMap {
    case '"'          => "\\\""
    case '\\'         => "\\\\"
    case '\n'         => "\\n"
    case '\r'         => "\\r"
    case '\t'         => "\\t"
    case c if c < ' ' => f"\\u${c.toInt}%04x"
    case c            => c.toString
  }

  def render(j: Json, indent: Int = 0): String = {
    val pad  = "  " * indent
    val pad1 = "  " * (indent + 1)
    j match {
      case Json.Str(v)   => "\"" + escape(v) + "\""
      case Json.Num(v)   => v.toString
      case Json.Bool(v)  => v.toString
      case Json.Null     => "null"
      case Json.Arr(Nil) => "[]"
      case Json.Arr(xs)  =>
        xs.map(x => pad1 + render(x, indent + 1)).mkString("[\n", ",\n", "\n" + pad + "]")
      case Json.Obj(Nil) => "{}"
      case Json.Obj(fs)  =>
        fs.map { case (k, v) => pad1 + "\"" + escape(k) + "\": " + render(v, indent + 1) }
          .mkString("{\n", ",\n", "\n" + pad + "}")
    }
  }
}
