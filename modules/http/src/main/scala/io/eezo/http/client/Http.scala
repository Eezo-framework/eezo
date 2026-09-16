package io.eezo.http.client

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

import scala.jdk.CollectionConverters.*

/** What one outbound call came back as: a closed set the caller matches exhaustively.
  *
  * Four arms and no more. [[Denied]] (401/403) is first-class because it is the arm with a
  * different *action* attached — refresh a token, surface a login — so the compiler asks every
  * caller what happens when credentials die, which is the whole point of matching a total function
  * over this enum rather than a `PartialFunction` that swallows what it does not name.
  * [[Unreachable]] is separate from [[Failed]] because "the server answered badly" and "no HTTP
  * happened at all" recover differently. Nothing here throws for an ordinary failure; a `Reply` is
  * a value, whatever happened.
  */
enum Reply {

  /** 2xx: what the caller hoped for. */
  case Ok(response: Received)

  /** 401 or 403: the credentials are wrong, expired, or missing. */
  case Denied(response: Received)

  /** Any other status: the server answered, and the answer was no. */
  case Failed(response: Received)

  /** No response at all: DNS, connect refused, timeout, TLS. The reason is for the log — there is
    * no status to branch on because no HTTP happened.
    */
  case Unreachable(reason: String)
}

/** What was received: the status, the headers, the bytes. Named for what it is rather than
  * `Response`, so a file that also handles eezo's own [[io.eezo.http.Response]] never has two
  * meanings for one word.
  */
final case class Received(status: Int, headers: Map[String, List[String]], body: Array[Byte]) {

  def text: String = new String(body, StandardCharsets.UTF_8)

  /** A header, case-insensitively, first value wins — the same reading rule as `Request`. */
  def header(name: String): Option[String] =
    headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }.flatMap(_.headOption)
}

/** Request-side credentials, as the header pair they are. */
object Auth {

  def bearer(token: String): (String, String) = "Authorization" -> s"Bearer $token"

  def basic(user: String, password: String): (String, String) = {
    val encoded =
      Base64.getEncoder.encodeToString(s"$user:$password".getBytes(StandardCharsets.UTF_8))
    "Authorization" -> s"Basic $encoded"
  }
}

/** The outbound HTTP client: direct style on the JDK's own `HttpClient`, so eezo's runtime
  * classpath stays Jetty and the Postgres driver and nothing else. A blocking `send` on a virtual
  * thread parks rather than pins, which is why there is no async variant here — the live layer's
  * `Async[S]` supplies the thread, and everything else calls from wherever it already is.
  *
  * Redirects follow the JDK's `NORMAL` policy: followed, never from HTTPS down to HTTP, and the JDK
  * strips `Authorization` when a redirect changes origin. Bodies are text or bytes; the JSON story
  * belongs to the future public `derives Json` design, not here (design/live.md M6).
  */
final class Http(timeout: Duration) {

  private val client = HttpClient
    .newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(timeout)
    .build()

  def get(url: String, headers: (String, String)*): Reply =
    send(url, headers)(_.GET())

  def delete(url: String, headers: (String, String)*): Reply =
    send(url, headers)(_.DELETE())

  def post(url: String, body: String, contentType: String, headers: (String, String)*): Reply =
    send(url, headers) {
      _.header("Content-Type", contentType)
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    }

  def put(url: String, body: String, contentType: String, headers: (String, String)*): Reply =
    send(url, headers) {
      _.header("Content-Type", contentType)
        .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    }

  private def send(url: String, headers: Seq[(String, String)])(
      build: HttpRequest.Builder => HttpRequest.Builder
  ): Reply =
    try {
      val base    = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
      val withAll = headers.foldLeft(base) { case (b, (name, value)) => b.header(name, value) }
      val sent    = client.send(build(withAll).build(), HttpResponse.BodyHandlers.ofByteArray())

      val received = Received(
        status = sent.statusCode(),
        headers = sent.headers().map().asScala.map { case (k, v) => k -> v.asScala.toList }.toMap,
        body = sent.body()
      )
      received.status match {
        case ok if ok >= 200 && ok <= 299 => Reply.Ok(received)
        case 401 | 403                    => Reply.Denied(received)
        case _                            => Reply.Failed(received)
      }
    } catch {
      // The JDK signals every no-response condition as an exception; callers get one arm. An
      // interrupt is re-signalled so a closing page's thread still winds down.
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        Reply.Unreachable(s"interrupted: ${e.getMessage}")
      case e: Exception =>
        Reply.Unreachable(s"${e.getClass.getSimpleName}: ${e.getMessage}")
    }
}

object Http {

  /** Ten seconds to connect and ten to answer: long enough for a slow API, short enough that a page
    * loop waiting on [[io.eezo.http.client.Reply]] is never parked into next week.
    */
  val DefaultTimeout: Duration = Duration.ofSeconds(10)

  private val default = new Http(DefaultTimeout)

  def get(url: String, headers: (String, String)*): Reply = default.get(url, headers*)

  def delete(url: String, headers: (String, String)*): Reply = default.delete(url, headers*)

  def post(
      url: String,
      body: String,
      contentType: String = "application/json",
      headers: (String, String)*
  ): Reply =
    default.post(url, body, contentType, headers*)

  def put(
      url: String,
      body: String,
      contentType: String = "application/json",
      headers: (String, String)*
  ): Reply =
    default.put(url, body, contentType, headers*)

  /** The same calls under another deadline, for the caller who knows better. */
  def withTimeout(timeout: Duration): Http = new Http(timeout)
}
