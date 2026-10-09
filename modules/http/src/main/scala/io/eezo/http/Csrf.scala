package io.eezo.http

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

import io.eezo.core.html.{Attrs, Html}
import io.eezo.core.html.Tags.input

/** The CSRF token: what a form carries and an unsafe request must return, proving the submission
  * came from a page this application served to this browser and not from a stranger's.
  *
  * One per session, in a reserved entry beside the flash, so the token is as private as the cookie
  * that carries it and lives as long. Not a second cookie, because the session is already signed
  * and the double submit pattern exists for frameworks whose session is server side; not one per
  * form, because that breaks the back button and a second tab. Sent as is rather than masked:
  * masking defends against BREACH, which needs response compression, and eezo compresses nothing.
  * The day gzip arrives, masking is added here alone.
  *
  * Both halves happen at dispatch, after a route has matched and before its handler runs, and
  * [[protect]] is where: it mints a token into a session that has none, so every request past
  * dispatch has one and reading it cannot fail, and it refuses a `POST`, `PUT`, `PATCH` or `DELETE`
  * whose form does not return the session's token, derived and handwritten alike. The one place the
  * token does not apply is an API route, where no session takes part: a program holds no session to
  * keep a token in and serves no page to embed one, and the route's `Guarded` says who may call it
  * instead. A route is an API route only when its handler takes an `ApiRequest`, so a page that
  * reads the session cannot drop the check without its type saying so. The safe methods, `GET`,
  * `HEAD` and `OPTIONS`, are never checked; a WebSocket upgrade is a `GET`. Only the form field is
  * read: a header for JavaScript waits until an example fetches.
  */
object Csrf {

  /** A token, distinct from any other string so that a form cannot be handed the wrong one. */
  opaque type Token = String

  object Token {

    private val random  = new SecureRandom()
    private val encoder = Base64.getUrlEncoder.withoutPadding

    /** 32 bytes from `SecureRandom`, as base64url. */
    private[eezo] def gen(): Token = {
      val bytes = new Array[Byte](32)
      random.nextBytes(bytes)
      encoder.encodeToString(bytes)
    }

    extension (token: Token) {
      def value: String = token
    }
  }

  /** The form field the token travels under, beside `_method`. */
  val Field: String = "_csrf"

  /** The session entry the token is kept under, built from [[Session.Reserved]] so it starts with
    * the prefix `Session.set` refuses, which is what keeps an application from overwriting it.
    */
  private[http] val Entry: String = Session.Reserved + "csrf"

  /** The one hidden input, for `Form.render`, the derived show page's delete button and a
    * handwritten form alike.
    */
  def hidden(token: Token): Html =
    input(Attrs.tpe := "hidden", Attrs.name := Field, Attrs.value := token)

  /** The token a session holds, if dispatch has minted one into it.
    *
    * `private[eezo]`, like [[carrying]] below and the `Session` doors both stand on: a module that
    * drives routes without a server, which is what `auth`'s and `testkit`'s suites do, has to be
    * able to build the session a browser would have arrived with and to read back the one that went
    * out. Still closed to an application, which gets its token through `Request.csrf`.
    */
  private[eezo] def read(session: Session): Option[Token] = session.reserved(Entry)

  /** The session with `token` in its reserved entry. */
  private[eezo] def carrying(session: Session, token: Token): Session =
    session.withReserved(Entry, token)

  /** The session with a token of its own, minted now, replacing whatever it held.
    *
    * The one named operation for what happens to the token when a handler rebuilds the session,
    * which in practice means signing in. Published here, rather than left for each login handler to
    * remember, because it is a decision about the application and not about one page: a second
    * login route that forgot to call it would be a session fixation hole nobody would see in a
    * review of the line that was missing.
    *
    * The decision is to rotate. Signing in rebuilds the session precisely so that nothing an
    * attacker could have planted in the old one survives the privilege change, and the CSRF token
    * is part of the old one. Carrying it across, which is what [[protect]] does for an ordinary
    * rebuild, would leave the post-login session holding a value that was minted before eezo knew
    * who this was. The cost is real and small: a form opened in a second tab before signing in
    * carries the old token, and submitting it after signing in is refused once, with the message
    * that says to reload.
    *
    * [[protect]] leaves the result alone, because a session that already holds a token of its own
    * is exactly what its second arm is for.
    */
  def rotated(session: Session): Session = carrying(session, Token.gen())

  /** The handler with the token around it, which is how `RouteTable.dispatch` runs the route it
    * matched: [[ensure]] first, [[verify]] on what it produced, and the session the handler saw,
    * minted token included, going out on the response when the handler named none. That last step
    * is what lets the adapter write the mint once, since it compares against the session it read
    * from the cookie, not the one dispatch amended.
    *
    * A handler that names a session built from `Session.empty` is common at login, where session
    * fixation is defended against by starting over rather than amending what arrived. `isEmpty`
    * still means logout: an explicitly empty session carries the token nowhere and none is added.
    * But a rebuilt session that is not empty, and does not already hold a token of its own, gets
    * the ready request's token carried into it, because a page rendered on that same response
    * embeds that token in its form and the next submission has to find it in the cookie that
    * follows.
    *
    * Carrying forward is the right answer for an ordinary rebuild and the wrong one at a privilege
    * change, so a login handler says which it means by calling [[rotated]]. The result already
    * holds a token of its own and so takes the arm above, untouched. This is deliberately not
    * decided here: this method cannot tell a session rebuilt at login from one rebuilt for any
    * other reason, and guessing would either rotate every rebuild or none.
    */
  private[http] def protect(handler: Handler): Handler = request => {
    val ready = ensure(request)
    verify(ready)
    val response = handler(ready)
    response.session match {
      case None => response.withSession(ready.session)
      case Some(named) if named.isEmpty || read(named).isDefined => response
      case Some(named)                                           =>
        read(ready.session).fold(response)(t => response.withSession(carrying(named, t)))
    }
  }

  /** The request with a token in its session, minted now if it had none. The cost of the mint is
    * one `Set-Cookie` on a first anonymous `GET`, which Rails and Spring pay too.
    */
  private def ensure(request: Request): Request =
    if (read(request.session).isDefined) request
    else request.copy(session = carrying(request.session, Token.gen()))

  /** Refuses an unsafe request whose form does not return the session's token. Runs on a request
    * [[ensure]] has already seen, so the session's token is there to compare against; a browser
    * never seen before has no token to return, and its submission is refused rather than minted
    * through.
    *
    * @throws Forbidden
    *   on a missing or mismatched token, which the boundary maps to a 403.
    */
  private def verify(request: Request): Unit =
    if (!request.method.safe) {
      val returned = request.form.get(Field).flatMap(_.headOption)
      val matches  = read(request.session).zip(returned).exists { case (expected, submitted) =>
        MessageDigest.isEqual(bytes(expected), bytes(submitted))
      }
      if (!matches)
        throw Forbidden("the CSRF token is missing or stale; reload the page and try again")
    }

  private def bytes(text: String): Array[Byte] = text.getBytes(StandardCharsets.UTF_8)
}
