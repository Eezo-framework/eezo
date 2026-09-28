package io.eezo.http.client

import java.time.Duration

import io.eezo.http.*

/** The taxonomy, held against a real server: every arm reached by an endpoint built to provoke it,
  * nothing touching the network beyond the loopback.
  */
class ClientSuite extends munit.FunSuite {

  private def route(method: Method, path: String)(handler: Handler): Route =
    Route.Http(method, PathPattern.parse(path), handler)

  private def text(status: Int, body: String): Response =
    Response(status, Seq("Content-Type" -> "text/plain; charset=utf-8"), Body.Bytes(body.getBytes))

  private def serving(body: String => Unit): Unit = {
    val routes = RouteTable(
      Seq(
        route(Method.GET, "/plain")(_ => text(200, "hello")),
        route(Method.GET, "/guarded") { request =>
          if (request.header("Authorization").contains("Bearer let-me-in")) text(200, "secret")
          else text(401, "who are you")
        },
        route(Method.GET, "/forbidden")(_ => text(403, "not for you")),
        route(Method.GET, "/broken")(_ => text(500, "oops")),
        route(Method.GET, "/slow") { _ =>
          Thread.sleep(2_000)
          text(200, "eventually")
        },
        route(Method.GET, "/token")(request => text(200, request.csrf.value)),
        route(Method.POST, "/echo") { request =>
          text(200, s"${request.header("Content-Type").getOrElse("?")}:${new String(request.body)}")
        }
      )
    )
    val server = HttpServer.start(port = 0, routes = routes)
    try {
      val port = server.port
      body(s"http://localhost:$port")
    } finally server.stop()
  }

  test("2xx is Ok, with the body and the headers readable") {
    serving { base =>
      Http.get(s"$base/plain") match {
        case Reply.Ok(r) =>
          assertEquals(r.text, "hello")
          assertEquals(r.header("content-type"), Some("text/plain; charset=utf-8"))
        case other => fail(s"expected Ok, got $other")
      }
    }
  }

  test("401 and 403 are Denied — the auth arm, with the response still in hand") {
    serving { base =>
      Http.get(s"$base/guarded") match {
        case Reply.Denied(r) => assertEquals(r.status, 401)
        case other           => fail(s"expected Denied, got $other")
      }
      Http.get(s"$base/forbidden") match {
        case Reply.Denied(r) => assertEquals(r.status, 403)
        case other           => fail(s"expected Denied, got $other")
      }
    }
  }

  test("the right bearer token turns Denied into Ok") {
    serving { base =>
      Http.get(s"$base/guarded", Auth.bearer("let-me-in")) match {
        case Reply.Ok(r) => assertEquals(r.text, "secret")
        case other       => fail(s"expected Ok, got $other")
      }
    }
  }

  test("basic credentials encode as the header the RFC says") {
    assertEquals(Auth.basic("user", "pass"), "Authorization" -> "Basic dXNlcjpwYXNz")
  }

  test("any other status is Failed, status in hand") {
    serving { base =>
      Http.get(s"$base/broken") match {
        case Reply.Failed(r) => assertEquals(r.status, 500)
        case other           => fail(s"expected Failed, got $other")
      }
      Http.get(s"$base/nowhere") match {
        case Reply.Failed(r) => assertEquals(r.status, 404)
        case other           => fail(s"expected Failed, got $other")
      }
    }
  }

  test("a server that answers too slowly is Unreachable under the caller's own deadline") {
    serving { base =>
      Http.withTimeout(Duration.ofMillis(300)).get(s"$base/slow") match {
        case Reply.Unreachable(reason) => assert(reason.toLowerCase.contains("timeout"), reason)
        case other                     => fail(s"expected Unreachable, got $other")
      }
    }
  }

  test("a port nobody listens on is Unreachable, not an exception") {
    Http.get("http://localhost:1") match {
      case Reply.Unreachable(_) => ()
      case other                => fail(s"expected Unreachable, got $other")
    }
  }

  test("post carries its body and content type") {
    serving { base =>
      // Dispatch refuses an unsafe request without the session's token, so the post returns one
      // the way a browser would: the cookie a first GET set, and the token in the form.
      val (cookie, token) = Http.get(s"$base/token") match {
        case Reply.Ok(r) => (r.header("set-cookie").get.takeWhile(_ != ';'), r.text)
        case other       => fail(s"expected Ok, got $other")
      }
      val form = s"${Csrf.Field}=$token&n=1"
      Http.post(
        s"$base/echo",
        form,
        "application/x-www-form-urlencoded",
        "Cookie" -> cookie
      ) match {
        case Reply.Ok(r) => assertEquals(r.text, s"application/x-www-form-urlencoded:$form")
        case other       => fail(s"expected Ok, got $other")
      }
    }
  }

  test("loopback is never proxied, whatever the environment's proxy rules say") {
    import java.net.{Proxy, ProxySelector, InetSocketAddress, SocketAddress, URI}

    // A hostile default: everything through a proxy that does not exist.
    val hostile = new ProxySelector {
      override def select(uri: URI): java.util.List[Proxy] =
        java.util.List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("10.255.255.1", 9999)))
      override def connectFailed(uri: URI, a: SocketAddress, c: java.io.IOException): Unit = ()
    }
    val selector = new Http.LoopbackSparing(() => Some(hostile))

    def chosen(url: String) = selector.select(URI.create(url))

    for (
      spared <- List(
        "http://localhost:8080/api",
        "http://sub.localhost/api",
        "http://127.0.0.1:8080/api",
        "http://127.1.2.3/api",
        "http://[::1]:8080/api"
      )
    )
      assertEquals(chosen(spared), java.util.List.of(Proxy.NO_PROXY), spared)

    // Everything else still follows the environment's rules.
    assertEquals(chosen("https://api.example.com/x"), hostile.select(URI.create("https://x")))
  }

  test("with no default selector at all, everything goes direct") {
    import java.net.{Proxy, URI}
    val selector = new Http.LoopbackSparing(() => None)
    assertEquals(
      selector.select(URI.create("https://api.example.com/x")),
      java.util.List.of(Proxy.NO_PROXY)
    )
  }
}
