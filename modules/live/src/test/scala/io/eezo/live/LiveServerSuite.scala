package io.eezo.live

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import io.eezo.core.html.{Attrs, Html, Url}
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

  /** A counter whose render carries a mounted link, for the prefix tests: the href must follow the
    * mount in the initial response, in the join resync, and in every later patch.
    */
  private final class LinkCounter extends Component[Int] {
    def init(ctx: Init[Int]): Int         = 0
    def handle(event: Event, n: Int): Int = n + 1
    def render(n: Int): Html              =
      div(a(Attrs.href := Url.Mounted(s"/posts/$n"), "posts"), span(n))
  }

  /** A handwritten page route whose handler mounts per request, which is what a real handler does:
    * `mounted` is by-name so every GET is a fresh page.
    */
  private def pageRoute(mounted: => Html, at: String = "/counter"): Route =
    Route.Http(
      Method.GET,
      PathPattern.parse(at),
      _ =>
        Response.Ok(
          Html.doctype ++ html(
            head(title("t")),
            io.eezo.core.html.Tags.body(mounted)
          )
        )
    )

  /** Everything a test drives: one server with the given user routes plus the live framework
    * routes, one HTTP client, one WS client.
    */
  private def servingRoutes(user: Seq[Route])(body: Rig => Unit): Unit = {
    val server = Eezo.start(port = 0, config = Config(RouteTable(user ++ Live.routes)))
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

  private def serving(body: Rig => Unit): Unit =
    servingRoutes(Seq(pageRoute(Live.mount(new Counter))))(body)

  private final class Rig(val port: Int, ws: WebSocketClient) {

    private val http = HttpClient.newHttpClient()

    def get(path: String): HttpResponse[String] =
      http.send(
        HttpRequest.newBuilder(URI.create(s"http://localhost:$port$path")).build(),
        HttpResponse.BodyHandlers.ofString()
      )

    /** Mounts a fresh page over HTTP and returns its id, read off the marker. */
    def mountedPageId(at: String = "/counter"): String = {
      val html = get(at).body()
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

    def join(base: String = "/"): Unit = send(s"""{"kind":"join","base":"$base"}""")

    def event(name: String): Unit = send(s"""{"kind":"event","name":"$name","payload":{}}""")

    def frame(): String = {
      val received = listener.frames.poll(5, TimeUnit.SECONDS)
      assert(received != null, "no frame within 5s")
      received
    }

    /** A frame if one arrives within the window, null otherwise: for drain-until-quiet loops. */
    def poll(millis: Long): String | Null = listener.frames.poll(millis, TimeUnit.MILLISECONDS)

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

  test("the client script is served whole, and told to revalidate") {
    serving { rig =>
      val response = rig.get("/eezo/live.js")
      assertEquals(response.statusCode(), 200)
      assert(response.body().contains("applyPatches"))
      assert(response.body().contains("__eezoLiveClient"))
      assertEquals(response.headers().firstValue("Cache-Control").orElse(""), "no-cache")
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

  test("a page mounted under a prefix: links right in the response, the resync and the patches") {
    val mounted = Route.under("/admin")(Seq(pageRoute(Live.mount(new LinkCounter))))
    servingRoutes(mounted) { rig =>
      // The initial response was rewritten by the mount: the base and the link both moved.
      val html = rig.get("/admin/counter").body()
      assert(html.contains("data-eezo-base=\"/admin\""), html)
      assert(html.contains("href=\"/admin/posts/0\""), html)

      val wire = rig.connect(rig.mountedPageId("/admin/counter"))
      wire.join(base = "/admin")
      val resync = wire.frame()
      assert(resync.contains("/admin/posts/0"), resync)
      assert(!resync.contains("/admin/admin"), resync)

      // The re-render's patch carries the moved url too: the seam design/live.md §2.6 exists for.
      wire.event("inc")
      val patch = wire.frame()
      assert(patch.contains("\"setAttr\""), patch)
      assert(patch.contains("/admin/posts/1"), patch)
      wire.close()
    }
  }

  test("a patch-failure report from the client is answered with a full resync") {
    serving { rig =>
      val wire = rig.connect(rig.mountedPageId())
      wire.join()
      val _ = wire.frame()

      wire.send("""{"kind":"failed","reasons":["expected <div> at [0,1], found #text"]}""")
      val resync = wire.frame()
      assert(resync.contains("\"setChildren\""), resync)
      assert(resync.contains("<span>0</span>"), resync)
      wire.close()
    }
  }

  test("a flood of publishes coalesces into bounded frames and the page stays responsive") {
    val flood = new Topic[Unit]
    final class FloodBoard extends Component[(Int, Int)] {
      def init(ctx: Init[(Int, Int)]): (Int, Int) = {
        ctx.subscribe(flood)((_, s) => (s._1 + 1, s._2))
        (0, 0)
      }
      def handle(event: Event, s: (Int, Int)): (Int, Int) = (s._1, s._2 + 1)
      def render(s: (Int, Int)): Html                     = div(span(s._1), em(s._2))
    }

    servingRoutes(Seq(pageRoute(Live.mount(new FloodBoard)))) { rig =>
      val wire = rig.connect(rig.mountedPageId())
      wire.join()
      val _ = wire.frame()

      val publishers = (1 to 4).map { _ =>
        Thread.ofVirtual().start(() => (1 to 2500).foreach(_ => flood.publish(())))
      }
      publishers.foreach(_.join())

      // Drain until quiet, counting: coalescing and drop-to-resync must keep this far under one
      // frame per message.
      var received = 0
      while (wire.poll(300) != null) received += 1
      assert(received < 1500, s"$received frames for 10000 messages is not coalescing")

      // Still responsive: a click round-trips and its patch says clicks=1.
      wire.event("click")
      val patch = wire.frame()
      assert(patch.contains("\"1\""), patch)
      wire.close()
    }
  }

  test("a live form: per-keystroke validation events and a whole-form submit") {
    final class Signup extends Component[(String, Boolean)] {
      def init(ctx: Init[(String, Boolean)]): (String, Boolean)         = ("", false)
      def handle(event: Event, s: (String, Boolean)): (String, Boolean) = event.name match {
        case "email-changed" => (event.payload.getOrElse("value", ""), s._2)
        case "save"          => (event.payload.getOrElse("email", ""), true)
      }
      def render(s: (String, Boolean)): Html = {
        val (email, saved) = s
        form(
          Live.onSubmit("save"),
          input(
            Attrs.tpe   := "text",
            Attrs.name  := "email",
            Attrs.value := email,
            Live.onInput("email-changed")
          ),
          p(if (email.isEmpty) "required" else if (email.contains("@")) "ok" else "no @"),
          p(if (saved) "saved" else "unsaved")
        )
      }
    }

    servingRoutes(Seq(pageRoute(Live.mount(new Signup)))) { rig =>
      val html = rig.get("/counter").body()
      assert(html.contains("data-eezo-input=\"email-changed\""), html)
      assert(html.contains("data-eezo-debounce=\"300\""), html)
      assert(html.contains("data-eezo-submit=\"save\""), html)

      val wire = rig.connect(rig.mountedPageId())
      wire.join()
      val _ = wire.frame()

      // A keystroke's worth of input: the validation text and the echoed value attribute move.
      wire.send("""{"kind":"event","name":"email-changed","payload":{"value":"a"}}""")
      val typed = wire.frame()
      assert(typed.contains("no @"), typed)

      wire.send("""{"kind":"event","name":"email-changed","payload":{"value":"a@b.c"}}""")
      assert(wire.frame().contains("ok"))

      // The submit arrives as one payload of named fields, the way FormData sends it.
      wire.send("""{"kind":"event","name":"save","payload":{"email":"a@b.c"}}""")
      assert(wire.frame().contains("saved"))
      wire.close()
    }
  }
}
