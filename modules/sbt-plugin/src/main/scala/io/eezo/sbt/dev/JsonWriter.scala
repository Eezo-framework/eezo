package io.eezo.sbt.dev

/** `JsonReader`'s inverse, compact, for the event stream `eezoDev --json` prints. Strings are
  * escaped the way the core module's `Json.compact` escapes them, so a line parses with any reader,
  * `JsonReader` included.
  */
private[sbt] object JsonWriter {

  def write(j: JValue): String = {
    val sb = new StringBuilder
    render(j, sb)
    sb.toString
  }

  private def render(j: JValue, sb: StringBuilder): Unit = j match {
    case JStr(v)  => string(v, sb)
    case JNum(v)  => sb.append(v): Unit
    case JBool(v) => sb.append(v): Unit
    case JNull    => sb.append("null"): Unit
    case JArr(xs) =>
      sb.append('[')
      var i = 0
      while (i < xs.size) {
        if (i > 0) sb.append(',')
        render(xs(i), sb)
        i += 1
      }
      sb.append(']'): Unit
    case JObj(fields) =>
      sb.append('{')
      var i = 0
      while (i < fields.size) {
        if (i > 0) sb.append(',')
        string(fields(i)._1, sb)
        sb.append(':')
        render(fields(i)._2, sb)
        i += 1
      }
      sb.append('}'): Unit
  }

  private def string(s: String, sb: StringBuilder): Unit = {
    sb.append('"')
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      c match {
        case '"'           => sb.append("\\\"")
        case '\\'          => sb.append("\\\\")
        case '\n'          => sb.append("\\n")
        case '\r'          => sb.append("\\r")
        case '\t'          => sb.append("\\t")
        case c if c < 0x20 =>
          sb.append("\\u")
          val hex = Integer.toHexString(c.toInt)
          var pad = hex.length
          while (pad < 4) { sb.append('0'); pad += 1 }
          sb.append(hex)
        case c => sb.append(c)
      }
      i += 1
    }
    sb.append('"'): Unit
  }
}
