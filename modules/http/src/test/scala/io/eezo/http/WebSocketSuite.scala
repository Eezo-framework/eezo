package io.eezo.http

import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.websocket.api.Callback
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest
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
    val server = Eezo.start(port = 0, config = Config(routes, dev = dev, secret = secret))
    val client = new WebSocketClient()
    client.start()
    try {
      val port = server.getConnectors.head.asInstanceOf[ServerConnector].getLocalPort
      body(client, port)
    } finally {
      client.stop()
      server.stop()
    }
  }

  private def connect(client: WebSocketClient, port: Int, path: String): Session =
    client.connect(new ClientListener, URI.create(s"ws://localhost:$port$path")).get()

  /** The upgrade at `path` is answered with `status` rather than left hanging. */
  private def refused(client: WebSocketClient, port: Int, path: String, status: Int = 404): Unit = {
    val failure = intercept[java.util.concurrent.ExecutionException](connect(client, port, path))
    assert(clue(failure.getCause.toString).contains(status.toString))
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
      val cookie  = SessionCookie.encode(io.eezo.http.Session.empty.set("user", "42"), secret)
      val upgrade = new ClientUpgradeRequest(URI.create(s"ws://localhost:$port/live"))
      upgrade.setHeader("Cookie", s"eezo_session=$cookie")
      val session = client.connect(new ClientListener, upgrade).get()
      assertEquals(events.poll(5, TimeUnit.SECONDS), "user:42")
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
      val cookie = SessionCookie.encode(
        io.eezo.http.Session.empty.set("user", "42").flash("notice", "welcome"),
        secret
      )
      val upgrade = new ClientUpgradeRequest(URI.create(s"ws://localhost:$port/live"))
      upgrade.setHeader("Cookie", s"eezo_session=$cookie")
      val session = client.connect(new ClientListener, upgrade).get()
      assertEquals(events.poll(5, TimeUnit.SECONDS), "user:42 flash:none")
      session.close()
    }
  }

  test("an upgrade matching no WebSocket route is refused, not left hanging") {
    serving(RouteTable(Seq(Route.Ws(PathPattern.parse("/live"), _ => new WsListener {})))) {
      (client, port) => refused(client, port, "/nope")
    }
  }

  test("an endpoint that throws answers the upgrade through the boundary, as a handler would") {
    val refusing =
      Route.Ws(PathPattern.parse("/live"), _ => throw Unauthorized("nobody is signed in"))
    serving(RouteTable(Seq(refusing))) { (client, port) =>
      refused(client, port, "/live", status = 401)
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
