package io.eezo.http

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import org.eclipse.jetty.client.Request as HandshakeRequest
import org.eclipse.jetty.client.Response as HandshakeResponse
import org.eclipse.jetty.websocket.api.Callback
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest
import org.eclipse.jetty.websocket.client.JettyUpgradeListener
import org.eclipse.jetty.websocket.client.WebSocketClient

/** The WebSocket half of the single Jetty mapping: eezo matches the path itself, and an upgrade
  * matching no `Route.Ws` is answered rather than falling through.
  */
class WebSocketSuite extends munit.FunSuite {

  /** A client that demands its own events, since this side is not eezo's runtime.
    *
    * `Session.Listener.AbstractAutoDemanding`, the class, rather than the `AutoDemanding`
    * interface: Scala emits a mixin forwarder for every default method of an interface, and Jetty
    * binds its events by reflecting over the methods a listener actually declares, so implementing
    * the interface directly makes it see two handlers for one event.
    */
  private class ClientListener extends Session.Listener.AbstractAutoDemanding {
    override def onWebSocketOpen(session: Session): Unit = ()
  }

  private val secret = Secret.parse("websocket secret, thirty two bytes")

  private def serving(routes: RouteTable, dev: Boolean = false)(
      body: (WebSocketClient, Int) => Unit
  ): Unit = {
    val server =
      HttpServer.start(port = 0, routes = routes, config = HttpConfig(dev = dev, secret = secret))
    val client = new WebSocketClient()
    client.start()
    try {
      val port = server.port
      body(client, port)
    } finally {
      client.stop()
      server.stop()
    }
  }

  private def connect(client: WebSocketClient, port: Int, path: String): Session =
    client.connect(new ClientListener, URI.create(s"ws://localhost:$port$path")).get()

  /** [[connect]], with `session` on the handshake the way a browser would carry it. */
  private def connect(
      client: WebSocketClient,
      port: Int,
      path: String,
      session: io.eezo.http.Session
  ): Session = {
    val upgrade = new ClientUpgradeRequest(URI.create(s"ws://localhost:$port$path"))
    upgrade.setHeader("Cookie", s"eezo_session=${SessionCookie.encode(session, secret)}")
    client.connect(new ClientListener, upgrade).get()
  }

  /** The upgrade at `path` is answered with a 404 rather than left hanging. */
  private def refused(client: WebSocketClient, port: Int, path: String): Unit = {
    val failure = intercept[java.util.concurrent.ExecutionException](connect(client, port, path))
    assert(clue(failure.getCause.toString).contains("404"))
  }

  /** The status and the `WWW-Authenticate` the refused upgrade at `path` was answered with.
    *
    * Read off the handshake itself through an upgrade listener, which Jetty calls with the response
    * whatever its status, because the `UpgradeException` the client throws carries the status code
    * but none of the headers.
    */
  private def refusal(client: WebSocketClient, port: Int, path: String): (Int, Option[String]) = {
    val answered = new AtomicReference[(Int, Option[String])](null)
    val reading  = new JettyUpgradeListener {
      override def onHandshakeResponse(
          request: HandshakeRequest,
          response: HandshakeResponse
      ): Unit =
        answered.set((response.getStatus, Option(response.getHeaders.get("WWW-Authenticate"))))
    }
    val failure = intercept[java.util.concurrent.ExecutionException](
      client.connect(new ClientListener, URI.create(s"ws://localhost:$port$path"), reading).get()
    )
    Option(answered.get)
      .getOrElse(fail(s"the upgrade at $path never reached the listener, failing with $failure"))
  }

  /** The body the refused upgrade at `path` was answered with.
    *
    * Written over a bare socket because Jetty's client keeps a refused handshake's body to itself,
    * and the body is what a person sees when they open a socket URL in a browser by hand.
    */
  private def refusalBody(port: Int, path: String): String = {
    val socket = new java.net.Socket("localhost", port)
    try {
      socket.setSoTimeout(5000)
      val out = socket.getOutputStream
      out.write(
        (s"GET $path HTTP/1.1\r\nHost: localhost:$port\r\nUpgrade: websocket\r\n" +
          "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
          "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII)
      )
      out.flush()
      val in      = new java.io.DataInputStream(socket.getInputStream)
      val headers = Iterator
        .continually {
          val line = new StringBuilder
          var c    = in.read()
          while (c != '\n') {
            if (c == -1) fail(s"the refusal at $path ended inside its headers: $line")
            if (c != '\r') line.append(c.toChar)
            c = in.read()
          }
          line.toString
        }
        .takeWhile(_.nonEmpty)
        .toVector
      val length = headers
        .collectFirst {
          case h if h.toLowerCase.startsWith("content-length:") => h.drop(15).trim.toInt
        }
        .getOrElse(fail(s"the refused upgrade at $path carried no length: $headers"))
      val bytes = new Array[Byte](length)
      in.readFully(bytes)
      new String(bytes, StandardCharsets.UTF_8)
    } finally socket.close()
  }

  test("a WebSocket route receives the open event and every message, with its path parameters") {
    val events = new LinkedBlockingQueue[String]()
    val routes = RouteTable(
      Seq(
        Route.Ws(
          PathPattern.parse("/live/:room"),
          request =>
            new WsListener {
              override def onOpen(conn: WsConn): Unit = {
                val _ = events.offer(s"open:${request.param[String]("room")}")
              }
              override def onText(conn: WsConn, text: String): Unit = {
                val _ = events.offer(s"text:$text")
              }
            }
        )
      )
    )

    serving(routes) { (client, port) =>
      val session = connect(client, port, "/live/lobby")
      assertEquals(events.poll(5, TimeUnit.SECONDS), "open:lobby")
      session.sendText("one", Callback.NOOP)
      assertEquals(events.poll(5, TimeUnit.SECONDS), "text:one")
      // The runtime demands the next event on the application's behalf, so a second message
      // arrives without the listener having asked for it.
      session.sendText("two", Callback.NOOP)
      assertEquals(events.poll(5, TimeUnit.SECONDS), "text:two")
      session.close()
    }
  }

  test("a WebSocket route reads the session the handshake's cookie carried") {
    val events = new LinkedBlockingQueue[String]()
    val routes = RouteTable(
      Seq(
        Route.Ws(
          PathPattern.parse("/live"),
          request =>
            new WsListener {
              override def onOpen(conn: WsConn): Unit = {
                val _ = events.offer(s"user:${request.session.get("user").getOrElse("nobody")}")
              }
            }
        )
      )
    )

    serving(routes) { (client, port) =>
      val session = connect(client, port, "/live", io.eezo.http.Session.empty.set("user", "42"))
      assertEquals(events.poll(5, TimeUnit.SECONDS), "user:42")
      session.close()
    }
  }

  test("an upgrade is named by the table, after the handshake's session has been read") {
    // Order is the whole of it: the naming reads the session, so running it before the
    // handshake's session has been read would make every upgrade anonymous, and silently, since
    // nothing about a `None` says whether it was asked too early or answered honestly.
    val events = new LinkedBlockingQueue[String]()
    val routes = RouteTable(
      Seq(
        Route.Ws(
          PathPattern.parse("/live"),
          request =>
            new WsListener {
              override def onOpen(conn: WsConn): Unit = {
                val _ = events.offer(s"user:${request.currentUser.getOrElse("nobody")}")
              }
            }
        )
      ),
      request => request.copy(currentUser = request.session.get("user"))
    )

    serving(routes) { (client, port) =>
      val session = connect(client, port, "/live", io.eezo.http.Session.empty.set("user", "42"))
      assertEquals(events.poll(5, TimeUnit.SECONDS), "user:42")
      session.close()
    }

    serving(routes) { (client, port) =>
      val session = connect(client, port, "/live")
      assertEquals(events.poll(5, TimeUnit.SECONDS), "user:nobody")
      session.close()
    }
  }

  test("a WebSocket route reads entries the handshake's cookie carried, but not its flash") {
    val events = new LinkedBlockingQueue[String]()
    val routes = RouteTable(
      Seq(
        Route.Ws(
          PathPattern.parse("/live"),
          request =>
            new WsListener {
              override def onOpen(conn: WsConn): Unit = {
                val user  = request.session.get("user").getOrElse("nobody")
                val flash = request.session.flash("notice").getOrElse("none")
                val _     = events.offer(s"user:$user flash:$flash")
              }
            }
        )
      )
    )

    serving(routes) { (client, port) =>
      val session = connect(
        client,
        port,
        "/live",
        io.eezo.http.Session.empty.set("user", "42").flash("notice", "welcome")
      )
      assertEquals(events.poll(5, TimeUnit.SECONDS), "user:42 flash:none")
      session.close()
    }
  }

  test("an upgrade matching no WebSocket route is refused, not left hanging") {
    serving(RouteTable(Seq(Route.Ws(PathPattern.parse("/live"), _ => new WsListener {})))) {
      (client, port) => refused(client, port, "/nope")
    }
  }

  test("a refused upgrade is answered with eezo's own error page, never the application's frame") {
    // A refusal is answered outside the HTTP handler and is not a page an application serves, so
    // it keeps the whole document it always had rather than coming back as bare content.
    serving(RouteTable(Seq(Route.Ws(PathPattern.parse("/live"), _ => new WsListener {})))) {
      (_, port) =>
        assert(
          clue(refusalBody(port, "/nope")).startsWith(
            """<!DOCTYPE html><html><head><meta charset="utf-8"><title>404 Not Found</title>""" +
              "</head><body><h1>404 Not Found</h1>"
          )
        )
    }
  }

  test("a throw answers the upgrade through the boundary, Forbidden as a 403 with no challenge") {
    // The status a guarded socket handshake really earns, and the reason it is this one: a client
    // has to be able to read the refusal, and Jetty's own client hides a 401 that carries no
    // challenge to offer as a protocol violation. Absent `WWW-Authenticate` is the other half of
    // that: a 403 asks for no credentials, so there is nothing for a client to retry with, and a
    // header here would be a promise this handshake cannot keep.
    //
    // An expired sign in arrives at this module as the same thrown `Forbidden`, since nothing in
    // `modules/http` knows a guard exists, so no test here can tell the two apart. That the guard
    // refuses an expired socket rather than redirecting it is pinned in `GuardSuite`.
    val refusing =
      Route.Ws(PathPattern.parse("/live"), _ => throw Forbidden("nobody is signed in"))
    serving(RouteTable(Seq(refusing))) { (client, port) =>
      val (status, challenge) = refusal(client, port, "/live")
      assertEquals(status, 403)
      assertEquals(challenge, None)
    }
  }

  test("the dev server accepts an upgrade on the reload path, and production refuses it") {
    val none = RouteTable(Seq.empty)
    serving(none, dev = true) { (client, port) =>
      val session = connect(client, port, Reload.path)
      assert(session.isOpen)
      session.close()
    }
    serving(none) { (client, port) => refused(client, port, Reload.path) }
  }

  test("a user route on the reload path never shadows it on the dev server") {
    val events = new LinkedBlockingQueue[String]()
    val routes = RouteTable(
      Seq(
        Route.Ws(
          PathPattern.parse(Reload.path),
          _ =>
            new WsListener {
              override def onOpen(conn: WsConn): Unit = { val _ = events.offer("user") }
            }
        )
      )
    )
    serving(routes, dev = true) { (client, port) =>
      val session = connect(client, port, Reload.path)
      assert(session.isOpen)
      assertEquals(events.poll(500, TimeUnit.MILLISECONDS), null)
      session.close()
    }
  }
}
