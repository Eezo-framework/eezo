package io.eezo.http

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

import io.eezo.core.Id

/** Converts a path parameter's text into the type a handler asked for.
  *
  * A model's key type is covered by the `Id[T]` instance in this companion, so a derived `show`
  * never converts a `String` by hand.
  */
trait FromPath[A] {
  def apply(value: String): Option[A]
}

object FromPath {

  given FromPath[String] = value => Some(value)

  given FromPath[Int] = value => value.toIntOption

  given FromPath[Long] = value => value.toLongOption

  given FromPath[UUID] = value =>
    try Some(UUID.fromString(value))
    catch { case _: IllegalArgumentException => None }

  /** A model's key, which is what a derived `show`, `edit`, `update` and `destroy` all read out of
    * the path. It is here rather than in `Id`'s companion because `FromPath` is this module's and
    * `Id` is `core`'s, which cannot see it.
    */
  given [T]: FromPath[Id[T]] = value => summon[FromPath[UUID]].apply(value).map(Id.apply)
}

/** One request, read whole.
  *
  * The body is eager and capped: `HttpApp.maxBodySize` decides how much of it is read, and
  * exceeding the cap is a 413 rather than a stream nobody drains. What is deliberately absent is an
  * untyped `attachment: AnyRef` bag. A capability arrives through the handler's `using` list, where
  * its absence is a compile error rather than a `sys.error` on the first request that needs it.
  *
  * The session is read for the handler out of the signed cookie once the route has matched, and
  * only on a browser route: no session takes part in an API route, so a request there carries the
  * empty one whatever cookie it sent, and so does a request built by hand until a test says
  * otherwise with `copy`.
  *
  * `secure` is whether the browser reached the application over HTTPS, which is what decides a
  * cookie's `Secure` attribute. A request built by hand is not.
  *
  * `currentUser` is who the guard says is behind this request, as the key the session spells it
  * with, and it is a fact about the request rather than a way to ask one: `http` holds the value
  * and whoever owns the rule, a guard, holds the rule. It is written before the handler it is for
  * runs, by the guard's own wrapper on a guarded page and by the route table's `identify` on a
  * socket upgrade, and never from a header, a query or path parameter, or a frame, all of which a
  * client chooses. Nobody is `None`, which is what every request in an application with no way of
  * signing in carries, and what a request built by hand carries until something names it.
  */
final case class Request(
    method: Method,
    path: String,
    query: Map[String, Seq[String]],
    headers: Map[String, Seq[String]],
    body: Array[Byte],
    pathParams: Map[String, String],
    session: Session = Session.empty,
    secure: Boolean = false,
    currentUser: Option[String] = None
) {

  /** A header, case insensitively, first value wins. */
  def header(name: String): Option[String] =
    Request.headerValues(headers, name).headOption

  /** A query parameter, first value wins. */
  def queryParam(name: String): Option[String] = query.get(name).flatMap(_.headOption)

  /** The `Cookie` header, split into pairs, decoded once and remembered. Every header of that name
    * counts, and the first of a repeated name wins. What a handler reads for its own cookies; on a
    * browser route the session cookie is read for it, into [[session]], once the route has matched.
    */
  lazy val cookies: Map[String, String] =
    Cookie.parse(Request.headerValues(headers, "Cookie"))

  /** One cookie by name. */
  def cookie(name: String): Option[String] = cookies.get(name)

  /** The CSRF token this browser's forms must carry: what a handler hands to `Form.render` and
    * `Csrf.hidden`. Dispatch mints one into the session before any handler on a browser route runs,
    * so inside one this cannot fail; a request built by hand and never dispatched has none, and
    * asking is a bug in the caller rather than a bad request.
    *
    * @throws IllegalStateException
    *   on a request dispatch has never seen, which has no token to hand out.
    */
  def csrf: Csrf.Token =
    Csrf
      .read(session)
      .getOrElse(
        throw new IllegalStateException(
          "this request has no CSRF token: dispatch mints one before a handler runs, so a " +
            "request built by hand has to be dispatched"
        )
      )

  /** The form encoded body, decoded once and remembered.
    *
    * Multi valued, because checkbox groups and multi selects genuinely produce repeats and a
    * `Map[String, String]` drops them in silence. Memoised, because the `_method` override reads
    * the body before dispatch and the handler reads it again. `multipart/form-data` is out of
    * scope: this is `application/x-www-form-urlencoded` only.
    */
  lazy val form: Map[String, Seq[String]] =
    if (!isFormEncoded) Map.empty
    else Request.decodeForm(new String(body, StandardCharsets.UTF_8))

  /** Whether the body is one a form submission produces, which is the single place that question is
    * answered.
    *
    * Both [[form]] and the `_method` override ask it, and they have to agree: an override that
    * fired on a body [[form]] refuses to read would let a request that is not a form submission at
    * all choose its own verb.
    */
  private[http] def isFormEncoded: Boolean =
    header("Content-Type").exists(_.startsWith("application/x-www-form-urlencoded"))

  /** A path parameter, converted. Throws [[BadRequest]], which the boundary maps to a 400. */
  def param[A](name: String)(using from: FromPath[A]): A = Request.param(pathParams, name)

  /** The non throwing form of [[param]]. */
  def paramOpt[A](name: String)(using from: FromPath[A]): Option[A] =
    pathParams.get(name).flatMap(from.apply)
}

object Request {

  /** Whether the browser used HTTPS: the connection says so, or a proxy that terminated TLS does.
    *
    * `X-Forwarded-Proto` is trusted for this one question and nothing else. A client that forges it
    * only puts `Secure` on its own cookies, and cannot take it off anyone's, since a TLS connection
    * counts whatever the header says. The answer also picks the scheme of the origin the live
    * socket computes for itself (`io.eezo.live.Origins.served`), so a forger can flip that scheme
    * too, but only for its own socket: a browser cannot add headers to a WebSocket upgrade, and a
    * client that can is not a browser and carries no victim's cookie. A chain of proxies lists the
    * schemes in order, and the first is the one the browser used.
    */
  private[http] def isSecure(tls: Boolean, headers: Map[String, Seq[String]]): Boolean =
    tls || headerValues(headers, "X-Forwarded-Proto").headOption
      .exists(_.split(',').head.trim.equalsIgnoreCase("https"))

  /** A path parameter, converted, or the [[BadRequest]] that says why not. Over the bare map
    * because an [[ApiRequest]] reads its path the same way, and a program calling an API route and
    * a browser calling a page have to be refused in the same words for the same mistake.
    */
  private[http] def param[A](pathParams: Map[String, String], name: String)(using
      from: FromPath[A]
  ): A =
    pathParams.get(name) match {
      case None        => throw BadRequest(s"path parameter '$name' is not part of this route")
      case Some(value) =>
        from(value).getOrElse(
          throw BadRequest(s"path parameter '$name' cannot be read from '$value'")
        )
    }

  /** Every value of a header, case insensitively, in order. Over the bare map rather than a
    * [[Request]], because [[isSecure]] is asked before there is one.
    */
  private[http] def headerValues(headers: Map[String, Seq[String]], name: String): Seq[String] =
    headers.iterator.collect { case (k, v) if k.equalsIgnoreCase(name) => v }.flatten.toSeq

  /** The field name a browser sends the verb it cannot issue under. */
  private[http] val MethodField = "_method"

  /** Applies the `_method` override, so that everything downstream sees the real verb.
    *
    * A browser can only issue `GET` and `POST` from markup, and the derived seven need `PUT` and
    * `DELETE`. The override is applied once, before dispatch, which is what keeps
    * `RouteTable.dispatch` a pure function of a request whose method is true: a unit test that
    * builds a `PUT` gets a `PUT`, with no transport quirk in between.
    *
    * A form-encoded `POST` is the whole of what is overridden, the query string fallback included.
    * The fallback is there for the form that has no field to carry the verb, not for any `POST` at
    * all: without the content type in the gate, a `POST` carrying a JSON body would let its own URL
    * rewrite its verb, and an API client that never asked for this convention would be dispatching
    * `DELETE` from a link somebody appended a query parameter to.
    *
    * It never downgrades to a safe verb: turning a `POST` into a `GET` loses the body and makes the
    * request repeatable, which is not something a form should be able to ask for. `HEAD` and
    * `OPTIONS` are refused with it because `Csrf.verify` skips the safe methods, so a forged
    * submission that named one of them would reach a handler unchecked. An unrecognised name is
    * left alone rather than raising, because a request eezo does not understand is one it has no
    * reason to reject on this field's behalf.
    */
  private[http] def withMethodOverride(request: Request): Request =
    if (request.method != Method.POST || !request.isFormEncoded) request
    else
      request.form
        .get(MethodField)
        .flatMap(_.headOption)
        .orElse(request.queryParam(MethodField))
        .flatMap(Method.parse)
        .filter(!_.safe) match {
        case Some(method) => request.copy(method = method)
        case None         => request
      }

  /** Decodes `a=1&b=2`, UTF-8, `+` as a space, percent decoded, keeping repeats in order. */
  private[http] def decodeForm(raw: String): Map[String, Seq[String]] =
    raw
      .split('&')
      .iterator
      .filter(_.nonEmpty)
      .map { pair =>
        val index         = pair.indexOf('=')
        val (name, value) =
          if (index < 0) (pair, "") else (pair.take(index), pair.drop(index + 1))
        decode(name) -> decode(value)
      }
      .toVector
      .groupMap(_._1)(_._2)

  private def decode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)
}
