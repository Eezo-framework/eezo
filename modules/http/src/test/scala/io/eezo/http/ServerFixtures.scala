package io.eezo.http

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

import scala.jdk.CollectionConverters.*

import io.eezo.core.html.{Attrs, body as bodyTag, html}
import org.eclipse.jetty.server.Server

/** What a suite needs to drive a booted server the way a browser over the wire does: a server on an
  * ephemeral port, a session cookie carrying the CSRF token, and a `POST` under it that returns the
  * token the way a served form does.
  *
  * Mixed into a `FunSuite` rather than kept in an object because [[visit]] and [[send]] fail the
  * test when the server did not hand back what a browser needs, and failing is the suite's own
  * business.
  */
trait ServerFixtures { self: munit.FunSuite =>

  ServerFixtures.quietJetty()

  /** The address every request to the suite's own server goes to, spelled out so that the far end
    * check in [[serving]] and the requests it vouches for agree on it: `localhost` resolves to
    * either loopback address, and a stranger holds its port on one of them.
    */
  protected val Loopback: String = "127.0.0.1"

  /** The client requests go out on, never following a redirect, which is what a suite reading a
    * `Location` header itself wants. A suite that wants one followed overrides this.
    *
    * It waits on a silent far end for [[connectTimeout]] and no longer, as does every request
    * [[send]] makes for [[requestTimeout]]: a loopback port can be held by a process that never
    * answers, and a client with no bound then blocks the whole run rather than failing one test.
    */
  protected val client: HttpClient =
    HttpClient
      .newBuilder()
      .followRedirects(HttpClient.Redirect.NEVER)
      .connectTimeout(connectTimeout)
      .build()

  /** How long a connection may take to open. A loopback connect is answered at once or not at all,
    * so this is generous only against a loaded runner.
    */
  protected def connectTimeout: Duration = Duration.ofSeconds(10)

  /** How long a response may take to begin. Well past anything a suite's handler does on purpose,
    * and well inside a CI job's limit, so a request that is never answered fails its own test.
    */
  protected def requestTimeout: Duration = Duration.ofSeconds(30)

  /** The one secret both suites sign and verify against. */
  protected val secret: Secret = Secret.parse("server secret, at least thirty two bytes")

  /** Boots a server for one test and stops it afterwards. */
  protected def serving(
      routes: RouteTable,
      maxBodySize: Long = 1.MiB,
      dev: Boolean = false,
      layout: Layout = Layout.plain,
      problems: PartialFunction[Throwable, Problem] = HttpConfig.DefaultProblems
  )(
      body: (HttpServer, Int) => Unit
  ): Unit = {
    val server = reachable(routes, HttpConfig(maxBodySize, dev, problems, secret, layout))
    try body(server, server.port)
    finally server.stop()
  }

  /** A server started on port 0 whose port, on [[Loopback]], is its own.
    *
    * The server listens on every address, and the kernel will hand it a port another process
    * already holds on the loopback alone. A connection to the loopback then goes to that process,
    * the more specific listener, and the server never sees it: the suite reads the stranger's
    * greeting as its status line. Only the server's own connector can say which process answered,
    * by the far end of each connection it holds, so a server a probe never reached is stopped and
    * another started on the next port the kernel offers.
    */
  private def reachable(routes: RouteTable, config: HttpConfig, attempts: Int = 3): HttpServer = {
    val server = HttpServer.start(port = 0, routes = routes, config = config)
    val probe  = new Socket(Loopback, server.port)
    val ours   =
      try acceptedBy(server.jetty, probe)
      finally probe.close()
    if (ours) server
    else {
      server.stop()
      if (attempts > 1) reachable(routes, config, attempts - 1)
      else fail("every port the server was given was one another process holds on the loopback")
    }
  }

  /** Whether `socket` reached `server` or some other process that holds the same port, by the far
    * end of each connection the server's connectors hold.
    */
  private def acceptedBy(server: Server, socket: Socket): Boolean = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    def accepted = server.getConnectors.exists(
      _.getConnectedEndPoints.asScala.exists(_.getRemoteSocketAddress match {
        case far: InetSocketAddress => far.getPort == socket.getLocalPort
        case _                      => false
      })
    )
    while (!accepted && System.nanoTime() < deadline) Thread.onSpinWait()
    accepted
  }

  /** A layout that leaves `class="framed"` on every page it frames, so a suite can tell from the
    * bytes alone whether a reply went through the application's layout.
    */
  protected val marked: Layout = (_, _, content) => html(bodyTag(Attrs.cls := "framed", content))

  /** What a route's `content` goes out as in eezo's own frame, which is what every page that is not
    * a whole document comes back in when the application names no layout.
    */
  protected def plainly(content: String): String =
    s"""<!DOCTYPE html><html><head><meta charset="utf-8"></head><body>$content</body></html>"""

  /** A port nobody is listening on right now, for a server started through `run`, which binds the
    * port it is given and hands back nothing to ask.
    */
  protected def freePort(): Int = {
    val socket = new java.net.ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }

  /** `get`, retried while the server is still coming up. */
  protected def awaiting(port: Int, path: String): HttpResponse[String] = {
    val deadline                        = System.nanoTime() + 10_000_000_000L
    def attempt(): HttpResponse[String] =
      try get(port, path)
      catch {
        case _: java.io.IOException if System.nanoTime() < deadline =>
          Thread.sleep(50)
          attempt()
      }
    attempt()
  }

  /** A request over the loopback, the session cookie set when `cookie` is defined. */
  protected def send(
      port: Int,
      method: String,
      path: String,
      cookie: Option[String],
      headers: Seq[(String, String)] = Nil,
      form: Option[String] = None
  ): HttpResponse[String] = {
    val builder =
      HttpRequest.newBuilder(URI.create(s"http://$Loopback:$port$path")).timeout(requestTimeout)
    cookie.foreach(value => builder.header("Cookie", s"${SessionCookie.Name}=$value"))
    headers.foreach(builder.header(_, _))
    form.foreach(_ => builder.header("Content-Type", "application/x-www-form-urlencoded"))
    val request = method match {
      case "GET" => builder.GET()
      case other => builder.method(other, HttpRequest.BodyPublishers.ofString(form.getOrElse("")))
    }
    client.send(request.build(), HttpResponse.BodyHandlers.ofString())
  }

  /** A `GET` with no session cookie. */
  protected def get(port: Int, path: String): HttpResponse[String] = send(port, "GET", path, None)

  /** The session cookie a response set, read out of `Set-Cookie`. */
  protected def sessionCookie(response: HttpResponse[String]): Option[String] =
    response.headers().allValues("Set-Cookie").asScala.collectFirst {
      case header if header.startsWith(s"${SessionCookie.Name}=") =>
        header.takeWhile(_ != ';').stripPrefix(s"${SessionCookie.Name}=")
    }

  /** What a browser holds once it has a session: the cookie it was handed, and the token inside it.
    * A later response may replace the cookie; the token stays.
    */
  final case class Browser(cookie: String, token: Csrf.Token)

  /** A browser's first visit to a served page, over the wire: what a suite reaches for when its
    * table mounts a page to be handed the cookie on.
    */
  protected def visit(port: Int, path: String): Browser = {
    val cookie = sessionCookie(send(port, "GET", path, None))
      .getOrElse(fail(s"the first visit to $path was handed no session cookie"))
    val token = Csrf
      .read(SessionCookie.decode(cookie, secret))
      .getOrElse(fail(s"the cookie from the first visit to $path carries no token"))
    Browser(cookie, token)
  }

  /** A browser handed a cookie the server never wrote, signed by hand with [[secret]]: what a suite
    * reaches for when its table mounts no page to visit.
    */
  protected def handed(): Browser = {
    val token = Csrf.Token.gen()
    Browser(SessionCookie.encode(Csrf.carrying(Session.empty, token), secret), token)
  }

  /** A `POST` the way a form on a served page makes it: under the browser's session cookie, its
    * token appended to `form`, returning that same token.
    */
  protected def submit(
      port: Int,
      path: String,
      browser: Browser,
      form: String = "",
      headers: Seq[(String, String)] = Nil
  ): HttpResponse[String] = {
    val token   = s"${Csrf.Field}=${browser.token.value}"
    val carried = if (form.isEmpty) token else s"$form&$token"
    send(port, "POST", path, Some(browser.cookie), headers, form = Some(carried))
  }
}

object ServerFixtures {

  /** Jetty's loggers, held: `LogManager` keeps a logger only weakly, and a level set on one nobody
    * holds is gone with the next collection.
    */
  private val jetty: Logger = Logger.getLogger("org.eclipse.jetty")

  /** Jetty announces every start and stop at `INFO` through `java.util.logging`, the backend the
    * build binds SLF4J to, and a suite starts a server per test: the lines would bury the summary.
    * Only what Jetty warns about reaches the test output. Production logging is untouched, since
    * this runs in the test JVM alone.
    */
  def quietJetty(): Unit = jetty.setLevel(Level.WARNING)
}
