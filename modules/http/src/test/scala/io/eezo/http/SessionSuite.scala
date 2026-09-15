package io.eezo.http

import io.eezo.core.html.Tags.p

/** The session is a signed cookie: a small map of strings a handler reads off the request and hands
  * back on the response, tampering rejected, a missing or invalid cookie read as empty. Flash rides
  * inside it under reserved names: written before a redirect, delivered on the next request, gone
  * after it.
  */
class SessionSuite extends munit.FunSuite {

  private val secret = Secret.parse("correct horse battery staple, and a nail")

  // Secret

  test("a secret is parsed from text and generated at random, and never prints") {
    intercept[IllegalArgumentException](Secret.parse(""))
    assertEquals(Secret.parse("x" * 32), Secret.parse("x" * 32))
    assertNotEquals(Secret.throwaway(), Secret.throwaway())
    assertEquals(Secret.parse("x" * 32).toString, "Secret(redacted)")
  }

  test("a secret shorter than 32 bytes is refused, and the length is counted in UTF-8 bytes") {
    val short = intercept[IllegalArgumentException](Secret.parse("a" * 31))
    assert(short.getMessage.contains("EEZO_SECRET"), short.getMessage)
    assert(short.getMessage.contains("openssl rand -base64 32"), short.getMessage)
    Secret.parse("a" * 32)
    Secret.parse("é" * 16)
    intercept[IllegalArgumentException](Secret.parse("é" * 15 + "a"))
  }

  test("the default secret is the environment variable, or a throwaway when it is not set") {
    assertEquals(Secret.fromEnv(Map("EEZO_SECRET" -> "x" * 32)), Secret.parse("x" * 32))
    assertNotEquals(Secret.fromEnv(Map.empty), Secret.fromEnv(Map.empty))
  }

  // Session, as a value

  test("a session is read and written like a small map") {
    val session = Session.empty.set("user", "42").set("theme", "dark")
    assertEquals(session.get("user"), Some("42"))
    assertEquals(session.remove("user").get("user"), None)
    assertEquals(Session.empty.get("user"), None)
    assert(Session.empty.isEmpty)
    assert(!session.isEmpty)
  }

  test("names starting with an underscore are eezo's own and cannot be set by the application") {
    intercept[IllegalArgumentException](Session.empty.set("_csrf", "x"))
    intercept[IllegalArgumentException](Session.empty.set("_flash.notice", "x"))
  }

  test("a flash written now is not readable now, and does not count as an entry") {
    val session = Session.empty.flash("notice", "created")
    assertEquals(session.flash("notice"), None)
    assertEquals(session.get("notice"), None)
    assert(session.isEmpty)
  }

  // The cookie

  test("a session survives the trip through the cookie, entries and flash alike") {
    val out    = Session.empty.set("user", "42").set("q", "a=b&c d/é").flash("notice", "created")
    val cookie = SessionCookie.encode(out, secret)
    val in     = SessionCookie.decode(cookie, secret)
    assertEquals(in.get("user"), Some("42"))
    assertEquals(in.get("q"), Some("a=b&c d/é"))
    assertEquals(in.flash("notice"), Some("created"))
    assertEquals(in.get("notice"), None)
    assert(Cookie("eezo_session", cookie).render.nonEmpty, "the value is made of cookie octets")
  }

  private def roundTrip(session: Session): Session =
    SessionCookie.decode(SessionCookie.encode(session, secret), secret)

  test("a delivered flash is dropped from what goes out again, unless written again") {
    val in = roundTrip(Session.empty.flash("notice", "created"))
    assertEquals(roundTrip(in).flash("notice"), None)
    assertEquals(roundTrip(in.flash("notice", "again")).flash("notice"), Some("again"))
  }

  test("a tampered, forged, truncated or garbled cookie reads as the empty session") {
    val cookie              = SessionCookie.encode(Session.empty.set("user", "42"), secret)
    val Array(payload, tag) = cookie.split('.')
    val flipped             = if (payload.head == 'A') 'B' else 'A'
    val elsewhere           = Secret.parse("another secret, from somewhere else")
    assert(SessionCookie.decode(s"$flipped${payload.tail}.$tag", secret).isEmpty, "payload edited")
    assert(SessionCookie.decode(s"$payload.${tag.reverse}", secret).isEmpty, "tag edited")
    assert(SessionCookie.decode(cookie, elsewhere).isEmpty, "signed elsewhere")
    assert(SessionCookie.decode(payload, secret).isEmpty, "no tag")
    assert(SessionCookie.decode(s"$payload.", secret).isEmpty, "empty tag")
    assert(SessionCookie.decode("", secret).isEmpty, "empty")
    assert(SessionCookie.decode("not base64!.nope", secret).isEmpty, "garbage")
    assert(!SessionCookie.decode(cookie, secret).isEmpty, "the untouched cookie still reads")
  }

  // The write rule: once, after dispatch, only when something changed

  private def request(cookie: Option[String]): Request = {
    val headers = cookie.map(c => Map("Cookie" -> Seq(s"eezo_session=$c"))).getOrElse(Map.empty)
    Request(Method.GET, "/", Map.empty, headers, Array.emptyByteArray, Map.empty)
  }

  private def setCookie(response: Response): Option[String] = response.header("Set-Cookie")

  /** The session the response signed into its `Set-Cookie`. */
  private def written(response: Response): Session = {
    val header = setCookie(response).getOrElse(fail("no Set-Cookie"))
    SessionCookie.decode(header.takeWhile(_ != ';').stripPrefix("eezo_session="), secret)
  }

  test("a request with no cookie and a handler that set nothing writes nothing") {
    val req = SessionCookie.read(request(None), secret)
    assertEquals(
      setCookie(SessionCookie.write(req, Response.Ok(p("x")), secret)),
      None
    )
  }

  test("a handler that set the session writes it, signed, with the default attributes") {
    val req      = SessionCookie.read(request(None), secret)
    val response = SessionCookie.write(
      req,
      Response.Ok(p("x")).withSession(req.session.set("user", "42")),
      secret
    )
    assertEquals(written(response).get("user"), Some("42"))
    assertEquals(
      setCookie(response).map(_.dropWhile(_ != ';')),
      Some("; Path=/; HttpOnly; SameSite=Lax")
    )
  }

  test("over HTTPS the session cookie is Secure") {
    val req      = SessionCookie.read(request(None), secret).copy(secure = true)
    val response = SessionCookie.write(
      req,
      Response.Ok(p("x")).withSession(req.session.set("user", "42")),
      secret
    )
    assert(setCookie(response).exists(_.contains("; Secure; ")), setCookie(response).toString)
  }

  test("a session that came in unchanged is not written again") {
    val cookie = SessionCookie.encode(Session.empty.set("user", "42"), secret)
    val req    = SessionCookie.read(request(Some(cookie)), secret)
    assertEquals(req.session.get("user"), Some("42"))
    assertEquals(
      setCookie(SessionCookie.write(req, Response.Ok(p("x")), secret)),
      None
    )
    val same =
      SessionCookie.write(req, Response.Ok(p("x")).withSession(req.session), secret)
    assertEquals(setCookie(same), None)
  }

  test("a session emptied by the handler expires the cookie rather than signing an empty one") {
    val cookie   = SessionCookie.encode(Session.empty.set("user", "42"), secret)
    val req      = SessionCookie.read(request(Some(cookie)), secret)
    val response = SessionCookie.write(
      req,
      Response.Redirect("/").withSession(Session.empty),
      secret
    )
    assertEquals(
      setCookie(response),
      Some("eezo_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax")
    )
  }

  test("a cookie that did not verify is expired, so the browser stops sending it") {
    val req = SessionCookie.read(request(Some("forged.cookie")), secret)
    assert(req.session.isEmpty)
    val response = SessionCookie.write(req, Response.Ok(p("x")), secret)
    assertEquals(
      setCookie(response),
      Some("eezo_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax")
    )
  }

  test("a delivered flash is written away even when the handler set nothing") {
    val cookie =
      SessionCookie.encode(Session.empty.set("user", "42").flash("notice", "created"), secret)
    val req = SessionCookie.read(request(Some(cookie)), secret)
    assertEquals(req.session.flash("notice"), Some("created"))
    val next = written(SessionCookie.write(req, Response.Ok(p("x")), secret))
    assertEquals(next.flash("notice"), None)
    assertEquals(next.get("user"), Some("42"))
  }

  test("a response built without a session leaves the request's session as the handler saw it") {
    assertEquals(Response.Ok(p("x")).session, None)
    assertEquals(Response.Ok(p("x")).withSession(Session.empty).session, Some(Session.empty))
  }
}
