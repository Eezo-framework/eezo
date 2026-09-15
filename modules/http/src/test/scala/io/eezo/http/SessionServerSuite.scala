package io.eezo.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

import io.eezo.core.html.Tags.*
import org.eclipse.jetty.server.ServerConnector

/** The session over the wire: read out of the `Cookie` header before dispatch, written once as a
  * `Set-Cookie` after it, with the secret the server was booted with.
  */
class SessionServerSuite extends munit.FunSuite {

  private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

  private val secret = Secret.parse("server secret")

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
      cookie: Option[String]
  ): HttpResponse[String] = {
    val builder = HttpRequest.newBuilder(URI.create(s"http://localhost:$port$path"))
    cookie.foreach(value => builder.header("Cookie", s"eezo_session=$value"))
    val request = method match {
      case "GET" => builder.GET()
      case other => builder.method(other, HttpRequest.BodyPublishers.noBody())
    }
    client.send(request.build(), HttpResponse.BodyHandlers.ofString())
  }

  private def sessionCookie(response: HttpResponse[String]): Option[String] =
    response
      .headers()
      .allValues("Set-Cookie")
      .stream()
      .filter(_.startsWith("eezo_session="))
      .findFirst()
      .map[String](_.takeWhile(_ != ';').stripPrefix("eezo_session="))
      .map[Option[String]](Some(_))
      .orElse(None)

  test(
    "a session set on one response is read on the next request, and the flash is delivered once"
  ) {
    serving { port =>
      val anonymous = send(port, "GET", "/me", None)
      assertEquals(anonymous.body(), "<p>user=nobody notice=-</p>")
      assertEquals(sessionCookie(anonymous), None, "a page that only reads writes no cookie")

      val login  = send(port, "POST", "/login", None)
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

  test("a tampered cookie reads as no session and is expired") {
    serving { port =>
      val cookie   = sessionCookie(send(port, "POST", "/login", None)).get
      val tampered = cookie.replaceFirst("\\.", "x.")
      val response = send(port, "GET", "/me", Some(tampered))
      assertEquals(response.body(), "<p>user=nobody notice=-</p>")
      assertEquals(sessionCookie(response), Some(""))
      assert(response.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"))
    }
  }

  test("logging out expires the cookie") {
    serving { port =>
      val cookie   = sessionCookie(send(port, "POST", "/login", None)).get
      val response = send(port, "POST", "/logout", Some(cookie))
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
      override def secret: Secret     = Secret.parse("fixed")
    }
    assertEquals(fixed.secret, Secret.parse("fixed"))
  }
}
