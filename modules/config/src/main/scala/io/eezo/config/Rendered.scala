package io.eezo.config

import scala.jdk.CollectionConverters.*

import com.typesafe.config.{ConfigException, ConfigRenderOptions, ConfigUtil}

/** What an unresolved value of the file is made of, read off its rendering.
  *
  * The library exposes no walk over the substitutions of a value: the classes that hold them are
  * private to its package. An unresolved value renders as the author wrote it, though, a
  * substitution as `${?VAR}` and every piece of text as a quoted string, so a scan that steps over
  * quoted strings finds every substitution and every piece of text, and text that merely contains
  * `${` inside quotes is never taken for one.
  */
private[config] object Rendered {

  /** JSON quoting, so that every piece of text is a quoted string the scan can step over. */
  val Options: ConfigRenderOptions = ConfigRenderOptions.concise()

  /** `references` are the paths substituted, as the library hands them to a resolver, in the order
    * they render; `literal` is whether any scalar text sits beside them. A list or an object is not
    * text, and what sits inside one does not make the value text, though a substitution inside one
    * is still a reference.
    */
  final case class Scan(references: List[String], literal: Boolean)

  def scan(rendered: String): Scan = {
    val references = List.newBuilder[String]
    var literal    = false
    var depth      = 0
    var i          = 0
    while (i < rendered.length) {
      rendered(i) match {
        case '"' =>
          if (depth == 0) literal = true
          i = quoted(rendered, i)
        case '$' if rendered.startsWith("${", i) =>
          val end  = closing(rendered, i + 2)
          val body = rendered.substring(i + 2, end)
          references += body.stripPrefix("?").trim
          i = end + 1
        // A merge of two values renders them side by side, separated by a comma.
        case c if c.isWhitespace || c == ',' => i += 1
        case '[' | '{'                       =>
          depth += 1
          i += 1
        case ']' | '}' =>
          depth -= 1
          i += 1
        // A null in a merge is no text the author wrote, so it is no literal either.
        case 'n' if token(rendered, i, "null") => i += 4
        case _                                 =>
          if (depth == 0) literal = true
          i += 1
      }
    }
    Scan(references.result(), literal)
  }

  /** Whether `word` stands alone at `from`, ending where the rendering ends or a merge separates.
    */
  private def token(text: String, from: Int, word: String): Boolean = {
    val end = from + word.length
    text.startsWith(word, from) && (end == text.length || text(end).isWhitespace || text(
      end
    ) == ',')
  }

  /** The index just past the quoted string that opens at `from`. */
  private def quoted(text: String, from: Int): Int = {
    var i = from + 1
    while (i < text.length && text(i) != '"') i += (if (text(i) == '\\') 2 else 1)
    i + 1
  }

  /** The index of the `}` that closes a substitution whose path starts at `from`; a quoted key may
    * hold one.
    */
  private def closing(text: String, from: Int): Int = {
    var i = from
    while (i < text.length && text(i) != '}') i = if (text(i) == '"') quoted(text, i) else i + 1
    i
  }

  /** The keys of a substituted path, or none when the path does not parse. */
  def segments(reference: String): List[String] =
    try ConfigUtil.splitPath(reference).asScala.toList
    catch { case _: ConfigException => Nil }
}
