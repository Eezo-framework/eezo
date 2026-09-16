package io.eezo.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

import scala.jdk.CollectionConverters.*

import io.eezo.core.html.Tags.*
import org.eclipse.jetty.server.ServerConnector

/** The session over the wire: read out of the `Cookie` header before dispatch, written once as a
  * `Set-Cookie` after it, with the secret the server was booted with. Every `POST` here goes the
  * way a browser's does: a first visit is handed the session cookie carrying the CSRF token, and
  * the form returns that token under the cookie.
  */
class SessionServerSuite extends munit.FunSuite {

  private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

  private val secret = Secret.parse("server secret, at least thirty two bytes")

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

  private def serving(body: Int => Unit): Unit = {
    val server = Eezo.start(port = 0, config = Config(routes, secret = secret))
    try body(server.getConnectors.head.asInstanceOf[ServerConnector].getLocalPort)
    finally server.stop()
  }

  private def send(
      port: Int,
      method: String,
      path: String,
      cookie: Option[String],
      headers: Seq[(String, String)] = Nil,
      form: Option[String] = None
  ): HttpResponse[String] = {
    val builder = HttpRequest.newBuilder(URI.create(s"http://localhost:$port$path"))
    cookie.foreach(value => builder.header("Cookie", s"eezo_session=$value"))
    headers.foreach(builder.header(_, _))
    form.foreach(_ => builder.header("Content-Type", "application/x-www-form-urlencoded"))
    val request = method match {
      case "GET" => builder.GET()
      case other =>
        builder.method(other, HttpRequest.BodyPublishers.ofString(form.getOrElse("")))
    }
    client.send(request.build(), HttpResponse.BodyHandlers.ofString())
  }

  /** A browser's first visit: the cookie it was handed, and the token inside it. */
  private def visit(port: Int): (String, Csrf.Token) = {
    val cookie = sessionCookie(send(port, "GET", "/me", None))
      .getOrElse(fail("the first visit was handed no session cookie"))
    val token = Csrf
      .read(SessionCookie.decode(cookie, secret))
      .getOrElse(fail("the first visit's cookie carries no token"))
    (cookie, token)
  }

  /** A `POST` the way a form on a served page makes it: under the session cookie, returning the
    * token. `cookie` is the one to send when it is not the first visit's.
    */
  private def submit(
      port: Int,
      path: String,
      seen: (String, Csrf.Token),
      cookie: Option[String] = None,
      headers: Seq[(String, String)] = Nil
  ): HttpResponse[String] =
    send(
      port,
      "POST",
      path,
      cookie.orElse(Some(seen._1)),
      headers,
      form = Some(s"_csrf=${seen._2.value}")
    )

  private def sessionCookie(response: HttpResponse[String]): Option[String] =
    response.headers().allValues("Set-Cookie").asScala.collectFirst {
      case header if header.startsWith("eezo_session=") =>
        header.takeWhile(_ != ';').stripPrefix("eezo_session=")
    }

  test(
    "a session set on one response is read on the next request, and the flash is delivered once"
  ) {
    serving { port =>
      val anonymous = send(port, "GET", "/me", None)
      assertEquals(anonymous.body(), "<p>user=nobody notice=-</p>")
      val seen = visit(port)
      assertEquals(
        sessionCookie(send(port, "GET", "/me", Some(seen._1))),
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
      assertEquals(first.body(), "<p>user=42 notice=welcome</p>")
      val swept = sessionCookie(first).getOrElse(fail("the delivered flash was not written away"))

      val second = send(port, "GET", "/me", Some(swept))
      assertEquals(second.body(), "<p>user=42 notice=-</p>")
      assertEquals(sessionCookie(second), None)
    }
  }

  test("behind a proxy that terminated TLS, the session cookie is Secure") {
    serving { port =>
      val seen  = visit(port)
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
    serving { port =>
      val cookie   = sessionCookie(submit(port, "/login", visit(port))).get
      val tampered = cookie.replaceFirst("\\.", "x.")
      val response = send(port, "GET", "/me", Some(tampered))
      assertEquals(response.body(), "<p>user=nobody notice=-</p>")
      // Not expired: the page was served, so dispatch minted a token into the empty session it
      // read, and that fresh session is what goes out, signed, in the tampered one's place.
      val fresh = sessionCookie(response).getOrElse(fail("the tampered cookie was left standing"))
      val read  = SessionCookie.decode(fresh, secret)
      assert(read.isEmpty, "nothing the tampered cookie claimed survived")
      assert(Csrf.read(read).isDefined, "a token was minted into its replacement")
    }
  }

  test("logging out expires the cookie") {
    serving { port =>
      val seen     = visit(port)
      val cookie   = sessionCookie(submit(port, "/login", seen)).get
      val response = submit(port, "/logout", seen, cookie = Some(cookie))
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
    serving { port =>
      val (cookie, token) = visit(port)
      assert(token.value.nonEmpty)
      val again = send(port, "GET", "/me", Some(cookie))
      assertEquals(sessionCookie(again), None, "the token is minted once")
      assertEquals(
        Csrf.read(SessionCookie.decode(cookie, secret)),
        Some(token),
        "the cookie carries the token and nothing the handler set"
      )
      assert(SessionCookie.decode(cookie, secret).isEmpty, "the token is not an entry")
    }
  }

  test("a POST that does not return the token is a 403, and writes no cookie") {
    serving { port =>
      val (cookie, _) = visit(port)
      val forged      = send(port, "POST", "/login", Some(cookie))
      assertEquals(forged.statusCode(), 403)
      assert(forged.body().contains("missing or stale"), forged.body())
      assertEquals(sessionCookie(forged), None)
      // A browser never seen before has no token to return either.
      assertEquals(send(port, "POST", "/login", None).statusCode(), 403)
      // A token from another browser's session is not this one's.
      val other = visit(port)
      assertEquals(submit(port, "/login", other, cookie = Some(cookie)).statusCode(), 403)
    }
  }
}
