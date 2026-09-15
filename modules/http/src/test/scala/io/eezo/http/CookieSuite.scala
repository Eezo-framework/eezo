package io.eezo.http

import io.eezo.core.html.Tags.p

/** A cookie is a value that renders its own `Set-Cookie` string, and a request reads the `Cookie`
  * header into pairs. Jetty's `HttpCookie` never appears: the adapter's `write` stays a header
  * copy, and no response ever carries the `Expires: 1970` stamp `Response.addCookie` adds.
  */
class CookieSuite extends munit.FunSuite {

  test("the defaults are the ones every surveyed framework agrees on") {
    assertEquals(
      Cookie("eezo_session", "abc").render,
      "eezo_session=abc; Path=/; HttpOnly; SameSite=Lax"
    )
  }

  test("every attribute renders in Jetty's order, only when set") {
    val cookie = Cookie(
      "a",
      "b",
      path = "/admin",
      maxAge = Some(60),
      secure = true,
      httpOnly = false,
      sameSite = Cookie.SameSite.Strict
    )
    assertEquals(cookie.render, "a=b; Path=/admin; Max-Age=60; Secure; SameSite=Strict")
  }

  test("an expired cookie is the one that tells a browser to drop the name") {
    assertEquals(
      Cookie.expired("eezo_session").render,
      "eezo_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"
    )
  }

  test("a value outside the cookie-octet rule is refused at construction, not on the wire") {
    intercept[IllegalArgumentException](Cookie("a", "b;c"))
    intercept[IllegalArgumentException](Cookie("a", "b c"))
    intercept[IllegalArgumentException](Cookie("a", "\"b\""))
    intercept[IllegalArgumentException](Cookie("a", "b,c"))
    intercept[IllegalArgumentException](Cookie("a", "b\\c"))
    // base64url with the `.` separator is entirely inside the rule
    Cookie("a", "aGVsbG8-_.x9=")
  }

  test("a name that is not a token is refused") {
    intercept[IllegalArgumentException](Cookie("", "b"))
    intercept[IllegalArgumentException](Cookie("a b", "b"))
    intercept[IllegalArgumentException](Cookie("a=b", "b"))
  }

  test("a response takes a cookie as one more Set-Cookie header") {
    val response = Response.Ok(p("hi")).withCookie(Cookie("a", "1")).withCookie(Cookie("b", "2"))
    assertEquals(
      response.headers.drop(1),
      Seq(
        "Set-Cookie" -> "a=1; Path=/; HttpOnly; SameSite=Lax",
        "Set-Cookie" -> "b=2; Path=/; HttpOnly; SameSite=Lax"
      )
    )
  }

  private def request(headers: Map[String, Seq[String]]): Request =
    Request(Method.GET, "/", Map.empty, headers, Array.emptyByteArray, Map.empty)

  test("a request splits its Cookie header into pairs") {
    val req = request(Map("Cookie" -> Seq("a=1; eezo_session=abc.def; b=")))
    assertEquals(req.cookies, Map("a" -> "1", "eezo_session" -> "abc.def", "b" -> ""))
    assertEquals(req.cookie("eezo_session"), Some("abc.def"))
    assertEquals(req.cookie("missing"), None)
  }

  test(
    "a quoted value loses its quotes, the first of a repeated name wins, and a second header counts"
  ) {
    val req = request(Map("cookie" -> Seq("a=\"1\"; a=2", "c=3")))
    assertEquals(req.cookies, Map("a" -> "1", "c" -> "3"))
  }

  test("no Cookie header is no cookies") {
    assertEquals(request(Map.empty).cookies, Map.empty[String, String])
  }
}
