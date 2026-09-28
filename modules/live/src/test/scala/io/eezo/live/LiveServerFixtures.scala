package io.eezo.live

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import io.eezo.core.html.Html
import io.eezo.core.html.Tags.*
import io.eezo.http.*
import org.eclipse.jetty.websocket.api.{Callback, Session}
import org.eclipse.jetty.websocket.client.{ClientUpgradeRequest, WebSocketClient}

/** What a suite needs to drive a live page the way a browser does: a booted server, a GET that
  * mounts a page and reads its id off the marker, and a WebSocket with blocking expectations on the
  * frames and the close that come back.
  *
  * Mixed into a `FunSuite`, the way `http`'s `ServerFixtures` is, because a rig that did not get
  * what a browser gets fails the test, and failing is the suite's own business. The headers a
  * request or an upgrade carries are the one thing the suites differ on: an `Origin`, a cookie, or
  * a fake declaration's header.
  */
trait LiveServerFixtures { self: munit.FunSuite =>

  /** The plainest component there is, for suites where the component is not the point. */
  protected final class Counter extends Component[Int] {
    def init(ctx: Init[Int]): Int         = 0
    def handle(event: Event, n: Int): Int = n + 1
    def render(n: Int): Html              = div(span(n), button(Live.onClick("inc"), "+"))
  }

  /** A handwritten page route whose handler mounts per request, which is what a real handler does:
    * `mounted` is handed the request, so every GET is a fresh page bound to whoever that request
    * named.
    */
  protected def pageRoute(mounted: Request => Html, at: String = "/counter"): Route =
    Route.Http(
      Method.GET,
      PathPattern.parse(at),
      request =>
        Response.Ok(
          Html.doctype ++ html(head(title("t")), io.eezo.core.html.Tags.body(mounted(request)))
        )
    )

  /** The page id off a mounted response's marker. */
  private def pageIdIn(html: String): String =
    "data-eezo-page=\"([0-9a-f]{32})\"".r
      .findFirstMatchIn(html)
      .map(_.group(1))
      .getOrElse(fail(s"no page marker in: $html"))

  /** Boots a server on `table` for one test, with one HTTP client and one WS client, and stops both
    * afterwards. The live framework routes are the caller's to add.
    */
  protected def serving(table: RouteTable)(body: Rig => Unit): Unit = {
    val server = HttpServer.start(port = 0, routes = table)
    val ws     = new WebSocketClient()
    ws.start()
    try body(new Rig(server.port, ws))
    finally {
      ws.stop()
      server.stop()
    }
  }

  protected final class Rig(val port: Int, ws: WebSocketClient) {

    private val http = HttpClient.newHttpClient()

    def get(path: String, headers: Seq[(String, String)] = Nil): HttpResponse[String] = {
      val builder = HttpRequest.newBuilder(URI.create(s"http://localhost:$port$path"))
      headers.foreach((name, value) => builder.header(name, value))
      http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** Mounts a fresh page over HTTP and returns its id, read off the marker. */
    def mountedPageId(at: String = "/counter", headers: Seq[(String, String)] = Nil): String =
      pageIdIn(get(at, headers).body())

    def connect(pageId: String, headers: Seq[(String, String)] = Nil): Wire = {
      val listener = new Listener
      val upgrade  = new ClientUpgradeRequest(URI.create(s"ws://localhost:$port/eezo/live/$pageId"))
      headers.foreach((name, value) => upgrade.setHeader(name, value))
      new Wire(ws.connect(listener, upgrade).get(), listener)
    }

    /** A socket that joins `pageId`, is answered with the full resync every join is owed, and hangs
      * up: the whole visit a suite drives when admission is the only thing under test.
      */
    def joins(pageId: String, headers: Seq[(String, String)] = Nil): Unit = {
      val wire = connect(pageId, headers)
      wire.join()
      val resync = wire.frame()
      assert(resync.contains("\"setChildren\""), resync)
      wire.close()
    }
  }

  protected final class Listener extends Session.Listener.AbstractAutoDemanding {
    val frames = new LinkedBlockingQueue[String]()
    val closes = new LinkedBlockingQueue[(Int, String)]()

    override def onWebSocketText(text: String): Unit               = { val _ = frames.offer(text) }
    override def onWebSocketClose(code: Int, reason: String): Unit = {
      val _ = closes.offer((code, reason))
    }
  }

  /** One live connection, with blocking expectations. */
  protected final class Wire(session: Session, listener: Listener) {

    def send(text: String): Unit = session.sendText(text, Callback.NOOP)

    def join(base: String = "/"): Unit = send(s"""{"kind":"join","base":"$base"}""")

    def event(name: String): Unit = send(s"""{"kind":"event","name":"$name","payload":{}}""")

    def frame(): String = {
      val received = listener.frames.poll(5, TimeUnit.SECONDS)
      assert(received != null, "no frame within 5s")
      received
    }

    /** A frame if one arrives within the window, null otherwise: for drain-until-quiet loops. */
    def poll(millis: Long): String | Null = listener.frames.poll(millis, TimeUnit.MILLISECONDS)

    def closeCode(): Int = closed()._1

    /** The close code and its reason, which is what tells apart two refusals sharing a code. */
    def closed(): (Int, String) =
      Option(listener.closes.poll(5, TimeUnit.SECONDS)).getOrElse(fail("no close within 5s"))

    def close(code: Int = 1000): Unit = session.close(code, "bye", Callback.NOOP)

    /** A hard transport close, no close frame: the server sees an abnormal drop (1006), which is
      * what starts the grace window. `session.close(1006, …)` cannot stand in, because 1006 is
      * reserved and may not be sent over the wire.
      */
    def drop(): Unit = session.disconnect()
  }
}
