package io.eezo.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

import scala.jdk.CollectionConverters.*

/** What a suite needs to drive a booted server the way a browser over the wire does: a server on an
  * ephemeral port, a session cookie carrying the CSRF token, and a `POST` under it that returns the
  * token the way a served form does.
  *
  * Mixed into a `FunSuite` rather than kept in an object because [[visit]] and [[send]] fail the
  * test when the server did not hand back what a browser needs, and failing is the suite's own
  * business.
  */
trait ServerFixtures { self: munit.FunSuite =>

  /** The client requests go out on, never following a redirect, which is what a suite reading a
    * `Location` header itself wants. A suite that wants one followed overrides this.
    */
  protected val client: HttpClient =
    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

  /** The one secret both suites sign and verify against. */
  protected val secret: Secret = Secret.parse("server secret, at least thirty two bytes")

  /** Boots a server for one test and stops it afterwards. */
  protected def serving(routes: RouteTable, maxBodySize: Long = 1.MiB, dev: Boolean = false)(
      body: (HttpServer, Int) => Unit
  ): Unit = {
    val server = HttpServer.start(
      port = 0,
      routes = routes,
      config = HttpConfig(maxBodySize, dev, secret = secret)
    )
    try body(server, server.port)
    finally server.stop()
  }

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
    val builder = HttpRequest.newBuilder(URI.create(s"http://localhost:$port$path"))
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
