package eezo.http

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
  * The user writes the entry point, and names the route table in it:
  *
  * ```scala
  * import eezo.generated.Routes
  * @main def main(): Unit = Eezo.run(port = 8080, routes = Routes.table, dev = true)
  * ```
  *
  * The table is an ordinary parameter rather than something `run` finds by reflection, because a
  * route transformation such as `under("/admin")` needs somewhere to be applied, and because "the
  * sbt plugin is not enabled" should be a compile error at the user's `@main` rather than a runtime
  * message.
  */
object Eezo {

  private val log = System.getLogger("eezo.http")

  /** Boots the server and blocks until it stops. */
  def run(
      port: Int,
      routes: RouteTable,
      maxBodySize: Long = Config.DefaultMaxBodySize,
      dev: Boolean = Config.DefaultDev,
      problems: PartialFunction[Throwable, Problem] = Config.DefaultProblems
  ): Unit = {
    val server = start(port, Config(routes, maxBodySize, dev, problems))
    server.join()
  }

  /** Boots the server and returns it, still running.
    *
    * The configuration below is `research/http-server.md` section 12, and every override in it is
    * an override of a Jetty default the research measured and calls a defect: a thread pool that is
    * not virtual-thread-native, a 30 second WebSocket idle timeout, a 64 KiB text message cap, and
    * an unbounded outgoing frame queue that grew a single stalled connection to 293.6 MiB of heap.
    */
  private[http] def start(port: Int, config: Config): Server = {
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

    server.start()
    server
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

      config.routes.dispatchWs(path) match {
        case Some((route, params)) =>
          val upgradeRequest = Request(
            method = Method.GET,
            path = path,
            query = queryOf(request),
            headers = headersOf(request),
            body = Array.emptyByteArray,
            pathParams = params
          )
          new JettyListener(route.endpoint(upgradeRequest))

        case None =>
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
        try config.routes.dispatch(readRequest(request, path, config.maxBodySize))
        catch {
          case failure: Throwable =>
            // Resolved once: the log decision and the response both read off this single value,
            // rather than each re-matching the failure to ask its own question of it.
            val resolution = Boundary.resolve(failure, path, config)
            if (Boundary.logsStackTrace(resolution.problem.status))
              log.log(System.Logger.Level.ERROR, s"${resolution.problem.status} on $path", failure)
            Boundary.toResponse(resolution)
        }

      write(response, result, callback)
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

    Request(
      method = method,
      path = path,
      query = queryOf(request),
      headers = headersOf(request),
      body = body,
      pathParams = Map.empty
    )
  }

  /** Writes the response. The sole caller of `succeeded()` and `failed()` in eezo. */
  private def write(
      response: JettyResponse,
      value: Response,
      callback: Callback
  ): Unit = {
    response.setStatus(value.status)
    value.headers.foreach { case (name, headerValue) => response.getHeaders.add(name, headerValue) }

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
