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
    serving(RouteTable(user ++ Live.routes))(body)

  private def serving(body: Rig => Unit): Unit =
    servingRoutes(Seq(pageRoute(Live.mount(_, new SubscribedCounter))))(body)

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
      assertEquals(evil.closed(), (4403, "origin mismatch"))

      rig.joins(pageId, headers = Seq("Origin" -> s"http://localhost:${rig.port}"))
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
