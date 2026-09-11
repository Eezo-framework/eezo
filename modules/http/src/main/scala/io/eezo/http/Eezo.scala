package io.eezo.http

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration

import scala.jdk.CollectionConverters.*

import org.eclipse.jetty.server.Handler as JettyHandler
import org.eclipse.jetty.server.Request as JettyRequest
import org.eclipse.jetty.server.Response as JettyResponse
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.Callback
import org.eclipse.jetty.util.thread.VirtualThreadPool
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.server.ServerUpgradeRequest
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse
import org.eclipse.jetty.websocket.server.WebSocketCreator
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler

/** A size, so that a byte count in a signature reads as one.
  *
  * The body cap and the WebSocket text message cap are deliberately the same number, so there is
  * one limit to remember rather than two.
  */
extension (n: Int) {
  def MiB: Long = n.toLong * 1024 * 1024
}

/** The server-wide set: the route table and the three settings that travel everywhere it is
  * dispatched from, bundled so `run`, `start`, the WebSocket creator and `EezoHandler` pass one
  * value instead of four.
  */
private[http] final case class Config(
    routes: RouteTable,
    maxBodySize: Long = Config.DefaultMaxBodySize,
    dev: Boolean = Config.DefaultDev,
    problems: PartialFunction[Throwable, Problem] = Config.DefaultProblems
)

private[http] object Config {

  /** The one place each of `Eezo.run`'s three optional defaults is stated. `run`'s own parameter
    * defaults read off these, so changing a default is one edit rather than two.
    */
  private[http] val DefaultMaxBodySize: Long                             = 1.MiB
  private[http] val DefaultDev: Boolean                                  = false
  private[http] val DefaultProblems: PartialFunction[Throwable, Problem] = PartialFunction.empty
}

/** Booting eezo.
  *
  * Nothing in user code calls this object. The entry point is `HttpApp`, which names the route
  * table and inherits `main`:
  *
  * ```scala
  * object Main extends HttpApp {
  *   override def routes: RouteTable = Routes.table()
  * }
  * ```
  *
  * `HttpApp.serve` is the one caller of [[run]], and [[run]] is `private[eezo]` so that stays true
  * by visibility rather than by convention. The table is an abstract member of the trait rather
  * than something found by reflection, because a route transformation such as `under("/admin")`
  * needs somewhere to be applied, and because "the sbt plugin is not enabled" should be a compile
  * error at the user's `Main` rather than a runtime message.
  *
  * The framework brings the server up and down. [[run]] blocks until the server stops, and the
  * server stops on the JVM's shutdown, so a SIGTERM unwinds `run`, `HttpApp.serve` returns, and
  * whatever wrapped it (the database edge's `withDatabase`) runs its `finally`. [[stop]] is the
  * same path for a test that started a server through `run` and wants it back down.
  */
object Eezo {

  private val log = System.getLogger("io.eezo.http")

  /** How long the shutdown hook waits for [[run]]'s caller to unwind once the server is stopped:
    * the database edge closes its `Database` in that window. The same ten seconds the sbt plugin's
    * `DevProcess.stop` gives the child before `destroyForcibly`.
    */
  private val UnwindTimeout: Duration = Duration.ofSeconds(10)

  /** The server [[run]] is joining, if any, so that [[stop]] and the shutdown hook can reach it.
    * One slot, not a set: `run` is what `main` ends in, once per process.
    */
  @volatile private var running: Option[Server] = None

  /** The path prefix reserved for the framework's own routes: the reload endpoint here, and the dev
    * server's drift actions in `modules/eezo`. One spelling, so a new framework route is added
    * under it rather than beside it.
    */
  private[eezo] val ReservedPrefix: String = "/eezo"

  /** The health endpoint, answered by the framework on every eezo server, dev and production alike.
    * Deliberately the cheapest possible truth (the server is accepting and answering requests),
    * because a deploy platform's checker and `eezo deploy`'s post-deploy poll both ask it every few
    * seconds, and a health check that touches the database turns a database blip into a restart
    * loop. It lives under [[ReservedPrefix]] and is asked before the user's table, like the reload
    * endpoint: no route can shadow it, no mount rewrites it, and it never appears in the boot
    * listing.
    */
  private[eezo] val HealthPath: String = s"$ReservedPrefix/health"

  private val healthy: Response =
    Response(200, Seq("Content-Type" -> "text/plain; charset=utf-8"), Body.Bytes("ok".getBytes))

  /** Boots the server and blocks until it stops.
    *
    * The server comes down with the JVM: a shutdown hook stops it, which returns `join`, and then
    * waits up to [[UnwindTimeout]] for the calling thread to finish, so the caller's `finally`
    * blocks run before the process exits. The hook is removed again when `run` returns any other
    * way, and the `IllegalStateException` the removal throws during a shutdown is the case where
    * the hook is what returned `join`.
    */
  private[eezo] def run(
      port: Int,
      routes: RouteTable,
      maxBodySize: Long = Config.DefaultMaxBodySize,
      dev: Boolean = Config.DefaultDev,
      problems: PartialFunction[Throwable, Problem] = Config.DefaultProblems
  ): Unit = {
    val server = build(port, Config(routes, maxBodySize, dev, problems))
    val caller = Thread.currentThread()
    val hook   = new Thread(
      () => {
        server.stop()
        caller.join(UnwindTimeout.toMillis)
      },
      "eezo-shutdown"
    )
    Runtime.getRuntime.addShutdownHook(hook)
    // Published before the server starts, so a `stop` that races the first request finds it.
    running = Some(server)
    try {
      server.start()
      server.join()
    } finally {
      running = None
      try Runtime.getRuntime.removeShutdownHook(hook): Unit
      catch { case _: IllegalStateException => () }
    }
  }

  /** Stops the server [[run]] is joining, so that `run` returns. Nothing if none is running. */
  private[eezo] def stop(): Unit = running.foreach(_.stop())

  /** Boots the server and returns it, still running: [[build]] and then `start`, for a suite that
    * holds the handle itself.
    */
  private[http] def start(port: Int, config: Config): Server = {
    val server = build(port, config)
    server.start()
    server
  }

  /** The server, configured and announced but not started.
    *
    * The configuration below is `research/http-server.md` section 12, and every override in it is
    * an override of a Jetty default the research measured and calls a defect: a thread pool that is
    * not virtual-thread-native, a 30 second WebSocket idle timeout, a 64 KiB text message cap, and
    * an unbounded outgoing frame queue that grew a single stalled connection to 293.6 MiB of heap.
    */
  private def build(port: Int, config: Config): Server = {
    val pool = new VirtualThreadPool()
    // No semaphore ceiling. The pool's default caps concurrent tasks, which reintroduces the
    // queueing that virtual threads exist to remove.
    pool.setMaxConcurrentTasks(0)

    val server    = new Server(pool)
    val connector = new ServerConnector(server)
    connector.setPort(port)
    server.addConnector(connector)

    val upgrade = WebSocketUpgradeHandler.from(
      server,
      container => {
        container.setIdleTimeout(Duration.ofMinutes(5)) // not the 30 second default
        container.setMaxTextMessageSize(1.MiB)          // not the 64 KiB default
        container.setMaxOutgoingFrames(64)              // not the unbounded default
        // Exactly one mapping. eezo matches WebSocket paths with its own `PathPattern`, because
        // Jetty's path spec grammar cannot express a pattern mixing `:name` and a catch-all, and a
        // second matcher would disagree with the first in ways users find before tests do.
        container.addMapping("/*", creator(config))
      }
    )
    upgrade.setHandler(new EezoHandler(config))
    server.setHandler(upgrade)

    announce(config)
    server
  }

  /** What boot says about the table it is about to serve.
    *
    * The warnings are unconditional, because a shadowed route, a derived route a handwritten one
    * replaced and a form page with no submit target are all worth a line in production, and the
    * flag that would hide them is the one nobody sets there. Three lines about the same table,
    * since one names a route that can never match, one names a route that is no longer mounted at
    * all, and one names a page that renders and answers 405 the moment it is submitted, and a user
    * chasing a page that is not the page they expected, or a form that will not send, needs to be
    * told which of the three happened. The listing below is the assembled table after the
    * replacement, so a route named in the override warning is deliberately absent from it. The
    * listing is not unconditional: it is a development convenience, and it earns its place because
    * a typo'd `derives Resorce` mounts nothing in silence, which makes an empty or short table the
    * only symptom a user ever sees.
    */
  private def announce(config: Config): Unit = {
    config.routes.overridden.foreach { route =>
      log.log(
        System.Logger.Level.WARNING,
        s"${route.describe} is written by hand and also derived; the handwritten route is " +
          "served and the derived one is not mounted."
      )
    }

    config.routes.shadowed.foreach { case (earlier, later) =>
      log.log(
        System.Logger.Level.WARNING,
        s"${earlier.describe} shadows ${later.describe}, which can never match. " +
          "Routes are tried in table order; move the narrower route first."
      )
    }

    Resource.orphaned(config.routes).foreach { orphan =>
      log.log(
        System.Logger.Level.WARNING,
        s"${orphan.pageRoute} is mounted without ${orphan.targetRoute}: the page renders a form " +
          "whose submit target is not mounted, so submitting it answers 405. Mount " +
          s"${orphan.target}, or subtract ${orphan.page} as well."
      )
    }

    if (config.dev) {
      val routes  = config.routes.routes
      val listing =
        if (routes.isEmpty) "no routes mounted"
        else {
          val heading = if (routes.size == 1) "1 route:" else s"${routes.size} routes:"
          routes.map(route => s"  ${route.describe}").mkString(s"$heading\n", "\n", "")
        }
      log.log(System.Logger.Level.INFO, listing)
    }
  }

  /** The single WebSocket creator.
    *
    * Jetty documents that a creator returning `null` "is responsible for completing the Callback
    * and sending a response", so an upgrade request matching no `Route.Ws` is answered here with a
    * 404 rather than falling through to the HTTP handler.
    */
  private def creator(config: Config): WebSocketCreator =
    (request: ServerUpgradeRequest, response: ServerUpgradeResponse, callback: Callback) => {
      val path = JettyRequest.getPathInContext(request)

      // The reload endpoint is asked first, before the user's table, so no route can shadow it, no
      // mount rewrites it, and it never appears in the boot listing. `Reload` owns the dev gate.
      Reload
        .listenerFor(path, config)
        .orElse {
          config.routes.dispatchWs(path).map { (route, params) =>
            route.endpoint(
              Request(
                method = Method.GET,
                path = path,
                query = queryOf(request),
                headers = headersOf(request),
                body = Array.emptyByteArray,
                pathParams = params
              )
            )
          }
        }
        .map(new JettyListener(_))
        .getOrElse {
          write(response, Boundary.errorResponse(NotFound(path), path, config), callback)
          null
        }
    }

  /** eezo's HTTP handler: one completion site, reached unconditionally.
    *
    * `readRequest` sits inside the `try` because it is what throws `PayloadTooLarge` and
    * `NotImplemented`; `Boundary.errorResponse` is total by construction, so it has nothing left to
    * throw; and `write` is the only function in eezo that touches Jetty's `Callback`. `handle`
    * always returns `true`, because an unmatched route throws `NotFound` here rather than falling
    * through to Jetty's own error page.
    */
  private final class EezoHandler(config: Config) extends JettyHandler.Abstract {

    override def handle(
        request: JettyRequest,
        response: JettyResponse,
        callback: Callback
    ): Boolean = {
      val path = JettyRequest.getPathInContext(request)

      val result =
        try {
          val incoming = readRequest(request, path, config.maxBodySize)
          if (incoming.method == Method.GET && path == HealthPath) healthy
          else config.routes.dispatch(incoming)
        } catch {
          case failure: Throwable =>
            // Resolved once: the log decision and the response both read off this single value,
            // rather than each re-matching the failure to ask its own question of it.
            val resolution = Boundary.resolve(failure, path, config)
            if (Boundary.logsStackTrace(resolution.problem.status))
              log.log(System.Logger.Level.ERROR, s"${resolution.problem.status} on $path", failure)
            Boundary.toResponse(resolution)
        }

      // Every HTTP response passes here, success or failure, so this is where the dev server adds
      // the reload client: `Boundary` stays the failure boundary and does not grow a response
      // filter. A refused upgrade is answered in `creator` and is not a page, so it skips this.
      write(response, Reload.inject(result, config), callback)
      true
    }
  }

  /** Reads one request, whole, with the body capped.
    *
    * The cap is enforced by reading one byte past it and refusing: a `Content-Length` a client
    * controls is not a limit, and a stream nobody drains is not an answer either.
    */
  private def readRequest(request: JettyRequest, path: String, maxBodySize: Long): Request = {
    val method = Method
      .parse(request.getMethod)
      .getOrElse(throw NotImplemented(request.getMethod))

    val stream = JettyRequest.asInputStream(request)
    val body   =
      try stream.readNBytes(Math.toIntExact(Math.min(maxBodySize + 1, Int.MaxValue.toLong)))
      finally stream.close()
    if (body.length > maxBodySize) throw PayloadTooLarge(maxBodySize)

    // The override is applied here, so that dispatch and every handler downstream see the verb the
    // form asked for rather than the `POST` a browser was able to issue.
    Request.withMethodOverride(
      Request(
        method = method,
        path = path,
        query = queryOf(request),
        headers = headersOf(request),
        body = body,
        pathParams = Map.empty
      )
    )
  }

  /** Writes the response. The sole caller of `succeeded()` and `failed()` in eezo. */
  private def write(
      response: JettyResponse,
      value: Response,
      callback: Callback
  ): Unit = {
    response.setStatus(value.status)
    value.headers.foreach { case (name, headerValue) =>
      response.getHeaders.add(name, Response.renderUrl(headerValue))
    }

    val bytes = value.body match {
      case Body.Bytes(raw) => raw
      case Body.Html(html) => html.render.getBytes(StandardCharsets.UTF_8)
      case Body.Empty      => Array.emptyByteArray
    }
    response.write(true, ByteBuffer.wrap(bytes), callback)
  }

  private def queryOf(request: JettyRequest): Map[String, Seq[String]] = {
    val fields = JettyRequest.extractQueryParameters(request)
    fields.getNames.asScala.map(name => name -> fields.getValuesOrEmpty(name).asScala.toSeq).toMap
  }

  private def headersOf(request: JettyRequest): Map[String, Seq[String]] = {
    val headers = request.getHeaders
    headers.getFieldNamesCollection.asScala
      .map(name => name -> headers.getValuesList(name).asScala.toSeq)
      .toMap
  }
}

/** Jetty's listener, adapted to eezo's.
  *
  * The runtime demands the next event after each one is fully handled, so a listener that forgets
  * to demand — a socket that silently stops — is not a mistake an application can make.
  *
  * It extends `Session.Listener.Abstract`, the class, rather than the interface. Scala emits a
  * mixin forwarder for every default method an interface has, and Jetty binds its events by
  * reflecting over the methods a listener declares, so implementing the interface directly makes
  * Jetty see two handlers for the same event and refuse the connection. `Abstract` rather than
  * `AbstractAutoDemanding`, because the demand below is eezo's read backpressure and not a
  * formality.
  */
private final class JettyListener(listener: WsListener) extends Session.Listener.Abstract {

  private var session: Session = null

  override def onWebSocketOpen(newSession: Session): Unit = {
    session = newSession
    listener.onOpen(WsConn(newSession))
    newSession.demand()
  }

  override def onWebSocketText(text: String): Unit = {
    listener.onText(WsConn(session), text)
    session.demand()
  }

  override def onWebSocketClose(status: Int, reason: String): Unit =
    listener.onClose(status, reason)

  override def onWebSocketError(cause: Throwable): Unit = listener.onError(cause)
}
