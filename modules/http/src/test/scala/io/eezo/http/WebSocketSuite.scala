package io.eezo.http

import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.websocket.api.Callback
import org.eclipse.jetty.websocket.api.Session
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

  private def serving(routes: RouteTable, dev: Boolean = false)(
      body: (WebSocketClient, Int) => Unit
  ): Unit = {
    val server = Eezo.start(port = 0, config = Config(routes, dev = dev))
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
      val session =
        client.connect(new ClientListener, URI.create(s"ws://localhost:$port/live/lobby")).get()
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

  test("an upgrade matching no WebSocket route is refused, not left hanging") {
    serving(RouteTable(Seq(Route.Ws(PathPattern.parse("/live"), _ => new WsListener {})))) {
      (client, port) =>
        val failure = intercept[java.util.concurrent.ExecutionException] {
          client.connect(new ClientListener, URI.create(s"ws://localhost:$port/nope")).get()
        }
        assert(clue(failure.getCause.toString).contains("404"))
    }
  }

  test("the dev server accepts an upgrade on the reload path, and production refuses it") {
    val none = RouteTable(Seq.empty)
    serving(none, dev = true) { (client, port) =>
      val session =
        client.connect(new ClientListener, URI.create(s"ws://localhost:$port/eezo/reload")).get()
      assert(session.isOpen)
      session.close()
    }
    serving(none) { (client, port) =>
      val failure = intercept[java.util.concurrent.ExecutionException] {
        client.connect(new ClientListener, URI.create(s"ws://localhost:$port/eezo/reload")).get()
      }
      assert(clue(failure.getCause.toString).contains("404"))
    }
  }

  test("a user route on the reload path never shadows it on the dev server") {
    val events = new LinkedBlockingQueue[String]()
    val routes = RouteTable(
      Seq(
        Route.Ws(
          PathPattern.parse("/eezo/reload"),
          _ =>
            new WsListener {
              override def onOpen(conn: WsConn): Unit = { val _ = events.offer("user") }
            }
        )
      )
    )
    serving(routes, dev = true) { (client, port) =>
      val session =
        client.connect(new ClientListener, URI.create(s"ws://localhost:$port/eezo/reload")).get()
      assert(session.isOpen)
      assertEquals(events.poll(500, TimeUnit.MILLISECONDS), null)
      session.close()
    }
  }
}
