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

/** A bound page against a booted server, with a `Guarded` built here rather than by a guard.
  *
  * The declaration is hand built on purpose: `live` compares two strings some other module put on
  * the request, so the suite that pins the comparison must not be able to reach a guard. What
  * stands in for a session is a header the fake declaration reads, which is enough because the
  * whole rule under test is that the two stamps are the same text. The umbrella's own suite is the
  * one that drives a real guard, real cookies and a real expiry.
  */
class BoundPageSuite extends munit.FunSuite {

  private val WhoHeader = "X-Eezo-Test-User"

  private final class Counter extends Component[Int] {
    def init(ctx: Init[Int]): Int         = 0
    def handle(event: Event, n: Int): Int = n + 1
    def render(n: Int): Html              = div(span(n), button(Live.onClick("inc"), "+"))
  }

  /** The fake session: whoever the header names is who this request is for. */
  private val naming: Request => Request =
    request => request.copy(currentUser = request.header(WhoHeader))

  /** A declaration that names a request and lets every one of them through. Refusing is the other
    * half of a real guard and has nothing to do with binding, so it is deliberately absent: what
    * this suite needs is a route whose renders are stamped and whose upgrades are stamped too.
    */
  private val guarded: Guarded[Any] = Guarded[Any](
    actions = Set.empty,
    through = {
      case http: Route.Http => http.copy(handler = naming.andThen(http.handler))
      case other            => other
    },
    carries = Seq.empty,
    identify = naming
  )

  private def pageRoute(at: String): Route =
    Route.Http(
      Method.GET,
      PathPattern.parse(at),
      request =>
        Response.Ok(Html.doctype ++ html(head(title("t")), body(Live.mount(request, new Counter))))
    )

  /** What a generated table looks like: the guarded page through its declaration, the public one
    * beside it, and the declaration's own naming on the table, which is what reaches an upgrade.
    */
  private def serving(body: Rig => Unit): Unit = {
    val user   = guarded.mounting(pageRoute("/dashboard")) :+ pageRoute("/public")
    val table  = RouteTable(user, guarded.identify) ++ RouteTable(Live.routes)
    val server = Eezo.start(port = 0, config = Config(table))
    val ws     = new WebSocketClient()
    ws.start()
    try body(new Rig(server.getConnectors.head.asInstanceOf[ServerConnector].getLocalPort, ws))
    finally {
      ws.stop()
      server.stop()
    }
  }

  private final class Rig(val port: Int, ws: WebSocketClient) {

    private val http = HttpClient.newHttpClient()

    /** Renders the page as `who`, and hands back the id off the marker. */
    def pageId(at: String, who: Option[String]): String = {
      val builder = HttpRequest.newBuilder(URI.create(s"http://localhost:$port$at"))
      who.foreach(builder.header(WhoHeader, _))
      val body = http.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body()
      "data-eezo-page=\"([0-9a-f]{32})\"".r
        .findFirstMatchIn(body)
        .map(_.group(1))
        .getOrElse(fail(s"no page marker in: $body"))
    }

    def connect(pageId: String, who: Option[String]): Wire = {
      val listener = new Listener
      val upgrade  = new ClientUpgradeRequest(URI.create(s"ws://localhost:$port/eezo/live/$pageId"))
      who.foreach(upgrade.setHeader(WhoHeader, _))
      new Wire(ws.connect(listener, upgrade).get(), listener)
    }
  }

  private final class Listener extends Session.Listener.AbstractAutoDemanding {
    val frames = new LinkedBlockingQueue[String]()
    val closes = new LinkedBlockingQueue[(Int, String)]()

    override def onWebSocketText(text: String): Unit               = { val _ = frames.offer(text) }
    override def onWebSocketClose(code: Int, reason: String): Unit = {
      val _ = closes.offer((code, reason))
    }
  }

  private final class Wire(session: Session, listener: Listener) {

    def join(): Unit = session.sendText("""{"kind":"join","base":"/"}""", Callback.NOOP)

    def resync(): String = {
      val received = listener.frames.poll(5, TimeUnit.SECONDS)
      assert(received != null, "no frame within 5s")
      received
    }

    def closed(): (Int, String) =
      Option(listener.closes.poll(5, TimeUnit.SECONDS)).getOrElse(fail("no close within 5s"))

    def close(): Unit = session.close(1000, "bye", Callback.NOOP)
  }

  test("a request free mount does not compile: every page must say who rendered it") {
    assert(compileErrors("io.eezo.live.Live.mount(new Counter)").nonEmpty)
  }

  test("a bound page answers its own user's socket") {
    serving { rig =>
      val wire = rig.connect(rig.pageId("/dashboard", Some("alice")), Some("alice"))
      wire.join()
      assert(wire.resync().contains("\"setChildren\""))
      wire.close()
    }
  }

  test("a bound page refuses a socket signed in as nobody, with 4403 and the reason why") {
    serving { rig =>
      val refused = rig.connect(rig.pageId("/dashboard", Some("alice")), None).closed()
      assertEquals(refused, (4403, "not signed in as the page's user"))
    }
  }

  test("a bound page refuses another signed in user, with 4403") {
    serving { rig =>
      val id = rig.pageId("/dashboard", Some("alice"))
      assertEquals(rig.connect(id, Some("mallory")).closed()._1, 4403)
    }
  }

  test("a refused stranger costs the page nothing: its own user still joins afterwards") {
    serving { rig =>
      val id = rig.pageId("/dashboard", Some("alice"))
      assertEquals(rig.connect(id, Some("mallory")).closed()._1, 4403)

      val wire = rig.connect(id, Some("alice"))
      wire.join()
      assert(wire.resync().contains("\"setChildren\""))
      wire.close()
    }
  }

  test(
    "a page rendered on a public route stays open to anyone: a header at render time changes " +
      "nothing, because a public route's handler never reads it"
  ) {
    serving { rig =>
      val unstamped   = rig.pageId("/public", None)
      val withVisitor = rig.pageId("/public", Some("alice"))

      // Both renders are unbound alike: /public bypasses the declaration's naming, so a header
      // that would stamp a guarded route's currentUser never reaches this handler at all.
      for (id <- Seq(unstamped, withVisitor)) {
        val wire = rig.connect(id, None)
        wire.join()
        assert(wire.resync().contains("\"setChildren\""))
        wire.close()
      }

      // "Anyone" means anyone: a socket naming a different visitor than the header carried at
      // render still joins the page, because it was never bound to that name either. A fresh
      // render, since the ones above already closed and left the registry.
      val anotherWithVisitor = rig.pageId("/public", Some("alice"))
      val otherWire          = rig.connect(anotherWithVisitor, Some("mallory"))
      otherWire.join()
      assert(otherWire.resync().contains("\"setChildren\""))
      otherWire.close()
    }
  }
}
