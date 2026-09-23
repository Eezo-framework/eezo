package io.eezo.live

import io.eezo.core.html.{Attrs, Html, Url}
import io.eezo.core.html.Tags.*
import io.eezo.http.*

/** The whole M3 path, against a booted server: mount over HTTP, join over WebSocket, events in,
  * patches out, and every refusal with its close code. This is the suite that stands in for a
  * browser everywhere a browser is not the point; the manual counter test is the browser's turn.
  */
class LiveServerSuite extends munit.FunSuite with LiveServerFixtures {

  private val bumps = new Topic[Unit]

  /** A counter that also moves on a publish, for the tests about what a resync carries. */
  private final class SubscribedCounter extends Component[Int] {
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

  /** The given user routes plus the live framework routes. */
  private def servingRoutes(user: Seq[Route])(body: Rig => Unit): Unit =
    serving(RouteTable(user ++ Live.routes(Set.empty)))(body)

  private def counterPage: Seq[Route] = Seq(pageRoute(Live.mount(_, new SubscribedCounter)))

  private def serving(body: Rig => Unit): Unit =
    servingRoutes(counterPage)(body)

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
      val evil   = rig.connect(pageId, headers = Seq("Origin" -> "http://evil.example"))
      assertEquals(evil.closed(), (4403, "origin http://evil.example not allowed"))

      rig.joins(pageId, headers = Seq("Origin" -> s"http://localhost:${rig.port}"))
    }
  }

  test("the right authority under the wrong scheme is another origin, and refused with 4403") {
    serving { rig =>
      val pageId = rig.mountedPageId()
      val https  = rig.connect(pageId, headers = Seq("Origin" -> s"https://localhost:${rig.port}"))
      assertEquals(https.closed(), (4403, s"origin https://localhost:${rig.port} not allowed"))
    }
  }

  test("an Origin of null, or one that is not an origin at all, is refused with 4403") {
    serving { rig =>
      Seq("null", "localhost", s"http://localhost:${rig.port}/path", "http://").foreach { origin =>
        val wire = rig.connect(rig.mountedPageId(), headers = Seq("Origin" -> origin))
        assertEquals(wire.closed(), (4403, s"origin $origin not allowed"), origin)
      }
    }
  }

  /** An upgrade as the endpoint sees it, built by hand: what the origin rule reads is the headers
    * and whether the connection was secure, and nothing else.
    */
  private def upgrade(secure: Boolean, headers: (String, String)*): Request =
    Request(
      Method.GET,
      "/eezo/live/x",
      Map.empty,
      headers.map((k, v) => k -> Seq(v)).toMap,
      Array.emptyByteArray,
      Map.empty,
      secure = secure
    )

  test("normalisation: case, and the scheme's default port, never make two origins differ") {
    val pairs = Seq(
      "HTTP://LOCALHOST:8080" -> "http://localhost:8080",
      "http://host:80"        -> "http://host",
      "https://host:443"      -> "https://host",
      "http://[::1]:8080"     -> "http://[::1]:8080"
    )
    pairs.foreach((raw, expected) =>
      assertEquals(Origins.normalise(raw): Option[String], Some(expected), raw)
    )
    // A default port belongs to its scheme: 443 under http is a port like any other.
    assertEquals(Origins.normalise("http://host:443"): Option[String], Some("http://host:443"))
  }

  test("an underscore host, such as a compose service name, is still an origin") {
    // java.net.URI has no host for `my_app`, only an authority, yet browsers send it as is.
    val pairs = Seq(
      "http://my_app:8080" -> "http://my_app:8080",
      "HTTP://My_App:80"   -> "http://my_app",
      "https://my_app"     -> "https://my_app"
    )
    def normalised(raw: String): Option[String] = Origins.normalise(raw)
    pairs.foreach((raw, expected) => assertEquals(normalised(raw), Some(expected), raw))
    assert(
      Origins.admits(
        upgrade(false, "Origin" -> "http://my_app:8080", "Host" -> "my_app:8080"),
        Set.empty
      )
    )
    assert(
      !Origins.admits(
        upgrade(false, "Origin" -> "http://my_app:8080", "Host" -> "my_app:9090"),
        Set.empty
      )
    )
    // The fallback reads a host, not any authority: these stay refused.
    Seq("http://a@my_app", "http://*.my_app", "http://my_app:x", "http://my_app:99999").foreach {
      raw => assertEquals(normalised(raw), None, raw)
    }
  }

  test("a port above 65535 is refused the same way on a plain host as on an underscore one") {
    // java.net.URI reads a host for "localhost" and hands back 99999 from getPort unchecked;
    // the bound has to be enforced here too, or a typo like this survives boot and can never match
    // a real browser Origin, since no browser can send a port outside 0 to 65535.
    assertEquals(Origins.normalise("http://localhost:99999"): Option[String], None)
    intercept[IllegalArgumentException](Live.routes(Set("http://localhost:99999")))
  }

  test("normalisation applies to the Host side too, and the scheme comes from the connection") {
    assert(
      Origins.admits(upgrade(false, "Origin" -> "HTTP://Host", "Host" -> "HOST:80"), Set.empty)
    )
    assert(
      Origins.admits(upgrade(true, "Origin" -> "https://host", "Host" -> "host:443"), Set.empty)
    )
    assert(
      Origins.admits(upgrade(false, "Origin" -> "http://[::1]:9", "Host" -> "[::1]:9"), Set.empty)
    )
    assert(!Origins.admits(upgrade(true, "Origin" -> "http://host", "Host" -> "host"), Set.empty))
  }

  test("with no usable Host the server has no origin of its own, so a browser is refused") {
    assert(!Origins.admits(upgrade(false, "Origin" -> "http://host"), Set.empty))
    assert(
      !Origins.admits(upgrade(false, "Origin" -> "http://host", "Host" -> "host/evil"), Set.empty)
    )
    assert(
      !Origins.admits(upgrade(false, "Origin" -> "http://host", "Host" -> "a@host"), Set.empty)
    )
    assert(
      Origins.admits(upgrade(false, "Host" -> "host"), Set.empty),
      "no Origin is still admitted"
    )
  }

  test("an origin the application lists joins whatever the Host; anything else is still refused") {
    serving(RouteTable(counterPage ++ Live.routes(Set("https://app.example")))) { rig =>
      val pageId = rig.mountedPageId()
      rig.joins(pageId, headers = Seq("Origin" -> "https://app.example"))

      Seq("https://app.example.evil", "https://evil.app.example", "http://app.example").foreach {
        origin =>
          val wire = rig.connect(rig.mountedPageId(), headers = Seq("Origin" -> origin))
          assertEquals(wire.closed(), (4403, s"origin $origin not allowed"), origin)
      }
      // The list widens the server's own origin, never replaces it.
      rig.joins(rig.mountedPageId(), headers = Seq("Origin" -> s"http://localhost:${rig.port}"))
    }
  }

  test("a listed origin is compared normalised, the same way the header is") {
    serving(RouteTable(counterPage ++ Live.routes(Set("HTTPS://App.Example:443")))) { rig =>
      rig.joins(rig.mountedPageId(), headers = Seq("Origin" -> "https://app.example"))
    }
  }

  test("a listed entry that is not an origin fails the boot, naming the entry") {
    Seq("https://app.example/", "app.example", "*", "https://*.app.example", "null").foreach {
      entry =>
        val thrown = intercept[IllegalArgumentException](Live.routes(Set(entry)))
        assert(thrown.getMessage.contains(s"'$entry'"), thrown.getMessage)
        assert(thrown.getMessage.contains("allowedOrigins"), thrown.getMessage)
    }
  }

  /** An application that widens the list the way a user would, and hands its framework routes to
    * the suite: `frameworkRoutes` is what every way of serving appends, so serving it is serving
    * what `boot` would, without a port picked up front or a thread blocked in `run`.
    */
  private object ProxiedApp extends LiveApp {
    def routes: RouteTable                             = RouteTable(Nil)
    override protected def allowedOrigins: Set[String] = Set("https://app.example")
    def framework: Seq[Route]                          = frameworkRoutes
  }

  test("LiveApp carries its allowedOrigins to the socket it contributes") {
    serving(RouteTable(counterPage ++ ProxiedApp.framework)) { rig =>
      rig.joins(rig.mountedPageId(), headers = Seq("Origin" -> "https://app.example"))
      val evil = rig.connect(rig.mountedPageId(), headers = Seq("Origin" -> "http://evil.example"))
      assertEquals(evil.closeCode(), 4403)
    }
  }

  /** The WARNING records the live logger publishes about `pageId` while `body` runs. The logger is
    * process wide and other suites share it, so the records are filtered by the page they name.
    */
  private def warningsAbout(pageId: String)(body: => Unit): List[String] = {
    val logger   = java.util.logging.Logger.getLogger("io.eezo.live")
    val captured = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val handler  = new java.util.logging.Handler {
      override def publish(record: java.util.logging.LogRecord): Unit =
        if (
          record.getLevel == java.util.logging.Level.WARNING &&
          record.getMessage.contains(pageId)
        ) { val _ = captured.add(record.getMessage) }
      override def flush(): Unit = ()
      override def close(): Unit = ()
    }
    logger.addHandler(handler)
    try body
    finally logger.removeHandler(handler)
    captured.toArray(Array.empty[String]).toList
  }

  test("a refused origin is one WARNING naming the page, both origins and what to do") {
    serving { rig =>
      val pageId  = rig.mountedPageId()
      val records = warningsAbout(pageId) {
        val evil = rig.connect(pageId, headers = Seq("Origin" -> "http://evil.example"))
        assertEquals(evil.closeCode(), 4403)
      }
      assertEquals(records.size, 1, records)
      val record = records.head
      assert(record.contains("http://evil.example"), record)
      assert(record.contains(s"http://localhost:${rig.port}"), record)
      assert(record.contains("a proxy that rewrites Host should forward it"), record)
      assert(record.contains("LiveApp.allowedOrigins"), record)
    }
  }

  test("a huge Origin still closes with 4403, its echo capped to fit a close reason") {
    serving { rig =>
      val origin         = "http://" + ("a" * 4000) + ".example"
      val wire           = rig.connect(rig.mountedPageId(), headers = Seq("Origin" -> origin))
      val (code, reason) = wire.closed()
      assertEquals(code, 4403)
      assert(reason.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 123, reason)
      assert(reason.startsWith("origin http://aaa"), reason)
      assert(reason.endsWith(" not allowed"), reason)
    }
  }

  test("what the client chose is echoed escaped: no control character reaches a log line") {
    assertEquals(Origins.shown("http://a\r\nFAKE b\u0007"), "http://a\\u000d\\u000aFAKE b\\u0007")
    assertEquals(Origins.shown("http://évil"), "http://\\u00e9vil")
    assertEquals(Origins.shown("http://evil.example"), "http://evil.example")
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
    val mounted = Route.under("/admin")(Seq(pageRoute(Live.mount(_, new LinkCounter))))
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

    servingRoutes(Seq(pageRoute(Live.mount(_, new FloodBoard)))) { rig =>
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

    servingRoutes(Seq(pageRoute(Live.mount(_, new Signup)))) { rig =>
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

  test("a component that calls out: loading now, Ok, Denied and Unreachable each patch in") {
    import io.eezo.http.client.{Auth, Http, Reply}

    val baseUrl = new java.util.concurrent.atomic.AtomicReference[String]("")

    final class Caller(async: Async[String]) extends Component[String] {
      def init(ctx: Init[String]): String = "idle"

      private def outcome(reply: Reply): String => String = reply match {
        case Reply.Ok(r)          => _ => s"got ${r.text}"
        case Reply.Denied(r)      => _ => s"denied ${r.status}"
        case Reply.Failed(r)      => _ => s"failed ${r.status}"
        case Reply.Unreachable(_) => _ => "unreachable"
      }

      def handle(event: Event, s: String): String = {
        event.name match {
          case "fetch" => async.get(s"${baseUrl.get}/api", Auth.bearer("let-me-in"))(outcome)
          case "broke" => async.get(s"${baseUrl.get}/api", Auth.bearer("wrong"))(outcome)
          case "gone"  => async(outcome(Http.get("http://localhost:1/api")))
          case _       => ()
        }
        "loading"
      }

      def render(s: String): Html = div(span(s))
    }

    val api = Route.Http(
      Method.GET,
      PathPattern.parse("/api"),
      request =>
        if (request.header("Authorization").contains("Bearer let-me-in"))
          Response(200, Seq("Content-Type" -> "text/plain"), Body.Bytes("the goods".getBytes))
        else Response.status(401)
    )

    servingRoutes(Seq(api, pageRoute(request => Live.mount(request, new Caller(_))))) { rig =>
      baseUrl.set(s"http://localhost:${rig.port}")
      val wire = rig.connect(rig.mountedPageId())
      wire.join()
      val _ = wire.frame()

      def untilFrame(marker: String): Unit = {
        val deadline = System.currentTimeMillis + 5000
        var found    = false
        while (!found && System.currentTimeMillis < deadline) {
          val frame = wire.poll(200)
          if (frame != null && frame.contains(marker)) found = true
        }
        assert(found, s"no frame containing '$marker' within 5s")
      }

      // The loading state is the event's own frame; the outcome patches in after the call.
      wire.event("fetch")
      assert(wire.frame().contains("loading"))
      untilFrame("got the goods")

      wire.event("broke")
      untilFrame("denied 401")

      wire.event("gone")
      untilFrame("unreachable")
      wire.close()
    }
  }
}
