package io.eezo.http

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeoutException

import scala.jdk.CollectionConverters.*

import org.eclipse.jetty.server.Handler as JettyHandler
import org.eclipse.jetty.server.NetworkConnector
import org.eclipse.jetty.server.Request as JettyRequest
import org.eclipse.jetty.server.Response as JettyResponse
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.handler.GracefulHandler
import org.eclipse.jetty.util.Callback
import org.eclipse.jetty.util.thread.VirtualThreadPool
import org.eclipse.jetty.websocket.server.ServerUpgradeRequest
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse
import org.eclipse.jetty.websocket.server.WebSocketCreator
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler

/** A running eezo server, as [[HttpServer.start]] hands it back: where it listens, and the way
  * down.
  *
  * Two members a holder outside `modules/http` can reach, and no more, because those are the two
  * questions a holder has, and neither answer is a Jetty type. The `jetty` door below is the one
  * exception, and it stays inside this module.
  */
private[eezo] final class HttpServer private (
    /** The Jetty server underneath.
      *
      * A Jetty type never leaves `modules/http`: the handle exists so that nothing outside this
      * module names one. This door is an accepted debt, kept for `EezoServerSuite` and
      * `DrainSuite`, which check the configuration research asked for and time the drain against
      * Jetty's own stop, two things no public behaviour shows.
      */
    private[http] val jetty: Server
) {

  /** Read back rather than remembered, because a caller that asked for port 0 needs the one the
    * kernel picked.
    */
  def port: Int =
    jetty.getConnectors.iterator
      .collectFirst { case bound: NetworkConnector => bound.getLocalPort }
      .getOrElse(throw new IllegalStateException("the server has no network connector"))

  /** eezo's stop rather than Jetty's, so a holder can neither skip the drain nor meet the exception
    * Jetty reports a cut short one with.
    */
  def stop(): Unit = HttpServer.drainAndStop(jetty)
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
  * The table is an abstract member of the trait rather than something found by reflection, because
  * a route transformation such as `under("/admin")` needs somewhere to be applied, and because "the
  * sbt plugin is not enabled" should be a compile error at the user's `Main` rather than a runtime
  * message.
  *
  * The framework brings the server up and down. [[run]] blocks until the server stops, and the
  * server stops on the JVM's shutdown, so a SIGTERM unwinds `run`, `HttpApp.serve` returns, and
  * whatever wrapped it (the database edge's `withDatabase`) runs its `finally`. The server drains
  * before any of that: it refuses new requests and lets the ones in flight finish, for up to
  * [[StopTimeout]], so the database closes after the last request has been answered rather than
  * under one still inside a transaction. [[stop]] is the same path, drain included, for a test that
  * started a server through `run` and wants it back down.
  */
private[eezo] object HttpServer {

  private val log = System.getLogger("io.eezo.http")

  /** How long the shutdown hook waits for [[run]]'s caller to unwind once the server is stopped:
    * the database edge closes its `Database` in that window. The same ten seconds the sbt plugin's
    * `DevProcess.stop` gives the child before `destroyForcibly`.
    */
  private val UnwindTimeout: Duration = Duration.ofSeconds(10)

  /** How long `stop` lets requests in flight finish before it closes their connections anyway.
    *
    * It must stay below [[UnwindTimeout]]. The hook spends the drain first and starts waiting on
    * the caller only after it, so a shutdown costs the two added together, and the process is
    * killed on a clock that is not ours: ten seconds after the stop in dev, the window
    * [[UnwindTimeout]] is named after, and five on Fly unless its `kill_timeout` says otherwise. A
    * drain that ran into either kill would skip `withDatabase`'s `finally`, which is the one thing
    * it exists to protect. Three seconds leaves the unwind room inside the shorter of the two. It
    * covers a request that is slow, not one that is stuck, and [[drainAndStop]] logs it when a stop
    * cuts one off.
    */
  val StopTimeout: Duration = Duration.ofSeconds(3)

  /** How long a connection may sit silent once the drain has begun before it is closed.
    *
    * The same silence ends two very different connections: one a browser keeps open between
    * requests, which only holds the stop, and one whose client pauses mid upload or mid download,
    * which is a request in flight the drain exists to finish. A production stop is a deploy, where
    * the client is anyone on any network and a second of waiting costs nobody, so it keeps Jetty's
    * own second. A dev stop is the restart on every save, where the browser's idle connection would
    * add that second to each reload and the one client is on the loopback and never pauses, so it
    * gets a tenth of that. Silence does not fail a handler that computes without touching the
    * connection: a request is read whole before its handler runs.
    */
  def shutdownIdleTimeout(dev: Boolean): Duration =
    if (dev) Duration.ofMillis(100) else Duration.ofSeconds(1)

  /** The server [[run]] is joining, if any, so that [[stop]] and the shutdown hook can reach it.
    * One slot, not a set: `run` is what `main` ends in, once per process. A server from [[start]]
    * never lands here: its holder stops it through the handle.
    */
  @volatile private var running: Option[HttpServer] = None

  /** The health endpoint, answered by the framework on every eezo server, dev and production alike.
    * Deliberately the cheapest possible truth (the server is accepting and answering requests),
    * because a deploy platform's checker and `eezo deploy`'s post-deploy poll both ask it every few
    * seconds, and a health check that touches the database turns a database blip into a restart
    * loop. It lives under [[RouteTable.ReservedPrefix]] and is asked before the user's table, like
    * the reload endpoint: no route can shadow it, no mount rewrites it, and it never appears in the
    * boot listing.
    */
  val HealthPath: String = s"${RouteTable.ReservedPrefix}/health"

  private val healthy: Response =
    Response(200, Seq("Content-Type" -> "text/plain; charset=utf-8"), Body.Bytes("ok".getBytes))

  /** Boots the server and blocks until it stops.
    *
    * `HttpApp.serve` is its one production caller, by convention rather than by visibility: the
    * object has one width, so the suites that drive `run` and the entry trait see the same member.
    *
    * The server comes down with the JVM: a shutdown hook stops it, draining the requests in flight
    * first, which returns `join`, and then waits up to [[UnwindTimeout]] for the calling thread to
    * finish, so the caller's `finally` blocks run before the process exits and after the last
    * request they could have been serving. The hook is removed again when `run` returns any other
    * way, and the `IllegalStateException` the removal throws during a shutdown is the case where
    * the hook is what returned `join`.
    */
  def run(port: Int, routes: RouteTable, config: HttpConfig = HttpConfig()): Unit = {
    val server = new HttpServer(build(port, routes, config))
    val caller = Thread.currentThread()
    val hook   = new Thread(
      () =>
        try server.stop()
        finally caller.join(UnwindTimeout.toMillis),
      "eezo-shutdown"
    )
    Runtime.getRuntime.addShutdownHook(hook)
    // Published before the server starts, so a `stop` that races the first request finds it.
    running = Some(server)
    try {
      server.jetty.start()
      server.jetty.join()
    } finally {
      running = None
      try Runtime.getRuntime.removeShutdownHook(hook): Unit
      catch { case _: IllegalStateException => () }
    }
  }

  /** Stops the server [[run]] is joining, so that `run` returns. Nothing if none is running, which
    * includes a server from [[start]]: that one is stopped through its handle.
    */
  def stop(): Unit = running.foreach(_.stop())

  /** The one way eezo stops a server, for the hook, [[stop]] and the handle alike.
    *
    * Jetty brings the server down whatever the drain did, and only then reports a drain that
    * [[StopTimeout]] cut short, by throwing. That is a line for the log rather than a failure: the
    * hook still has the caller to wait for, and the caller's `finally` is the point of the whole
    * sequence.
    */
  private def drainAndStop(server: Server): Unit =
    try server.stop()
    catch {
      case _: TimeoutException =>
        log.log(
          System.Logger.Level.WARNING,
          s"requests still in flight ${StopTimeout.toSeconds} seconds after the stop began were cut off"
        )
    }

  /** Boots the server and returns it, still running: [[build]] and then `start`, for a caller that
    * holds the handle itself and stops it through that handle.
    */
  def start(port: Int, routes: RouteTable, config: HttpConfig = HttpConfig()): HttpServer = {
    val server = new HttpServer(build(port, routes, config))
    server.jetty.start()
    server
  }

  /** The server, configured and announced but not started.
    *
    * The configuration below is `research/http-server.md` section 12, and every override in it is
    * an override of a Jetty default the research measured and calls a defect: a thread pool that is
    * not virtual-thread-native, a 30 second WebSocket idle timeout, a 64 KiB text message cap, and
    * an unbounded outgoing frame queue that grew a single stalled connection to 293.6 MiB of heap.
    */
  private def build(port: Int, routes: RouteTable, config: HttpConfig): Server = {
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
        container.addMapping("/*", creator(routes, config))
      }
    )
    upgrade.setHandler(new EezoHandler(routes, config))
    // Outermost, so every request is counted from the start, and an upgrade that arrives during the
    // drain is refused like any other request. A socket stops counting once its handshake is
    // written, and Jetty closes the open ones with 1001 as the drain begins, so a live page never
    // holds the stop open.
    val graceful = new GracefulHandler(upgrade)
    graceful.setShutdownIdleTimeout(shutdownIdleTimeout(config.dev).toMillis)
    server.setHandler(graceful)
    server.setStopTimeout(StopTimeout.toMillis)

    announce(routes, config)
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
  private def announce(routes: RouteTable, config: HttpConfig): Unit = {
    RouteReport.warnings(routes).foreach(warning => log.log(System.Logger.Level.WARNING, warning))
    if (config.dev) log.log(System.Logger.Level.INFO, RouteReport.listing(routes.routes))
  }

  /** What a failure is answered with, on the HTTP path and on a refused upgrade alike, so that a
    * 500 out of an endpoint is logged exactly as one out of a handler is.
    *
    * Resolved once: the log decision and the response both read off this single value, rather than
    * each re-matching the failure to ask its own question of it.
    */
  private def answer(failure: Throwable, path: String, config: HttpConfig): Response = {
    val resolution = Boundary.resolve(failure, path, config)
    if (Boundary.logsStackTrace(resolution.problem.status))
      log.log(System.Logger.Level.ERROR, s"${resolution.problem.status} on $path", failure)
    Boundary.toResponse(resolution)
  }

  /** The single WebSocket creator.
    *
    * Jetty documents that a creator returning `null` "is responsible for completing the Callback
    * and sending a response", so an upgrade request matching no `Route.Ws` is answered here with a
    * 404 rather than falling through to the HTTP handler. An endpoint that throws while being
    * built, a guard refusing with `Forbidden` for one, is answered through the boundary the same
    * way, since this creator runs outside `EezoHandler` and Jetty's own 500 page would name the
    * exception. That is also what lets the refusal reach the client at all: it is an ordinary
    * status off the boundary, which a socket client can read, rather than a handshake failure of
    * its own that Jetty would hide.
    *
    * The endpoint's request carries the session the handshake's cookie did, read the way the HTTP
    * handler reads it, but with its flash stripped: see [[readHandshake]] for why. Nothing is
    * written back either way: an upgrade has no response a cookie could ride on.
    */
  private def creator(routes: RouteTable, config: HttpConfig): WebSocketCreator =
    (request: ServerUpgradeRequest, response: ServerUpgradeResponse, callback: Callback) => {
      val path = JettyRequest.getPathInContext(request)

      // The reload endpoint is asked first, before the user's table, so no route can shadow it, no
      // mount rewrites it, and it never appears in the boot listing. `Reload` owns the dev gate.
      def refuse(failure: Throwable): Null = {
        write(response, answer(failure, path, config), callback)
        null
      }

      try
        Reload
          .listenerFor(path, config)
          .orElse {
            routes.dispatchWs(path).map { (route, params) =>
              // The table names the current user on the upgrade after the session has been read
              // and before the endpoint is built, which is the only order that works: the naming
              // reads `request.session`, so running it first would make every upgrade anonymous
              // with nothing to say so, and running it after the endpoint would be too late for
              // the endpoint to read.
              route.endpoint(
                routes.identify(
                  readHandshake(
                    requestOf(request, Method.GET, path, Array.emptyByteArray, params),
                    config.secret
                  )
                )
              )
            }
          }
          .map(new JettyListener(_))
          .getOrElse(refuse(NotFound(path)))
      catch { case failure: Throwable => refuse(failure) }
    }

  /** The handshake's request with its session read, minus the flash.
    *
    * An upgrade has no response a cookie could ride on, so a flash handed to the endpoint here
    * could never be swept the way `EezoHandler` sweeps one on the HTTP path, and the browser would
    * carry it into the next request too, delivering it twice. Entries are unaffected: nothing
    * sweeps them either, on a WebSocket or on HTTP, so they travel the same way on both.
    */
  private def readHandshake(request: Request, secret: Secret): Request = {
    val carried = SessionCookie.read(request, secret)
    carried.copy(session = carried.session.copy(delivered = Map.empty))
  }

  /** eezo's HTTP handler: one completion site, reached unconditionally.
    *
    * `readRequest` sits inside the `try` because it is what throws `PayloadTooLarge` and
    * `NotImplemented`; `Boundary.errorResponse` is total by construction, so it has nothing left to
    * throw; and `write` is the only function in eezo that touches Jetty's `Callback`. `handle`
    * always returns `true`, because an unmatched route throws `NotFound` here rather than falling
    * through to Jetty's own error page.
    *
    * The session cookie is written here, once, on the success path: the session the response names,
    * or else the one the request carried into the handler, and only when it differs from what
    * arrived. Dispatch mints a CSRF token into a session that has none, so a first visit differs
    * and writes one `Set-Cookie` even when the handler names no session. A failure that reached the
    * boundary writes no cookie, so an error page leaves the browser's session, flash included,
    * exactly as it was.
    */
  private final class EezoHandler(routes: RouteTable, config: HttpConfig)
      extends JettyHandler.Abstract {

    override def handle(
        request: JettyRequest,
        response: JettyResponse,
        callback: Callback
    ): Boolean = {
      val path = JettyRequest.getPathInContext(request)

      val result =
        try {
          val incoming = readRequest(request, path, config.maxBodySize)
          // Health is answered before the session is read: a probe carries no cookie and wants
          // none back, and the endpoint's whole point is to cost nothing.
          if (incoming.method == Method.GET && path == HealthPath) healthy
          else {
            val read = SessionCookie.read(incoming, config.secret)
            SessionCookie.write(read, routes.dispatch(read), config.secret)
          }
        } catch {
          case failure: Throwable => answer(failure, path, config)
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
    Request.withMethodOverride(requestOf(request, method, path, body, Map.empty))
  }

  /** The one place a Jetty request becomes eezo's, for the HTTP handler and the WebSocket creator
    * alike, so both agree on the headers and on whether the browser used HTTPS.
    */
  private def requestOf(
      request: JettyRequest,
      method: Method,
      path: String,
      body: Array[Byte],
      pathParams: Map[String, String]
  ): Request = {
    val headers = headersOf(request)
    Request(
      method = method,
      path = path,
      query = queryOf(request),
      headers = headers,
      body = body,
      pathParams = pathParams,
      secure = Request.isSecure(request.isSecure, headers)
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
