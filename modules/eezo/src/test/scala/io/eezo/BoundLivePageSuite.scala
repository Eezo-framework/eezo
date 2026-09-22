package io.eezo

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Instant
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import io.eezo.auth.Guard
import io.eezo.core.Id
import io.eezo.core.html.Html
import io.eezo.core.html.Tags.*
import io.eezo.http.*
import io.eezo.live.{Component, Event, Init, Live}
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.websocket.api.{Callback, Session as WsSession}
import org.eclipse.jetty.websocket.client.{ClientUpgradeRequest, WebSocketClient}

/** A bound live page driven the way a browser drives one: a real guard, the cookie eezo itself
  * signed, and a real upgrade over a real socket.
  *
  * It lives in the umbrella because it is the only place `http`, `auth` and `live` are all on one
  * classpath, and binding is exactly the seam between the three: `auth` decides who is signed in,
  * `http` carries that verdict on the request, and `live` compares the two stamps. Each module's
  * own suite pins its half against something hand built; nothing but this can pin that the three
  * halves meet.
  *
  * No password is ever hashed here. A sign in is planted by handing the browser a session eezo
  * signed, which is what a successful login would have written anyway, so the expiry case costs a
  * cookie rather than a fortnight and the suite costs no bcrypt.
  */
class BoundLivePageSuite extends munit.FunSuite {

  private case class User(id: Id[User])

  private val alice   = User(Id.gen())
  private val mallory = User(Id.gen())

  /** `credentials` answers nobody: this suite never signs anyone in through the login form. */
  private val guard: Guard[User] =
    Guard[User](Map(alice.id -> alice, mallory.id -> mallory).get, _ => None)

  private final class Counter extends Component[Int] {
    def init(ctx: Init[Int]): Int         = 0
    def handle(event: Event, n: Int): Int = n + 1
    def render(n: Int): Html              = div(span(n), button(Live.onClick("inc"), "+"))
  }

  private def pageRoute(at: String): Route =
    Route.Http(
      Method.GET,
      PathPattern.parse(at),
      request =>
        Response.Ok(Html.doctype ++ html(head(title("t")), body(Live.mount(request, new Counter))))
    )

  private def signedIn(who: User, since: Instant): Session =
    Session.empty
      .withReserved(Guard.UserEntry, who.id.show)
      .withReserved(Guard.StampEntry, Guard.stamped(since))

  /** A page that hands the browser a session, so the tests below own a real signed cookie without
    * going through a password.
    */
  private def planting(at: String, session: Session): Route =
    Route.Http(
      Method.GET,
      PathPattern.parse(at),
      _ => Response.Ok(Html.text("planted")).withSession(session)
    )

  private val now    = Instant.now()
  private val lapsed = now.minus(Guard.DefaultLifetime).minusSeconds(1)

  /** What the generator emits: the guarded page through its declaration, the public page beside it,
    * and the declaration's own `identify` on the table, which is what reaches an upgrade.
    */
  private def serving(body: Rig => Unit): Unit = {
    val dashboard: Guarded[Any] = guard.required[Any]
    val user                    = dashboard.mounting(pageRoute("/dashboard")) ++ Seq(
      pageRoute("/public"),
      planting("/plant/alice", signedIn(alice, now)),
      planting("/plant/mallory", signedIn(mallory, now)),
      planting("/plant/lapsed", signedIn(alice, lapsed))
    )
    val server = Eezo.start(
      port = 0,
      config = Config(RouteTable(user, dashboard.identify) ++ RouteTable(Live.routes))
    )
    val ws = new WebSocketClient()
    ws.start()
    try body(new Rig(server.getConnectors.head.asInstanceOf[ServerConnector].getLocalPort, ws))
    finally {
      ws.stop()
      server.stop()
    }
  }

  private final class Rig(val port: Int, ws: WebSocketClient) {

    private val http = HttpClient.newHttpClient()

    private def get(path: String, cookie: Option[String]): HttpResponse[String] = {
      val builder = HttpRequest.newBuilder(URI.create(s"http://localhost:$port$path"))
      cookie.foreach(builder.header("Cookie", _))
      http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** The session cookie eezo signed for the browser that visited `path`. */
    def cookieFrom(path: String): String =
      get(path, None)
        .headers()
        .firstValue("Set-Cookie")
        .orElseThrow(() => new NoSuchElementException(s"$path handed back no session cookie"))
        .takeWhile(_ != ';')

    def pageId(at: String, cookie: Option[String]): String = {
      val body = get(at, cookie).body()
      "data-eezo-page=\"([0-9a-f]{32})\"".r
        .findFirstMatchIn(body)
        .map(_.group(1))
        .getOrElse(fail(s"no page marker in: $body"))
    }

    def connect(pageId: String, cookie: Option[String]): Wire = {
      val listener = new Listener
      val upgrade  = new ClientUpgradeRequest(URI.create(s"ws://localhost:$port/eezo/live/$pageId"))
      cookie.foreach(upgrade.setHeader("Cookie", _))
      new Wire(ws.connect(listener, upgrade).get(), listener)
    }
  }

  private final class Listener extends WsSession.Listener.AbstractAutoDemanding {
    val frames = new LinkedBlockingQueue[String]()
    val closes = new LinkedBlockingQueue[(Int, String)]()

    override def onWebSocketText(text: String): Unit               = { val _ = frames.offer(text) }
    override def onWebSocketClose(code: Int, reason: String): Unit = {
      val _ = closes.offer((code, reason))
    }
  }

  private final class Wire(session: WsSession, listener: Listener) {

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

  test("a guarded page joins with the very cookie that rendered it") {
    serving { rig =>
      val cookie = rig.cookieFrom("/plant/alice")
      val wire   = rig.connect(rig.pageId("/dashboard", Some(cookie)), Some(cookie))
      wire.join()
      assert(wire.resync().contains("\"setChildren\""))
      wire.close()
    }
  }

  test("a guarded page refuses a socket carrying no cookie at all, with 4403") {
    serving { rig =>
      val cookie = rig.cookieFrom("/plant/alice")
      val id     = rig.pageId("/dashboard", Some(cookie))
      assertEquals(rig.connect(id, None).closed(), (4403, "not signed in as the page's user"))
    }
  }

  test("a guarded page refuses another signed in user, with 4403") {
    serving { rig =>
      val id = rig.pageId("/dashboard", Some(rig.cookieFrom("/plant/alice")))
      assertEquals(rig.connect(id, Some(rig.cookieFrom("/plant/mallory"))).closed()._1, 4403)
    }
  }

  test("a guarded page refuses the same user a second past the sign in's lifetime, with 4403") {
    serving { rig =>
      val id = rig.pageId("/dashboard", Some(rig.cookieFrom("/plant/alice")))
      // The same person, the same browser: a sign in the guard no longer honours names nobody, and
      // nobody is not the page's user.
      assertEquals(rig.connect(id, Some(rig.cookieFrom("/plant/lapsed"))).closed()._1, 4403)
    }
  }

  test("a page rendered on a public route is joined by nobody, however the visitor arrived") {
    serving { rig =>
      val id   = rig.pageId("/public", Some(rig.cookieFrom("/plant/alice")))
      val wire = rig.connect(id, None)
      wire.join()
      assert(wire.resync().contains("\"setChildren\""))
      wire.close()
    }
  }
}
