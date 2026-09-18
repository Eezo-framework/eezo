package io.eezo.http

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

import scala.util.control.NonFatal

/** What a browser carries between requests: a small map of strings, signed into one cookie.
  *
  * A handler reads it off `Request.session` and hands the one it wants kept to
  * `Response.withSession`; a response that names none keeps the request's. The write happens once,
  * after dispatch, and only when something changed, so a page that reads the session costs no
  * `Set-Cookie`. Flash rides inside the same cookie: [[flash(name, value)]] writes a value for the
  * next request and no other, where [[flash(name)]] reads it, which is how a create can say
  * "created" on the page it redirects to.
  *
  * Three maps rather than one, because a value has three lives here: an entry stays until removed,
  * a flash written now is `pending` until the cookie goes out, and a flash that came in is
  * `delivered` now and dropped from what goes out. The application sees entries and the two flash
  * doors; the names eezo reserves for itself start with an underscore and cannot be set from
  * outside, the way `_method` and `_csrf` are reserved among form fields.
  */
final case class Session private[http] (
    private[http] val entries: Map[String, String],
    private[http] val delivered: Map[String, String],
    private[http] val pending: Map[String, String]
) {

  def get(name: String): Option[String] = entries.get(name)

  def set(name: String, value: String): Session = {
    require(
      !name.startsWith(Session.Reserved),
      s"session entry '$name' starts with '${Session.Reserved}', which eezo reserves"
    )
    copy(entries = entries + (name -> value))
  }

  def remove(name: String): Session = copy(entries = entries - name)

  /** The entry under a reserved name, the door [[Csrf.read]] uses instead of reaching into
    * `entries` itself.
    *
    * `private[eezo]` rather than `private[http]` because `modules/auth` keeps the signed in user's
    * key and the path a guard refused under reserved names too, and `io.eezo.auth` is a different
    * package. The alternative was an ordinary entry, which an application could overwrite through
    * [[set]]: writing somebody else's key into `_user` would be a privilege escalation spelled with
    * the public API. The prefix is what makes that impossible, so the door has to open as wide as
    * the modules that need the prefix and no wider.
    */
  private[eezo] def reserved(name: String): Option[String] = entries.get(name)

  /** The session with `value` written under a reserved name, the door [[Csrf.carrying]] uses
    * instead of reaching into `entries` and `copy` itself. The guard mirrors [[set]]'s, the other
    * way round: a name that does not start with [[Session.Reserved]] does not belong here.
    */
  private[eezo] def withReserved(name: String, value: String): Session = {
    requireReserved(name, "withReserved")
    copy(entries = entries + (name -> value))
  }

  /** The session without a reserved entry. The other half of [[withReserved]], and the shape a
    * guard needs on the way out: a session naming a row that is no longer there has to lose that
    * name on the response, or the next request arrives stale in exactly the same way and nothing
    * ever clears it.
    */
  private[eezo] def withoutReserved(name: String): Session = {
    requireReserved(name, "withoutReserved")
    copy(entries = entries - name)
  }

  private def requireReserved(name: String, door: String): Unit =
    require(
      name.startsWith(Session.Reserved),
      s"session entry '$name' does not start with '${Session.Reserved}', which $door requires"
    )

  /** Whether the application holds any entry of its own. Flash does not count, and neither does
    * what eezo keeps under its reserved names: a session that carries only a flash, or only the
    * CSRF token dispatch minted into it, holds nothing the application put there.
    *
    * Deliberately not "nobody is signed in". The signed in user's key is a reserved entry too, so a
    * session naming a user still answers `true` here. The one caller that matters,
    * [[Csrf.protect]], is asking whether a handler emptied the session on purpose, and it asks
    * about the token in the same breath, so a login response, which carries a freshly minted one,
    * never reaches that arm.
    */
  def isEmpty: Boolean = entries.keysIterator.forall(_.startsWith(Session.Reserved))

  /** A flash delivered by the request that carried this session, readable on this request alone. */
  def flash(name: String): Option[String] = delivered.get(name)

  /** A flash for the next request: written into the cookie this response sets, read by the handler
    * of the request that follows, and gone after it.
    */
  def flash(name: String, value: String): Session = copy(pending = pending + (name -> value))
}

object Session {

  val empty: Session = Session(Map.empty, Map.empty, Map.empty)

  /** The prefix of the names eezo keeps for itself, the flash, the CSRF token and the guard's own
    * entries, which [[set]] refuses, [[withReserved]] requires and [[isEmpty]] looks past.
    *
    * `private[eezo]` for the reason [[reserved]] gives: `modules/auth` builds its own names from it
    * rather than writing `"_user"` out, so a module that reserves a name cannot spell the prefix
    * differently from the module that enforces it.
    */
  private[eezo] val Reserved: String = "_"
}

/** The session's cookie: the name, the wire format, and the one rule for when it is written.
  *
  * The value is `base64url(payload).base64url(HMAC-SHA256(base64url(payload)))`, unpadded, so it is
  * made of cookie octets with nothing to quote. The payload is the map form encoded, one line of
  * `URLEncoder` rather than a JSON reader eezo does not have. Signed, not encrypted: a user id and
  * a flash are not secrets from the browser that holds them, and four of the five surveyed
  * frameworks that keep the session in the cookie sign only.
  *
  * Verification compares the tags with `MessageDigest.isEqual` and treats every failure the same
  * way, as no session: a cookie eezo did not sign is a cookie eezo did not read.
  */
private[http] object SessionCookie {

  val Name: String = "eezo_session"

  /** The prefix under which a flash travels inside the payload, beside the `_csrf` entry the CSRF
    * token reserves. Built from [[Session.Reserved]], so it starts with the prefix `Session.set`
    * refuses.
    */
  val FlashPrefix: String = Session.Reserved + "flash."

  /** The largest encoded session `encode` will hand back, a conservative margin under the roughly
    * 4096 bytes every major browser keeps of one cookie. A `Set-Cookie` past that limit is not
    * refused by the browser with an error the server ever sees: it is dropped in silence, so the
    * next request arrives with no session or a stale one, and nothing anywhere says why. Refusing
    * here, before the header is built, turns that into a loud failure at the point the oversized
    * value was produced. The margin leaves room for `Name`, the `=`, and the fixed attribute tail
    * `Cookie.render` always writes, so it is well under 4096 rather than measured exactly against
    * it.
    */
  val MaxValue: Int = 3800

  private val encoder = Base64.getUrlEncoder.withoutPadding
  private val decoder = Base64.getUrlDecoder

  def encode(session: Session, secret: Secret): String = {
    val flash   = session.pending.map { case (name, value) => FlashPrefix + name -> value }
    val payload =
      encoder.encodeToString(form(session.entries ++ flash).getBytes(StandardCharsets.UTF_8))
    val tag    = encoder.encodeToString(secret.sign(payload.getBytes(StandardCharsets.US_ASCII)))
    val cookie = s"$payload.$tag"
    require(
      cookie.length <= MaxValue,
      s"the session encodes to ${cookie.length} bytes, past the $MaxValue a browser will keep; " +
        "store a key here and the data elsewhere"
    )
    cookie
  }

  def decode(value: String, secret: Secret): Session =
    try {
      val dot = value.lastIndexOf('.')
      if (dot <= 0 || dot == value.length - 1) Session.empty
      else {
        val payload  = value.substring(0, dot)
        val received = decoder.decode(value.substring(dot + 1))
        val expected = secret.sign(payload.getBytes(StandardCharsets.US_ASCII))
        if (!MessageDigest.isEqual(expected, received)) Session.empty
        else arrived(unform(new String(decoder.decode(payload), StandardCharsets.UTF_8)))
      }
    } catch { case NonFatal(_) => Session.empty }

  /** The request with its session read: what the adapter does before dispatch. */
  def read(request: Request, secret: Secret): Request =
    request.copy(session = request.cookie(Name).map(decode(_, secret)).getOrElse(Session.empty))

  /** The response with the session cookie set, when it has to be: what the adapter does after
    * dispatch. The session that goes out is the one the response names, or else the one the request
    * carried into the handler, which dispatch has amended with a CSRF token when it had none.
    * Nothing is written when it matches what arrived; an emptied session is expired so the browser
    * stops sending it; anything else is signed. A cookie that did not verify read as empty, so on a
    * served page it is replaced by a fresh signed one carrying only a minted token, and on an error
    * page it is left alone like every cookie. `Secure` follows the scheme the browser used.
    */
  def write(request: Request, response: Response, secret: Secret): Response = {
    val incoming  = request.session
    val outgoing  = response.session.getOrElse(incoming)
    val present   = request.cookie(Name).isDefined
    val unchanged = outgoing.entries == incoming.entries && outgoing.pending == incoming.delivered
    val empty     = outgoing.entries.isEmpty && outgoing.pending.isEmpty

    if (unchanged && (!empty || !present)) response
    else if (empty) response.withCookie(Cookie.expired(Name))
    else response.withCookie(Cookie(Name, encode(outgoing, secret), secure = request.secure))
  }

  /** A session as read from a cookie: the reserved flash names are delivered, the rest are entries.
    */
  private def arrived(payload: Map[String, String]): Session = {
    val (flash, entries) = payload.partition { case (name, _) => name.startsWith(FlashPrefix) }
    Session(
      entries,
      flash.map { case (name, value) => name.drop(FlashPrefix.length) -> value },
      Map.empty
    )
  }

  private def form(payload: Map[String, String]): String =
    payload.toSeq.sorted
      .map { case (name, value) => s"${urlEncode(name)}=${urlEncode(value)}" }
      .mkString("&")

  /** The form decoder the request body uses; the payload's names are a map's keys, so one each. */
  private def unform(raw: String): Map[String, String] =
    Request.decodeForm(raw).map { case (name, values) => name -> values.head }

  private def urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
