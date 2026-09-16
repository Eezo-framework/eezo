package io.eezo.live

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

import io.eezo.core.html.{AttrName, Attr, Attrs, Html, Tags, Url}
import io.eezo.http.{Body, Eezo, Method, PathPattern, Request, Response, Route, WsConn, WsListener}

/** The live layer's front door: [[mount]] in a handler, [[routes]] on the server, the event binding
  * attributes in views.
  *
  * A handler embeds a component wherever it wants in its own page:
  *
  * {{{
  * def board(request: Request): Response =
  *   Response.Ok(layout(Live.mount(Board())))
  * }}}
  *
  * `mount` runs `init`, registers the page, and returns a fragment of two nodes: the anchor
  * `<div data-eezo-page=…>` holding the first render, and the `<script src="/eezo/live.js">` that
  * brings it to life. Carrying the script *in the fragment* is what keeps the promise that the
  * framework never builds the page (design/live.md §2.3) without http ever scanning a response for
  * markers: the app owns the whole document, and the mount is just a value in it. The script's
  * address is a plain string on purpose — the socket and the script are served unmounted under the
  * reserved prefix, so a mounted page must not have them rewritten.
  *
  * One mount per response in v1. A second mount renders (each is an ordinary value) but the client
  * drives only the first anchor it finds; multi-mount is on the backlog.
  *
  * The registry and the reaper are process-wide the way the installed `Database` is: built once,
  * reached from the routes below, invisible to user code.
  */
object Live {

  private val log      = System.getLogger("io.eezo.live")
  private val registry = new PageRegistry()
  private val senders  = new ConcurrentHashMap[String, Sender]

  private val PageAttr  = AttrName("data-eezo-page")
  private val DeadAttr  = AttrName("data-eezo-dead")
  private val ClickAttr = AttrName("data-eezo-click")

  /** Marks an element as a click binding: `button(Live.onClick("inc"), "+")` sends `Event("inc")`
    * to the component's `handle`. The client delegates one listener at the document, so the binding
    * survives any patch that replaces the element.
    */
  def onClick(name: String): Attr = ClickAttr := name

  /** Mounts `component` as a live page: the returned fragment goes wherever the handler's own
    * layout puts it. At the registry's cap the component is rendered once, statically, and the page
    * says so in an attribute the client logs — a busy site degrades to working pages that do not
    * update, never to errors (design/live.md §2.5).
    */
  def mount[S](component: Component[S]): Html =
    registry.register { id =>
      val sender = new Sender(id)
      val _      = senders.put(id, sender)
      new Page(id, component, sender.send)
    } match {
      case Some(page) =>
        try {
          val tree = page.mount()
          anchor(tree, PageAttr := page.id) ++ script
        } catch {
          case e: Throwable =>
            registry.close(page.id)
            val _ = senders.remove(page.id)
            throw e
        }
      case None =>
        log.log(
          System.Logger.Level.WARNING,
          s"live page registry at capacity (${PageRegistry.DefaultCap}); serving a static render"
        )
        anchor(staticRender(component), DeadAttr := "capacity")
    }

  /** What [[io.eezo.http.HttpApp.frameworkRoutes]] carries when `LiveApp` is mixed in: the one
    * socket every page joins, and the client script. Both under the reserved prefix, both
    * unmounted, both absent from `eezo routes` (they are the framework's, not the user's) though
    * present in the boot listing, which shows what is actually served.
    */
  val routes: Seq[Route] = Seq(
    Route.Ws(PathPattern.parse(s"${Eezo.ReservedPrefix}/live/:page"), endpoint),
    Route.Http(
      Method.GET,
      PathPattern.parse(s"${Eezo.ReservedPrefix}/live.js"),
      _ =>
        Response(200, Seq("Content-Type" -> "text/javascript; charset=utf-8"), Body.Bytes(clientJs))
    )
  )

  /** The anchor div: the node the applier resolves every path from. `data-eezo-base` is a mounted
    * url, so `Route.under` moves it with the page and the client reports the moved value at join
    * (design/live.md §2.6).
    */
  private def anchor(tree: Html.Element, mark: Attr): Html =
    Tags.div(mark, Attrs.eezoBase := Url.Mounted("/"), tree)

  private val script: Html =
    Tags.script(Attrs.src := s"${Eezo.ReservedPrefix}/live.js", AttrName("defer") := true)

  /** The component rendered with no page behind it: `init` runs, its subscriptions are immediately
    * cancelled, and the tree is served as it stands.
    */
  private def staticRender[S](component: Component[S]): Html.Element = {
    val ctx   = new Init[S](_ => ())
    val state = component.init(ctx)
    ctx.seal().foreach(_.cancel())
    Canonical.root(component.render(state))
  }

  /** applier.js and client.js, concatenated once at startup: the applier is the file the round-trip
    * harness certifies, byte for byte, and the client is everything around it.
    */
  private lazy val clientJs: Array[Byte] = {
    def resource(name: String): String = {
      val in = getClass.getResourceAsStream(name)
      require(in != null, s"missing classpath resource $name")
      try new String(in.readAllBytes(), StandardCharsets.UTF_8)
      finally in.close()
    }
    (resource("/io/eezo/live/applier.js") + "\n" + resource("/io/eezo/live/client.js"))
      .getBytes(StandardCharsets.UTF_8)
  }

  /** One socket, one page, states in lockstep with the registry. Jetty delivers a session's
    * callbacks serially, so the `attached` var is single-threaded by contract.
    */
  private def endpoint(request: Request): WsListener = new WsListener {

    private val id = request.pathParams.getOrElse("page", "")

    private var attached: Option[(Page[?], Sender)] = None

    override def onOpen(conn: WsConn): Unit =
      if (!originAllowed(request)) {
        log.log(System.Logger.Level.WARNING, s"page $id: upgrade refused, origin mismatch")
        conn.close(4403, "origin mismatch")
      } else {
        registry.connect(id) match {
          case Left(PageRegistry.ConnectRefusal.Unknown) => conn.close(4404, "unknown page")
          case Left(PageRegistry.ConnectRefusal.AlreadyConnected) =>
            conn.close(4409, "page already connected")
          case Right(page) =>
            senders.get(id) match {
              case null   => conn.close(4404, "unknown page")
              case sender =>
                sender.attach(conn)
                attached = Some((page, sender))
            }
        }
      }

    override def onText(conn: WsConn, text: String): Unit =
      attached.foreach { case (page, _) =>
        Wire.read(text) match {
          case Left(problem) =>
            log.log(System.Logger.Level.WARNING, s"page $id sent a malformed frame: $problem")
            conn.send(Wire.error(problem))
          case Right(Wire.ClientMessage.Join(_)) =>
            // The base is M4's concern; the resync is this milestone's: the client starts from
            // the tree the page holds now, whatever moved between mount and join.
            page.resync()
          case Right(Wire.ClientMessage.Emit(event)) =>
            try page.event(event)
            catch {
              case e: Exception =>
                log.log(System.Logger.Level.ERROR, s"page $id event '${event.name}' failed", e)
                conn.send(Wire.error(s"event '${event.name}' failed"))
            }
          case Right(Wire.ClientMessage.Ping) => conn.send(Wire.pong)
        }
      }

    override def onClose(code: Int, reason: String): Unit =
      attached.foreach { case (page, sender) =>
        sender.detach()
        // 1000 is the client saying goodbye on purpose (beforeunload); everything else gets the
        // grace window, reaped on the cadence below.
        if (code == 1000) {
          registry.close(page.id)
          val _ = senders.remove(page.id)
        } else registry.disconnect(page.id)
      }

    override def onError(cause: Throwable): Unit =
      attached.foreach { case (page, sender) =>
        sender.detach()
        registry.disconnect(page.id)
      }
  }

  /** A browser names where the page came from; a socket opened by another site is refused. No
    * `Origin` header is a non-browser client, allowed: the page id is the capability, and origin
    * checking exists against cross-site use of a *browser's* credentials.
    */
  private def originAllowed(request: Request): Boolean =
    request.header("Origin") match {
      case None         => true
      case Some(origin) =>
        request.header("Host").exists { host =>
          try new URI(origin).getAuthority == host
          catch { case _: Exception => false }
        }
    }

  /** The socket a page's frames leave through, if one is attached. Detached, frames drop by design:
    * the resync at the next join replays the tree, so nothing is owed to a socket that is not
    * there.
    */
  private final class Sender(pageId: String) {

    @volatile private var conn: Option[WsConn] = None

    def attach(c: WsConn): Unit = conn = Some(c)

    def detach(): Unit = conn = None

    def send(patches: List[Patch]): Unit = conn.foreach { c =>
      try c.send(Wire.patches(patches))
      catch {
        case e: Exception =>
          // A stalled or vanished client. Closing fires onClose, which starts the grace window.
          log.log(
            System.Logger.Level.WARNING,
            s"page $pageId: send failed (${e.getMessage}); closing the connection"
          )
          detach()
          c.close(1011, "send failed")
      }
    }
  }

  locally {
    // The reaper: never-connected pages past their TTL and dropped pages past their grace window
    // go, on a fixed cadence, for the process's lifetime. A virtual thread parked 10s at a time.
    val _ = Thread
      .ofVirtual()
      .name("eezo-live-reaper")
      .start(() => {
        while (true) {
          Thread.sleep(10_000)
          val gone = registry.reap()
          gone.foreach(id => senders.remove(id))
          if (gone.nonEmpty)
            log.log(System.Logger.Level.INFO, s"reaped ${gone.size} live page(s)")
        }
      })
  }
}
