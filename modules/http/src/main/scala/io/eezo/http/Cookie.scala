package io.eezo.http

/** A cookie a response sets, as a value that renders its own `Set-Cookie` string.
  *
  * eezo writes `Set-Cookie` by hand rather than through Jetty's `Response.addCookie`, so that no
  * Jetty type sits in the API, the adapter's `write` stays a header copy, and no response carries
  * the `Expires: 1970` stamp Jetty adds beside `Max-Age`. The name is checked against RFC 2616's
  * token rule and the value against RFC 6265's `cookie-octet` rule at construction, which is the
  * one place a value that would break the header can be refused with a message that names it;
  * anything that is not already an octet, a base64url payload for one, encodes before it gets here.
  *
  * The attributes are eezo's choice, the one the surveyed frameworks agree on, rather than
  * defaults: every cookie is `Path=/`, `HttpOnly` and `SameSite=Lax`, and lives with the browser
  * session. Only `Secure` is a parameter, because it is the request's scheme, not the cookie, that
  * knows whether it can be on. `Max-Age` is set only by [[Cookie.expired]]. A custom path, a cookie
  * scripts can read, another `SameSite` or a lifetime arrive when something needs them, and so does
  * `Domain`.
  */
final case class Cookie private[http] (
    name: String,
    value: String,
    secure: Boolean,
    private[http] val maxAge: Option[Long]
) {
  require(Cookie.isToken(name), s"cookie name '$name' is not an RFC 2616 token")
  require(
    value.forall(Cookie.isOctet),
    s"cookie value for '$name' holds a character outside RFC 6265's cookie-octet rule; encode it"
  )

  /** The `Set-Cookie` value, attributes in the order Jetty writes them. */
  def render: String = {
    val attributes = Seq(
      Some("Path=/"),
      maxAge.map(seconds => s"Max-Age=$seconds"),
      Option.when(secure)("Secure"),
      Some("HttpOnly"),
      Some("SameSite=Lax")
    ).flatten
    (s"$name=$value" +: attributes).mkString("; ")
  }
}

object Cookie {

  /** A cookie that lives with the browser session, `Secure` only when `secure` says so. */
  def apply(name: String, value: String, secure: Boolean = false): Cookie =
    new Cookie(name, value, secure, None)

  /** The cookie that tells a browser to drop `name`: an empty value and `Max-Age=0`. */
  def expired(name: String): Cookie = new Cookie(name, "", false, Some(0))

  /** RFC 2616 `token`: one or more US-ASCII characters that are neither controls nor separators. */
  private def isToken(name: String): Boolean =
    name.nonEmpty && name.forall(c => c > 0x20 && c < 0x7f && !Separators.contains(c))

  private val Separators: Set[Char] = "()<>@,;:\\\"/[]?={} \t".toSet

  /** RFC 6265 `cookie-octet`: `%x21 / %x23-2B / %x2D-3A / %x3C-5B / %x5D-7E`, which is US-ASCII
    * without controls, whitespace, the double quote, the comma, the semicolon and the backslash.
    */
  private def isOctet(c: Char): Boolean =
    c == 0x21 || (c >= 0x23 && c <= 0x2b) || (c >= 0x2d && c <= 0x3a) ||
      (c >= 0x3c && c <= 0x5b) || (c >= 0x5d && c <= 0x7e)

  /** Splits one or more `Cookie` header values into pairs: `;` separated, the name before the first
    * `=`, surrounding double quotes dropped from the value, and the first of a repeated name kept,
    * which is the pair the browser put first because it matched the longest path.
    *
    * A pair with no `=`, or whose name is empty or not an RFC 2616 token, is dropped rather than
    * read, which is the rule Jetty's RFC 6265 parser applies: no browser sends such a pair, so it
    * names no cookie, and inventing an empty one would let it shadow a well formed pair of the same
    * name further on. The value stays lenient and is not checked against `cookie-octet`.
    */
  private[http] def parse(headers: Seq[String]): Map[String, String] =
    headers.iterator
      .flatMap(_.split(';').iterator)
      .flatMap { pair =>
        val index = pair.indexOf('=')
        val name  = if (index < 0) "" else pair.take(index).trim
        Option.when(isToken(name))(name -> unquote(pair.drop(index + 1).trim))
      }
      .distinctBy(_._1)
      .toMap

  private def unquote(value: String): String =
    if (value.length >= 2 && value.head == '"' && value.last == '"')
      value.substring(1, value.length - 1)
    else value
}
