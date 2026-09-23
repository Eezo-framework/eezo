package io.eezo.live

import java.net.URI
import java.util.Locale

import io.eezo.http.Request

/** Which pages may open a live socket, decided on the whole origin: scheme, host and port.
  *
  * The authority alone is not enough. A page served over plain HTTP on the same host is a different
  * origin from the HTTPS one, and a browser holding a cookie for one would otherwise lend it to a
  * socket the other opened.
  */
private[live] object Origins {

  /** An origin already in its one spelling. Raw and normalised origins are both strings, and a set
    * of raw ones would compile and then quietly never match; only [[normalise]] and [[listed]] make
    * one, so the set the socket compares against cannot be the raw list by mistake.
    */
  opaque type Normalised <: String = String

  /** An origin in the one spelling two equal origins share, or `None` when `raw` is not an origin
    * at all. Equality after this is the whole comparison, so nothing here is lenient: anything with
    * a path, a query, a fragment or credentials is refused rather than trimmed, because trimming is
    * how `https://app.example.evil` ends up looking like `https://app.example`.
    */
  def normalise(raw: String): Option[Normalised] =
    try {
      val uri    = new URI(raw)
      val scheme = Option(uri.getScheme).map(_.toLowerCase(Locale.ROOT))
      val bare   =
        uri.getRawUserInfo == null && Option(uri.getRawPath).forall(_.isEmpty) &&
          uri.getRawQuery == null && uri.getRawFragment == null
      val hostAndPort = Option(uri.getHost) match {
        case Some(host) => Some((host.toLowerCase(Locale.ROOT), uri.getPort))
        case None       => registryHost(uri.getRawAuthority)
      }
      for {
        s         <- scheme
        (h, port) <- hostAndPort
        if bare && h.nonEmpty
      } yield {
        if (port == -1 || DefaultPorts.get(s).contains(port)) s"$s://$h" else s"$s://$h:$port"
      }
    } catch { case _: Exception => None }

  /** The host and port of an authority `java.net.URI` would not read as a server. It gives up on a
    * name with an underscore, such as a compose service called `my_app`, yet browsers send exactly
    * that as the origin, and refusing it would lock a same origin page out of its own socket. Only
    * hostname characters are accepted, so credentials and wildcards are still not an origin.
    */
  private def registryHost(authority: String): Option[(String, Int)] =
    Option(authority).map(_.toLowerCase(Locale.ROOT)).collect {
      case RegistryAuthority(host, null)                        => (host, -1)
      case RegistryAuthority(host, port) if port.toInt <= 65535 => (host, port.toInt)
    }

  private val RegistryAuthority = "([a-z0-9_.-]+)(?::([0-9]{1,5}))?".r

  /** An entry of `LiveApp.allowedOrigins`, normalised, or the boot fails naming it. A developer's
    * list is configuration, not traffic: an entry that could never match is a typo, and a typo that
    * quietly refuses every socket is found in production instead of at boot.
    */
  def listed(entry: String): Normalised =
    normalise(entry).getOrElse(
      throw new IllegalArgumentException(
        s"LiveApp.allowedOrigins: '$entry' is not an origin; write scheme://host or " +
          "scheme://host:port, with no path, no trailing slash and no wildcard"
      )
    )

  /** A value the client chose, made safe to echo: every character outside printable ASCII becomes
    * `\uXXXX`, so a forged header cannot start a new log line, and the result is capped. The cap is
    * what the WebSocket close frame imposes: a reason is at most 123 bytes, and `origin … not
    * allowed` around this has to fit, or the refusal itself fails to send.
    */
  def shown(raw: String): String = {
    val escaped = raw.flatMap { c =>
      if (c >= ' ' && c <= '~') c.toString else f"\\u${c.toInt}%04x"
    }
    if (escaped.length <= ShownCap) escaped else escaped.take(ShownCap - 3) + "..."
  }

  private val ShownCap = 100

  private val DefaultPorts = Map("http" -> 80, "https" -> 443)

  /** The origin this server answers as for `request`: the scheme the browser used, which
    * `request.secure` already knows, and the `Host` it asked for. `None` when the `Host` is missing
    * or is not a host, so a browser is then refused unless the application listed its origin.
    *
    * `request.secure` trusts `X-Forwarded-Proto`, so the scheme half of this rests on a header the
    * client can write. That is safe here for the same reason a missing `Origin` is: a browser
    * cannot add headers to a WebSocket upgrade, and a client that can forge one is not a browser
    * and carries no victim's cookie. Nothing else forwarded is read, `X-Forwarded-Host` included,
    * because a proxy that rewrites `Host` is better fixed at the proxy than trusted here.
    */
  def served(request: Request): Option[Normalised] = {
    val scheme = if (request.secure) "https" else "http"
    request.header("Host").flatMap(host => normalise(s"$scheme://$host"))
  }

  /** Whether an upgrade may join: its `Origin` is the server's own or one the application listed.
    * No `Origin` is a client that is not a browser, and it is let through: origin checking exists
    * against a browser lending its cookies to a socket another site opened, and a client that is
    * not a browser carries no victim's cookie.
    */
  def admits(request: Request, allowed: Set[Normalised]): Boolean =
    request.header("Origin") match {
      case None         => true
      case Some(origin) =>
        normalise(origin).exists(received =>
          allowed.contains(received) || served(request).contains(received)
        )
    }
}
