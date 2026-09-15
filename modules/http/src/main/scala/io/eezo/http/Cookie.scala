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
  * The defaults are the ones the surveyed frameworks agree on: `Path=/`, `HttpOnly`,
  * `SameSite=Lax`, no `Max-Age` so the cookie lives with the browser session, and `Secure` off,
  * because it is the request's scheme, not the cookie, that knows whether it can be on. `Domain` is
  * deliberately absent until something asks for it.
  */
final case class Cookie(
    name: String,
    value: String,
    path: String = "/",
    maxAge: Option[Long] = None,
    secure: Boolean = false,
    httpOnly: Boolean = true,
    sameSite: Cookie.SameSite = Cookie.SameSite.Lax
) {
  require(Cookie.isToken(name), s"cookie name '$name' is not an RFC 2616 token")
  require(
    value.forall(Cookie.isOctet),
    s"cookie value for '$name' holds a character outside RFC 6265's cookie-octet rule; encode it"
  )

  /** The `Set-Cookie` value, attributes in the order Jetty writes them, each only when set. */
  def render: String = {
    val attributes = Seq(
      Some(s"Path=$path"),
      maxAge.map(seconds => s"Max-Age=$seconds"),
      Option.when(secure)("Secure"),
      Option.when(httpOnly)("HttpOnly"),
      Some(s"SameSite=$sameSite")
    ).flatten
    (s"$name=$value" +: attributes).mkString("; ")
  }
}

object Cookie {

  /** Each case renders as its own name. */
  enum SameSite {
    case Strict, Lax, None
  }

  /** The cookie that tells a browser to drop `name`: an empty value and `Max-Age=0`. */
  def expired(name: String): Cookie = Cookie(name, "", maxAge = Some(0))

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
    */
  private[http] def parse(headers: Seq[String]): Map[String, String] =
    headers.iterator
      .flatMap(_.split(';').iterator)
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { pair =>
        val index = pair.indexOf('=')
        if (index < 0) pair -> "" else pair.take(index).trim -> unquote(pair.drop(index + 1).trim)
      }
      .distinctBy(_._1)
      .toMap

  private def unquote(value: String): String =
    if (value.length >= 2 && value.head == '"' && value.last == '"')
      value.substring(1, value.length - 1)
    else value
}
