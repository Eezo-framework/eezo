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

  /** Parses one JSON value, strictly: the whole input must be that value plus whitespace.
    *
    * The reader exists for the wire messages `modules/live` receives from a browser, which is why
    * it returns `Either` rather than throwing: every inbound frame is untrusted input, and a
    * malformed one is an ordinary value the page loop logs and survives. The message carries the
    * zero-based offset, because "unexpected character" without a position is a debugging session.
    *
    * Numbers are integers only, like [[Json.Num]] itself. A fraction or an exponent is refused by
    * name rather than truncated: nothing eezo puts on the wire is fractional, and a client that
    * sends `1.5` where the server expects an index is a client whose message should fail loudly.
    */
  def parse(s: String): Either[String, Json] = {
    val p = new Parser(s)
    try {
      val value = p.value()
      p.skipWhitespace()
      if (p.done) Right(value) else Left(p.error("one JSON value, then end of input"))
    } catch {
      case Parse(message) => Left(message)
    }
  }

  /** The reader's only failure, caught inside [[parse]] and never seen outside it. An exception
    * rather than `Either` threaded through every production, because the parser is recursive and
    * the failure is terminal: nothing recovers mid-value.
    */
  private final case class Parse(message: String) extends RuntimeException(message) {
    // The message is the payload; a stack trace into a hand rolled parser helps nobody.
    override def fillInStackTrace(): Throwable = this
  }

  /** Deep enough for any frame eezo writes or reads, shallow enough that a hostile `[[[[…` frame
    * inside the transport's size cap is a parse error rather than a stack overflow: the parser is
    * recursive, and recursion depth on untrusted input is a resource like any other.
    */
  private val MaxDepth = 64

  private final class Parser(s: String) {

    private var at    = 0
    private var depth = 0

    def done: Boolean = at >= s.length

    def error(expected: String): String = {
      val found = if (done) "end of input" else s"'${s.charAt(at)}'"
      s"expected $expected at offset $at, found $found"
    }

    private def fail(expected: String): Nothing = throw Parse(error(expected))

    def skipWhitespace(): Unit = {
      while (
        !done && (s.charAt(at) == ' ' || s.charAt(at) == '\n' ||
          s.charAt(at) == '\r' || s.charAt(at) == '\t')
      )
        at += 1
    }

    private def expect(c: Char): Unit = {
      if (done || s.charAt(at) != c) fail(s"'$c'")
      at += 1
    }

    /** Consumes `word` when it is next, for the three literals. */
    private def literal(word: String, value: Json): Json = {
      if (!s.startsWith(word, at)) fail(s"'$word'")
      at += word.length
      value
    }

    def value(): Json = {
      skipWhitespace()
      if (done) fail("a JSON value")
      s.charAt(at) match {
        case '{'                        => obj()
        case '['                        => arr()
        case '"'                        => Json.Str(string())
        case 't'                        => literal("true", Json.Bool(true))
        case 'f'                        => literal("false", Json.Bool(false))
        case 'n'                        => literal("null", Json.Null)
        case c if c == '-' || c.isDigit => number()
        case _                          => fail("a JSON value")
      }
    }

    private def descend(): Unit = {
      depth += 1
      if (depth > MaxDepth)
        throw Parse(s"nesting deeper than $MaxDepth at offset $at: refused, not recursed")
    }

    private def obj(): Json = {
      descend()
      expect('{')
      skipWhitespace()
      if (!done && s.charAt(at) == '}') { at += 1; depth -= 1; return Json.Obj(Nil) }
      val fields = List.newBuilder[(String, Json)]
      var more   = true
      while (more) {
        skipWhitespace()
        val name = string()
        skipWhitespace()
        expect(':')
        fields += name -> value()
        skipWhitespace()
        if (!done && s.charAt(at) == ',') at += 1
        else more = false
      }
      expect('}')
      depth -= 1
      Json.Obj(fields.result())
    }

    private def arr(): Json = {
      descend()
      expect('[')
      skipWhitespace()
      if (!done && s.charAt(at) == ']') { at += 1; depth -= 1; return Json.Arr(Nil) }
      val items = List.newBuilder[Json]
      var more  = true
      while (more) {
        items += value()
        skipWhitespace()
        if (!done && s.charAt(at) == ',') at += 1
        else more = false
      }
      expect(']')
      depth -= 1
      Json.Arr(items.result())
    }

    private def string(): String = {
      expect('"')
      val sb = new StringBuilder
      while (true) {
        if (done) fail("a closing '\"'")
        s.charAt(at) match {
          case '"' =>
            at += 1
            return sb.result()
          case '\\' =>
            at += 1
            if (done) fail("an escape character")
            s.charAt(at) match {
              case '"'  => sb.append('"'); at += 1
              case '\\' => sb.append('\\'); at += 1
              case '/'  => sb.append('/'); at += 1
              case 'b'  => sb.append('\b'); at += 1
              case 'f'  => sb.append('\f'); at += 1
              case 'n'  => sb.append('\n'); at += 1
              case 'r'  => sb.append('\r'); at += 1
              case 't'  => sb.append('\t'); at += 1
              case 'u'  =>
                at += 1
                if (at + 4 > s.length) fail("four hex digits")
                val hex = s.substring(at, at + 4)
                val cp  =
                  try Integer.parseInt(hex, 16)
                  catch { case _: NumberFormatException => fail("four hex digits") }
                sb.append(cp.toChar)
                at += 4
              case _ => fail("a JSON escape")
            }
          case c if c < ' ' => fail("no control character inside a string")
          case c            => sb.append(c); at += 1
        }
      }
      sb.result() // unreachable; the loop returns
    }

    private def number(): Json = {
      val start = at
      if (!done && s.charAt(at) == '-') at += 1
      if (done || !s.charAt(at).isDigit) fail("a digit")
      while (!done && s.charAt(at).isDigit) at += 1
      if (!done && (s.charAt(at) == '.' || s.charAt(at) == 'e' || s.charAt(at) == 'E'))
        throw Parse(
          s"a fractional or exponent number at offset $start: eezo's wire carries integers only"
        )
      val text = s.substring(start, at)
      text.toLongOption match {
        case Some(v) => Json.Num(v)
        case None    => throw Parse(s"a number out of Long range at offset $start: $text")
      }
    }
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
