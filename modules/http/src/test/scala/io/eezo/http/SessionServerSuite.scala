package io.eezo.http

import io.eezo.core.html.Tags.*

/** The session over the wire: read out of the `Cookie` header before dispatch, written once as a
  * `Set-Cookie` after it, with the secret the server was booted with. Every `POST` here goes the
  * way a browser's does: a first visit is handed the session cookie carrying the CSRF token, and
  * the form returns that token under the cookie.
  */
class SessionServerSuite extends munit.FunSuite with ServerFixtures {

  private val routes: RouteTable = RouteTable(
    Seq(
      Route.Http(
        Method.POST,
        PathPattern.parse("/login"),
        request =>
          Response
            .Redirect("/me")
            .withSession(request.session.set("user", "42").flash("notice", "welcome"))
      ),
      Route.Http(
        Method.GET,
        PathPattern.parse("/me"),
        request =>
          Response.Ok(
            p(
              s"user=${request.session.get("user").getOrElse("nobody")} notice=${request.session.flash("notice").getOrElse("-")}"
            )
          )
      ),
      Route.Http(
        Method.POST,
        PathPattern.parse("/logout"),
        _ => Response.Redirect("/me").withSession(Session.empty)
      )
    )
  )

  test(
    "a session set on one response is read on the next request, and the flash is delivered once"
  ) {
    serving(routes) { (_, port) =>
      val anonymous = send(port, "GET", "/me", None)
      assertEquals(anonymous.body(), plainly("<p>user=nobody notice=-</p>"))
      val seen = visit(port, "/me")
      assertEquals(
        sessionCookie(send(port, "GET", "/me", Some(seen.cookie))),
        None,
        "a page that only reads writes no cookie, once the token has been minted"
      )

      val login  = submit(port, "/login", seen)
      val cookie = sessionCookie(login).getOrElse(fail("login set no session cookie"))
      assertEquals(login.statusCode(), 303)
      assertEquals(
        SessionCookie.decode(cookie, secret).get("user"),
        Some("42"),
        "signed with the server's secret"
      )
      val header = login.headers().firstValue("Set-Cookie").orElse("")
      assert(header.endsWith("; Path=/; HttpOnly; SameSite=Lax"), header)
      assert(!header.contains("Secure"), header)

      val first = send(port, "GET", "/me", Some(cookie))
      assertEquals(first.body(), plainly("<p>user=42 notice=welcome</p>"))
      val swept = sessionCookie(first).getOrElse(fail("the delivered flash was not written away"))

      val second = send(port, "GET", "/me", Some(swept))
      assertEquals(second.body(), plainly("<p>user=42 notice=-</p>"))
      assertEquals(sessionCookie(second), None)
    }
  }

  test("behind a proxy that terminated TLS, the session cookie is Secure") {
    serving(routes) { (_, port) =>
      val seen  = visit(port, "/me")
      val https = submit(port, "/login", seen, headers = Seq("X-Forwarded-Proto" -> "https"))
      assert(https.headers().firstValue("Set-Cookie").orElse("").contains("; Secure; "))
      // A chain of proxies lists the schemes in order, and the first is the one the browser used.
      val chain = submit(port, "/login", seen, headers = Seq("X-Forwarded-Proto" -> "HTTPS, http"))
      assert(chain.headers().firstValue("Set-Cookie").orElse("").contains("; Secure; "))
      val http = submit(port, "/login", seen, headers = Seq("X-Forwarded-Proto" -> "http"))
      assert(!http.headers().firstValue("Set-Cookie").orElse("").contains("Secure"))
    }
  }

  test("a tampered cookie reads as no session and is replaced by a fresh one") {
    serving(routes) { (_, port) =>
      val cookie   = sessionCookie(submit(port, "/login", visit(port, "/me"))).get
      val tampered = cookie.replaceFirst("\\.", "x.")
      val response = send(port, "GET", "/me", Some(tampered))
      assertEquals(response.body(), plainly("<p>user=nobody notice=-</p>"))
      // Not expired: the page was served, so dispatch minted a token into the empty session it
      // read, and that fresh session is what goes out, signed, in the tampered one's place.
      val fresh = sessionCookie(response).getOrElse(fail("the tampered cookie was left standing"))
      val read  = SessionCookie.decode(fresh, secret)
      assert(read.isEmpty, "nothing the tampered cookie claimed survived")
      assert(Csrf.read(read).isDefined, "a token was minted into its replacement")
    }
  }

  test("logging out expires the cookie") {
    serving(routes) { (_, port) =>
      val seen     = visit(port, "/me")
      val cookie   = sessionCookie(submit(port, "/login", seen)).get
      val response = submit(port, "/logout", seen.copy(cookie = cookie))
      assertEquals(response.statusCode(), 303)
      assertEquals(
        response.headers().firstValue("Set-Cookie").orElse(""),
        "eezo_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"
      )
    }
  }

  test("the entry trait has a secret override beside port, read from the environment by default") {
    val app = new HttpApp {
      override def routes: RouteTable = RouteTable.empty
    }
    assertEquals(app.secret.getClass, classOf[Secret])
    val fixed = new HttpApp {
      override def routes: RouteTable = RouteTable.empty
      override def secret: Secret     = Secret.parse("fixed secret, at least thirty two bytes")
    }
    assertEquals(fixed.secret, Secret.parse("fixed secret, at least thirty two bytes"))
  }

  // The CSRF token over the wire

  test("a first visit is handed the session cookie carrying the token, and only the first") {
    serving(routes) { (_, port) =>
      val browser = visit(port, "/me")
      assert(browser.token.value.nonEmpty)
      val again = send(port, "GET", "/me", Some(browser.cookie))
      assertEquals(sessionCookie(again), None, "the token is minted once")
      assertEquals(
        Csrf.read(SessionCookie.decode(browser.cookie, secret)),
        Some(browser.token),
        "the cookie carries the token and nothing the handler set"
      )
      assert(SessionCookie.decode(browser.cookie, secret).isEmpty, "the token is not an entry")
    }
  }

  test("a POST that does not return the token is a 403, and writes no cookie") {
    serving(routes) { (_, port) =>
      val cookie = visit(port, "/me").cookie
      val forged = send(port, "POST", "/login", Some(cookie))
      assertEquals(forged.statusCode(), 403)
      assert(forged.body().contains("missing or stale"), forged.body())
      assertEquals(sessionCookie(forged), None)
      // A browser never seen before has no token to return either.
      assertEquals(send(port, "POST", "/login", None).statusCode(), 403)
      // A token from another browser's session is not this one's.
      val other = visit(port, "/me")
      assertEquals(submit(port, "/login", other.copy(cookie = cookie)).statusCode(), 403)
    }
  }
}
