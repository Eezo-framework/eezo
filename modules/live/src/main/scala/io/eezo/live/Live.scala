package io.eezo.live

import java.nio.charset.StandardCharsets

import io.eezo.core.html.{AttrName, Attr, Attrs, Html, Tags, Url}
import io.eezo.http.{Body, Eezo, Method, PathPattern, Request, Response, Route, WsConn, WsListener}

/** The live layer's front door: [[mount]] in a handler, [[routes]] on the server, the event binding
  * attributes in views.
  *
  * A handler embeds a component wherever it wants in its own page:
  *
  * {{{
  * def board(request: Request): Response =
  *   Response.Ok(layout(Live.mount(request, Board())))
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

  private val PageAttr     = AttrName("data-eezo-page")
  private val DeadAttr     = AttrName("data-eezo-dead")
  private val ClickAttr    = AttrName("data-eezo-click")
  private val InputAttr    = AttrName("data-eezo-input")
  private val ChangeAttr   = AttrName("data-eezo-change")
  private val SubmitAttr   = AttrName("data-eezo-submit")
  private val DebounceAttr = AttrName("data-eezo-debounce")

  /** Marks an element as a click binding: `button(Live.onClick("inc"), "+")` sends `Event("inc")`
    * to the component's `handle`. The client delegates one listener at the document, so the binding
    * survives any patch that replaces the element — and so do all the bindings below.
    */
  def onClick(name: String): Attr = ClickAttr := name

  /** A per-keystroke binding, debounced on the client: after `debounceMillis` of quiet the
    * element's current value arrives as `Event(name, Map("value" -> …))`. The debounce is per
    * element, so typing in one field does not flush another's timer.
    */
  def onInput(name: String, debounceMillis: Int = 300): Seq[Attr] =
    Seq(InputAttr := name, DebounceAttr := debounceMillis)

  /** A committed-change binding, sent at once: a checkbox or radio arrives as
    * `Map("value" -> "on" | "")`, a select or text input as its value. The right binding for
    * controls where every change is a decision rather than a keystroke.
    */
  def onChange(name: String): Attr = ChangeAttr := name

  /** A form submission binding: the browser's submit is intercepted, and every named field in the
    * form arrives as one payload, `Map(fieldName -> value)`. Checkboxes follow the form convention:
    * present as "on" when ticked, absent otherwise.
    */
  def onSubmit(name: String): Attr = SubmitAttr := name

  /** The opt-out for a subtree some other script owns (a chart, an embedded editor): eezo still
    * patches the marked element's own attributes but never its children (design/live.md §1.1 on
    * §4.6). The round-trip guarantee deliberately ends at this attribute.
    */
  val ignore: Attr = AttrName(Differ.IgnoreAttr) := ""

  /** Mounts `component` as a live page: the returned fragment goes wherever the handler's own
    * layout puts it. At the registry's cap the component is rendered once, statically, and the page
    * says so in an attribute the client logs — a busy site degrades to working pages that do not
    * update, never to errors (design/live.md §2.5).
    *
    * `request` is the request being answered, and the one thing read off it is `currentUser`: a
    * page rendered behind a guarded route is bound to that user and admits only their socket
    * (docs/live.md §7). There is no request free overload, because a page that forgot to say who
    * rendered it would quietly stay unbound, and that has to be a compile error.
    */
  def mount[S](request: Request, component: Component[S]): Html =
    mount(request, (_: Async[S]) => component)

  /** The factory overload for a component that calls out: the [[Async]] it is built with is the
    * page's own, so work it declares comes back through this page's mailbox. Wired through a relay
    * because the page cannot exist before its component does; the relay is aimed before `init`
    * runs, so even a fetch-at-mount lands.
    */
  def mount[S](request: Request, create: Async[S] => Component[S]): Html =
    registry.register(
      request.currentUser,
      id => {
        val relay = new Relay[S]
        val page  = new Page(id, create(new Async[S](relay.post)))
        relay.aim(page)
        page
      }
    ) match {
      case Some(page) =>
        try {
          val tree = page.mount()
          anchor(tree, PageAttr := page.id) ++ script
        } catch {
          case e: Throwable =>
            registry.close(page.id)
            throw e
        }
      case None =>
        log.log(
          System.Logger.Level.WARNING,
          s"live page registry at capacity (${PageRegistry.DefaultCap}); serving a static render"
        )
        // A throwaway instance with a no-op Async: whatever it starts has nowhere to land,
        // which is exactly what a static render means.
        anchor(staticRender(create(new Async[S](_ => ()))), DeadAttr := "capacity")
    }

  /** What [[io.eezo.http.HttpApp.frameworkRoutes]] carries when `LiveApp` is mixed in: the one
    * socket every page joins, and the client script. Both under the reserved prefix, both
    * unmounted, both absent from `eezo routes` (they are the framework's, not the user's) though
    * present in the boot listing, which shows what is actually served.
    *
    * `allowedOrigins` is the one thing the socket needs from the application, which is why this is
    * a method with no parameterless twin: a twin would let `LiveApp` drop an application's list
    * without a compile error. Empty means a socket is admitted from the server's own origin only.
    */
  def routes(allowedOrigins: Set[String]): Seq[Route] = {
    val allowed = allowedOrigins.map(Origins.listed)
    Seq(
      Route.Ws(PathPattern.parse(s"${Eezo.ReservedPrefix}/live/:page"), endpoint(allowed, _)),
      Route.Http(
        Method.GET,
        PathPattern.parse(s"${Eezo.ReservedPrefix}/live.js"),
        _ =>
          Response(
            200,
            Seq(
              "Content-Type" -> "text/javascript; charset=utf-8",
              // Revalidate every load: the script changes with the framework, and a browser holding
              // a stale copy across a dev rebuild is a debugging session that blames the wrong code.
              "Cache-Control" -> "no-cache"
            ),
            Body.Bytes(clientJs)
          )
      )
    )
  }

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
  private def endpoint(allowed: Set[Origins.Normalised], request: Request): WsListener =
    new WsListener {

      // The current user is read off the upgrade once, when this listener is built, and compared
      // once, when the socket opens.
      private val id       = request.pathParams.getOrElse("page", "")
      private val who      = request.currentUser
      private val originOk = Origins.admits(request, allowed)

      private var attached: Option[Page[?]] = None

      override def onOpen(conn: WsConn): Unit =
        if (!originOk) {
          val received = Origins.shown(request.header("Origin").getOrElse(""))
          val served   = Origins.served(request).fold("unknown, no usable Host")(Origins.shown)
          log.log(
            System.Logger.Level.WARNING,
            s"page $id: upgrade refused, origin $received not allowed, this server is $served; " +
              "a proxy that rewrites Host should forward it (and one that terminates TLS should " +
              "send X-Forwarded-Proto), or add the origin to LiveApp.allowedOrigins"
          )
          conn.close(4403, s"origin $received not allowed")
        } else {
          registry.connect(id, who) match {
            case Left(PageRegistry.ConnectRefusal.Unknown) => conn.close(4404, "unknown page")
            case Left(PageRegistry.ConnectRefusal.AlreadyConnected) =>
              conn.close(4409, "page already connected")
            // Expired, signed out, or a stranger: the registry cannot tell which.
            case Left(PageRegistry.ConnectRefusal.NotTheUser) =>
              log.log(
                System.Logger.Level.WARNING,
                s"page $id: upgrade refused, not the page's user"
              )
              conn.close(4403, "not signed in as the page's user")
            case Right(page) =>
              page.attach(patches =>
                try conn.send(Wire.patches(patches))
                catch {
                  case e: Exception =>
                    // A stalled or vanished client. Closing fires onClose, which starts
                    // the grace window.
                    log.log(
                      System.Logger.Level.WARNING,
                      s"page $id: send failed (${e.getMessage}); closing the connection"
                    )
                    page.detach()
                    conn.close(1011, "send failed")
                }
              )
              attached = Some(page)
          }
        }

      override def onText(conn: WsConn, text: String): Unit =
        attached.foreach { page =>
          Wire.read(text) match {
            case Left(problem) =>
              log.log(System.Logger.Level.WARNING, s"page $id sent a malformed frame: $problem")
              conn.send(Wire.error(problem))
            case Right(Wire.ClientMessage.Join(base)) =>
              // The reported base is the prefix the client's DOM was rewritten with; the resync
              // that follows carries the tree rebased to match, and starts the client from
              // whatever moved between mount and join (design/live.md §2.6).
              mountPrefix(base) match {
                case Some(prefix) => page.rebase(prefix)
                case None         =>
                  log.log(
                    System.Logger.Level.WARNING,
                    s"page $id sent a dubious base '$base'; ignored"
                  )
              }
              page.resync()
            case Right(Wire.ClientMessage.Emit(event)) =>
              try page.event(event)
              catch {
                case e: Exception =>
                  log.log(System.Logger.Level.ERROR, s"page $id event '${event.name}' failed", e)
                  conn.send(Wire.error(s"event '${event.name}' failed"))
              }
            case Right(Wire.ClientMessage.Ping)                   => conn.send(Wire.pong)
            case Right(Wire.ClientMessage.PatchesFailed(reasons)) =>
              // The correctness alarm (design/live.md §1.1 on §4.3): a patch the applier refused
              // means the two sides disagreed about the DOM. Loud in the log, healed by a resync.
              log.log(
                System.Logger.Level.ERROR,
                s"page $id refused ${reasons.size} patch(es): ${reasons.mkString("; ")} — resyncing"
              )
              page.resync()
          }
        }

      override def onClose(code: Int, reason: String): Unit =
        attached.foreach { page =>
          page.detach()
          // 1000 is the client saying goodbye on purpose (beforeunload); everything else gets the
          // grace window, reaped on the cadence below.
          if (code == 1000) registry.close(page.id)
          else registry.disconnect(page.id)
        }

      override def onError(cause: Throwable): Unit =
        attached.foreach { page =>
          page.detach()
          registry.disconnect(page.id)
        }
    }

  /** The join's base report, validated down to a path this process would itself have written:
    * `Response.under` only ever produces normalised absolute paths, so anything else is a client
    * inventing things. `None` is "ignore it", never an error - the page then keeps "/".
    */
  private def mountPrefix(base: String): Option[String] =
    Option.when(base.length <= 200 && base.matchesSafePrefix)(io.eezo.core.html.Url.normalise(base))

  extension (base: String) {
    private def matchesSafePrefix: Boolean = base.matches("/[A-Za-z0-9_./-]*")
  }

  /** Breaks the page-needs-component-needs-async cycle at mount: transitions posted before the
    * relay is aimed can only come from the component's constructor, which is code running before
    * its own page exists, and they are dropped as such.
    */
  private final class Relay[S] {

    @volatile private var target: Option[Page[S]] = None

    def aim(page: Page[S]): Unit = target = Some(page)

    def post(transition: S => S): Unit = target.foreach(_.post(transition))
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
          if (gone.nonEmpty)
            log.log(System.Logger.Level.INFO, s"reaped ${gone.size} live page(s)")
        }
      })
  }
}
