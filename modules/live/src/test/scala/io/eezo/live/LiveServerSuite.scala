package io.eezo.live

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import io.eezo.core.html.Html
import io.eezo.core.html.Tags.*
import io.eezo.http.*
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.websocket.api.{Callback, Session}
import org.eclipse.jetty.websocket.client.{ClientUpgradeRequest, WebSocketClient}

/** The whole M3 path, against a booted server: mount over HTTP, join over WebSocket, events in,
  * patches out, and every refusal with its close code. This is the suite that stands in for a
  * browser everywhere a browser is not the point; the manual counter test is the browser's turn.
  */
class LiveServerSuite extends munit.FunSuite {

  private val bumps = new Topic[Unit]

  private final class Counter extends Component[Int] {
    def init(ctx: Init[Int]): Int = {
      ctx.subscribe(bumps)((_, n) => n + 1)
      0
    }
    def handle(event: Event, n: Int): Int = event.name match {
      case "inc" => n + 1
    }
    def render(n: Int): Html =
      div(span(n), button(Live.onClick("inc"), "+"))
  }

  /** Everything a test drives: one server with a counter page and the live framework routes, one
    * HTTP client, one WS client.
    */
  private def serving(body: Rig => Unit): Unit = {
    val page = Route.Http(
      Method.GET,
      PathPattern.parse("/counter"),
      _ =>
        Response.Ok(
          Html.doctype ++ html(
            head(title("counter")),
            io.eezo.core.html.Tags.body(Live.mount(new Counter))
          )
        )
    )
    val server = Eezo.start(port = 0, config = Config(RouteTable(page +: Live.routes)))
    val ws     = new WebSocketClient()
    ws.start()
    try {
      val port = server.getConnectors.head.asInstanceOf[ServerConnector].getLocalPort
      body(new Rig(port, ws))
    } finally {
      ws.stop()
      server.stop()
    }
  }

  private final class Rig(val port: Int, ws: WebSocketClient) {

    private val http = HttpClient.newHttpClient()

    def get(path: String): HttpResponse[String] =
      http.send(
        HttpRequest.newBuilder(URI.create(s"http://localhost:$port$path")).build(),
        HttpResponse.BodyHandlers.ofString()
      )

    /** Mounts a fresh page over HTTP and returns its id, read off the marker. */
    def mountedPageId(): String = {
      val html = get("/counter").body()
      "data-eezo-page=\"([0-9a-f]{32})\"".r
        .findFirstMatchIn(html)
        .map(_.group(1))
        .getOrElse(fail(s"no page marker in: $html"))
    }

    // Jetty 12.1 deprecates this ClientUpgradeRequest path without a public replacement that sets
    // request headers; scoped here rather than build-wide, and only in test client code.
    @annotation.nowarn("cat=deprecation")
    def connect(pageId: String, origin: Option[String] = None): Wire = {
      val listener = new Listener
      val upgrade  = new ClientUpgradeRequest()
      origin.foreach(o => upgrade.setHeaders(java.util.Map.of("Origin", java.util.List.of(o))))
      val session =
        ws.connect(listener, URI.create(s"ws://localhost:$port/eezo/live/$pageId"), upgrade)
          .get()
      new Wire(session, listener)
    }
  }

  private final class Listener extends Session.Listener.AbstractAutoDemanding {
    val frames = new LinkedBlockingQueue[String]()
    val closes = new LinkedBlockingQueue[Int]()

    override def onWebSocketText(text: String): Unit               = { val _ = frames.offer(text) }
    override def onWebSocketClose(code: Int, reason: String): Unit = { val _ = closes.offer(code) }
  }

  /** One live connection, with blocking expectations. */
  private final class Wire(session: Session, listener: Listener) {

    def send(text: String): Unit = session.sendText(text, Callback.NOOP)

    def join(): Unit = send("""{"kind":"join","base":"/"}""")

    def event(name: String): Unit = send(s"""{"kind":"event","name":"$name","payload":{}}""")

    def frame(): String = {
      val received = listener.frames.poll(5, TimeUnit.SECONDS)
      assert(received != null, "no frame within 5s")
      received
    }

    def closeCode(): Int = {
      val code = listener.closes.poll(5, TimeUnit.SECONDS)
      Option(code).getOrElse(fail("no close within 5s")).intValue
    }

    def close(code: Int = 1000): Unit = session.close(code, "bye", Callback.NOOP)

    /** A hard transport close, no close frame: the server sees an abnormal drop (1006), which is
      * what starts the grace window. `session.close(1006, …)` cannot stand in — 1006 is reserved
      * and may not be sent over the wire.
      */
    def drop(): Unit = session.disconnect()
  }

  test("the mounted page carries the marker, the base, the first render and the script") {
    serving { rig =>
      val html = rig.get("/counter").body()
      assert(html.matches("(?s).*data-eezo-page=\"[0-9a-f]{32}\".*"), html)
      assert(html.contains("data-eezo-base=\"/\""), html)
      assert(html.contains("<span>0</span>"), html)
      assert(html.contains("""<script src="/eezo/live.js" defer>"""), html)
    }
  }

  test("the client script is served whole: applier plus client") {
    serving { rig =>
      val response = rig.get("/eezo/live.js")
      assertEquals(response.statusCode(), 200)
      assert(response.body().contains("applyPatches"))
      assert(response.body().contains("__eezoLiveClient"))
    }
  }

  test("join answers with a full resync, and an event with its fine-grained patch") {
    serving { rig =>
      val wire = rig.connect(rig.mountedPageId())
      wire.join()
      val resync = wire.frame()
      assert(resync.contains("\"setChildren\""), resync)
      assert(resync.contains("<span>0</span>"), resync)

      wire.event("inc")
      val patch = wire.frame()
      assert(patch.contains("\"setText\""), patch)
      assert(patch.contains("\"1\""), patch)
      wire.close()
    }
  }

  test("the join resync carries what moved between mount and join") {
    serving { rig =>
      val pageId = rig.mountedPageId()
      bumps.publish(()) // no socket attached: the frame drops, the state moves
      val wire = rig.connect(pageId)
      wire.join()
      val resync = wire.frame()
      assert(resync.contains("<span>1</span>"), resync)
      wire.close()
    }
  }

  test("a malformed frame gets an error frame and the page survives it") {
    serving { rig =>
      val wire = rig.connect(rig.mountedPageId())
      wire.join()
      val _ = wire.frame() // the resync

      wire.send("not json at all")
      assert(wire.frame().contains("\"error\""))

      // A numeric payload value is itself malformed — payloads are string-valued — and refused.
      wire.send("""{"kind":"event","name":"inc","payload":{"n":3}}""")
      assert(wire.frame().contains("\"error\""))

      wire.send("""{"kind":"event","name":"inc","payload":{"n":"3"}}""")
      assert(wire.frame().contains("\"setText\""))
      wire.close()
    }
  }

  test("an event the component has no handler for fails as an error frame, not a dead page") {
    serving { rig =>
      val wire = rig.connect(rig.mountedPageId())
      wire.join()
      val _ = wire.frame()

      wire.event("no-such-event")
      assert(wire.frame().contains("failed"))

      wire.event("inc")
      assert(wire.frame().contains("\"setText\""))
      wire.close()
    }
  }

  test("an unknown page id is closed with 4404") {
    serving { rig =>
      val wire = rig.connect("0" * 32)
      assertEquals(wire.closeCode(), 4404)
    }
  }

  test("a second socket on a connected page is closed with 4409") {
    serving { rig =>
      val pageId = rig.mountedPageId()
      val first  = rig.connect(pageId)
      first.join()
      val _      = first.frame()
      val second = rig.connect(pageId)
      assertEquals(second.closeCode(), 4409)
      first.close()
    }
  }

  test("a cross-site origin is refused with 4403; the page's own origin is welcome") {
    serving { rig =>
      val pageId = rig.mountedPageId()
      val evil   = rig.connect(pageId, origin = Some("http://evil.example"))
      assertEquals(evil.closeCode(), 4403)

      val own = rig.connect(pageId, origin = Some(s"http://localhost:${rig.port}"))
      own.join()
      assert(own.frame().contains("\"setChildren\""))
      own.close()
    }
  }

  test("a ping is answered with a pong") {
    serving { rig =>
      val wire = rig.connect(rig.mountedPageId())
      wire.send("""{"kind":"ping"}""")
      assert(wire.frame().contains("\"pong\""))
      wire.close()
    }
  }

  test("a clean close frees the page at once: the id is gone for good") {
    serving { rig =>
      val pageId = rig.mountedPageId()
      val wire   = rig.connect(pageId)
      wire.join()
      val _ = wire.frame()
      wire.close(1000)
      assertEquals(wire.closeCode(), 1000)

      val again = rig.connect(pageId)
      assertEquals(again.closeCode(), 4404)
    }
  }

  test("an abnormal drop keeps the page: a rejoin resyncs onto the same state") {
    serving { rig =>
      val pageId = rig.mountedPageId()
      val first  = rig.connect(pageId)
      first.join()
      val _ = first.frame()
      first.event("inc")
      val _ = first.frame()

      first.drop() // a hard transport close: the server sees 1006 and opens the grace window
      val _ = first.closeCode()

      val second = rig.connect(pageId)
      second.join()
      val resync = second.frame()
      assert(resync.contains("<span>1</span>"), resync)
      second.close()
    }
  }
}
