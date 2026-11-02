package io.eezo.sbt.dev

/** A JSON value and a reader for it, the plugin's own.
  *
  * The plugin cannot depend on `eezo-core`, which has the framework's `Json`: an sbt plugin is on
  * the build's classpath, not the application's, and is compiled for Scala 2.12 as well as 3. So
  * this is the reader the console uses on what the application writes, through `--json` and the
  * marker lines, and it is as small as that job: the documented shapes in `docs/reference/cli.md`
  * are objects, arrays, strings, integers, booleans and null, and nothing else is accepted.
  *
  * Read, never written: nothing in the plugin produces JSON.
  */
private[sbt] sealed trait JValue {

  /** Field `name` of an object, `JNull` for anything else so a chain of lookups never throws. */
  def apply(name: String): JValue = this match {
    case JObj(fields) => fields.find(_._1 == name).map(_._2).getOrElse(JNull)
    case _            => JNull
  }

  def str: Option[String]               = this match { case JStr(v) => Some(v); case _ => None }
  def num: Option[Long]                 = this match { case JNum(v) => Some(v); case _ => None }
  def bool: Option[Boolean]             = this match { case JBool(v) => Some(v); case _ => None }
  def arr: Vector[JValue]               = this match { case JArr(v) => v; case _ => Vector.empty }
  def strOr(default: String): String    = str.getOrElse(default)
  def boolOr(default: Boolean): Boolean = bool.getOrElse(default)
}

private[sbt] final case class JStr(v: String)                        extends JValue
private[sbt] final case class JNum(v: Long)                          extends JValue
private[sbt] final case class JBool(v: Boolean)                      extends JValue
private[sbt] case object JNull                                       extends JValue
private[sbt] final case class JArr(items: Vector[JValue])            extends JValue
private[sbt] final case class JObj(fields: Vector[(String, JValue)]) extends JValue

private[sbt] object JsonReader {

  /** One value, the whole input: a failure names the offset. */
  def parse(s: String): Either[String, JValue] = {
    val p = new Parser(s)
    try {
      val v = p.value()
      p.skipWhitespace()
      if (p.done) Right(v) else Left(p.error("end of input"))
    } catch {
      case Failure(message) => Left(message)
    }
  }

  private final case class Failure(message: String) extends RuntimeException(message) {
    override def fillInStackTrace(): Throwable = this
  }

  private final class Parser(s: String) {

    private var at = 0

    def done: Boolean = at >= s.length

    def error(expected: String): String = {
      val found = if (done) "end of input" else s"'${s.charAt(at)}'"
      s"expected $expected at offset $at, found $found"
    }

    private def fail(expected: String): Nothing = throw Failure(error(expected))

    def skipWhitespace(): Unit =
      while (
        !done && (s.charAt(at) == ' ' || s.charAt(at) == '\n' || s.charAt(at) == '\r' ||
          s.charAt(at) == '\t')
      ) at += 1

    private def expect(c: Char): Unit = {
      if (done || s.charAt(at) != c) fail(s"'$c'")
      at += 1
    }

    private def literal(word: String, v: JValue): JValue = {
      if (!s.startsWith(word, at)) fail(s"'$word'")
      at += word.length
      v
    }

    def value(): JValue = {
      skipWhitespace()
      if (done) fail("a JSON value")
      s.charAt(at) match {
        case '{'                        => obj()
        case '['                        => arr()
        case '"'                        => JStr(string())
        case 't'                        => literal("true", JBool(true))
        case 'f'                        => literal("false", JBool(false))
        case 'n'                        => literal("null", JNull)
        case c if c == '-' || c.isDigit => number()
        case _                          => fail("a JSON value")
      }
    }

    private def obj(): JValue = {
      expect('{')
      skipWhitespace()
      if (!done && s.charAt(at) == '}') { at += 1; return JObj(Vector.empty) }
      val fields = Vector.newBuilder[(String, JValue)]
      var more   = true
      while (more) {
        skipWhitespace()
        val name = string()
        skipWhitespace()
        expect(':')
        fields += (name -> value())
        skipWhitespace()
        if (!done && s.charAt(at) == ',') at += 1 else more = false
      }
      expect('}')
      JObj(fields.result())
    }

    private def arr(): JValue = {
      expect('[')
      skipWhitespace()
      if (!done && s.charAt(at) == ']') { at += 1; return JArr(Vector.empty) }
      val items = Vector.newBuilder[JValue]
      var more  = true
      while (more) {
        items += value()
        skipWhitespace()
        if (!done && s.charAt(at) == ',') at += 1 else more = false
      }
      expect(']')
      JArr(items.result())
    }

    private def string(): String = {
      expect('"')
      val sb = new StringBuilder
      while (true) {
        if (done) fail("a closing '\"'")
        val c = s.charAt(at)
        if (c == '"') { at += 1; return sb.result() }
        else if (c == '\\') {
          at += 1
          if (done) fail("an escape")
          s.charAt(at) match {
            case '"'  => sb.append('"')
            case '\\' => sb.append('\\')
            case '/'  => sb.append('/')
            case 'b'  => sb.append('\b')
            case 'f'  => sb.append('\f')
            case 'n'  => sb.append('\n')
            case 'r'  => sb.append('\r')
            case 't'  => sb.append('\t')
            case 'u'  =>
              if (at + 5 > s.length) fail("four hex digits")
              val hex = s.substring(at + 1, at + 5)
              val cp  =
                try Integer.parseInt(hex, 16)
                catch { case _: NumberFormatException => fail("four hex digits") }
              sb.append(cp.toChar)
              at += 4
            case _ => fail("a JSON escape")
          }
          at += 1
        } else { sb.append(c); at += 1 }
      }
      sb.result()
    }

    private def number(): JValue = {
      val start = at
      if (s.charAt(at) == '-') at += 1
      if (done || !s.charAt(at).isDigit) fail("a digit")
      while (!done && s.charAt(at).isDigit) at += 1
      if (!done && (s.charAt(at) == '.' || s.charAt(at) == 'e' || s.charAt(at) == 'E'))
        throw Failure(s"an integer at offset $start: the dev loop's JSON carries integers only")
      val text = s.substring(start, at)
      try JNum(java.lang.Long.parseLong(text))
      catch {
        case _: NumberFormatException => throw Failure(s"a number in Long range at offset $start")
      }
    }
  }
}
