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
  * The body is eager and capped: `Eezo.run`'s `maxBodySize` decides how much of it is read, and
  * exceeding the cap is a 413 rather than a stream nobody drains. What is deliberately absent is an
  * untyped `attachment: AnyRef` bag. A capability arrives through the handler's `using` list, where
  * its absence is a compile error rather than a `sys.error` on the first request that needs it.
  */
final case class Request(
    method: Method,
    path: String,
    query: Map[String, Seq[String]],
    headers: Map[String, Seq[String]],
    body: Array[Byte],
    pathParams: Map[String, String]
) {

  /** A header, case insensitively, first value wins. */
  def header(name: String): Option[String] =
    headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }.flatMap(_.headOption)

  /** A query parameter, first value wins. */
  def queryParam(name: String): Option[String] = query.get(name).flatMap(_.headOption)

  /** The form encoded body, decoded once and remembered.
    *
    * Multi valued, because checkbox groups and multi selects genuinely produce repeats and a
    * `Map[String, String]` drops them in silence. Memoised, because the `_method` override reads
    * the body before dispatch and the handler reads it again. `multipart/form-data` is out of
    * scope: this is `application/x-www-form-urlencoded` only.
    */
  lazy val form: Map[String, Seq[String]] =
    if (!header("Content-Type").exists(_.startsWith("application/x-www-form-urlencoded")))
      Map.empty
    else Request.decodeForm(new String(body, StandardCharsets.UTF_8))

  /** A path parameter, converted. Throws [[BadRequest]], which the boundary maps to a 400. */
  def param[A](name: String)(using from: FromPath[A]): A =
    paramOpt[A](name).getOrElse(
      throw BadRequest(
        pathParams.get(name) match {
          case Some(value) => s"path parameter '$name' cannot be read from '$value'"
          case None        => s"path parameter '$name' is not part of this route"
        }
      )
    )

  /** The non throwing form of [[param]]. */
  def paramOpt[A](name: String)(using from: FromPath[A]): Option[A] =
    pathParams.get(name).flatMap(from.apply)
}

object Request {

  /** The field name a browser sends the verb it cannot issue under. */
  private[http] val MethodField = "_method"

  /** Applies the `_method` override, so that everything downstream sees the real verb.
    *
    * A browser can only issue `GET` and `POST` from markup, and the derived seven need `PUT` and
    * `DELETE`. The override is applied once, before dispatch, which is what keeps
    * `RouteTable.dispatch` a pure function of a request whose method is true: a unit test that
    * builds a `PUT` gets a `PUT`, with no transport quirk in between.
    *
    * Only a form-encoded `POST` is overridden, with the query string as the fallback for the case
    * that has no body to carry it, and it never downgrades to `GET`: turning a `POST` into a `GET`
    * loses the body and makes the request repeatable, which is not something a form should be able
    * to ask for. An unrecognised name is left alone rather than raising, because a request eezo
    * does not understand is one it has no reason to reject on this field's behalf.
    */
  private[http] def withMethodOverride(request: Request): Request =
    if (request.method != Method.POST) request
    else
      request.form
        .get(MethodField)
        .flatMap(_.headOption)
        .orElse(request.queryParam(MethodField))
        .flatMap(Method.parse)
        .filter(_ != Method.GET) match {
        case Some(method) => request.copy(method = method)
        case None         => request
      }

  /** Decodes `a=1&b=2`, UTF-8, `+` as a space, percent decoded, keeping repeats in order. */
  private def decodeForm(raw: String): Map[String, Seq[String]] =
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
