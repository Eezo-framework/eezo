package io.eezo.http.api

import io.eezo.http.{BadRequest, FromPath, Method, UnprocessableContent}

/** A request on an API route: one a program sends, in which no session takes part.
  *
  * A type of its own rather than an [[io.eezo.http.Request]] with an empty session, because the
  * type a handler takes is the one place a page says what it is: a file whose handler takes this is
  * an API route, with no declaration anywhere else to forget or to contradict. It follows that
  * nothing a browser carries is here to read. A session, a flash, a CSRF token, the cookies and the
  * form would each be a question with no honest answer on a route no browser session reaches, so
  * they are absent rather than empty. The `Cookie` header is still among the headers, as sent,
  * because a header is what a program chose to send and not something eezo read for it.
  *
  * `currentUser` is the one thing it carries that a program did not send: who the guard says is
  * behind the request, read off the API token in its Bearer header and never off a session, as the
  * key the browser's request spells it with. A handler reads it here because it holds no browser
  * request to ask a guard about, and `None` is nobody, which is what every unguarded API route
  * reads.
  *
  * Public and a plain case class so that an application's test can build one and call its handler
  * directly; only turning a served [[io.eezo.http.Request]] into one, [[Request.of]], is eezo's
  * own. It lives in a package of its own so that the signature says the kind of route in so many
  * words, `api.Request` beside the browser's `Request`, rather than in a prefix on one name.
  */
final case class Request(
    method: Method,
    path: String,
    query: Map[String, Seq[String]],
    headers: Map[String, Seq[String]],
    body: Array[Byte],
    pathParams: Map[String, String],
    currentUser: Option[String] = None
) {

  /** A header, case insensitively, first value wins. */
  def header(name: String): Option[String] =
    io.eezo.http.Request.headerValues(headers, name).headOption

  /** A query parameter, first value wins. */
  def queryParam(name: String): Option[String] = query.get(name).flatMap(_.headOption)

  /** A path parameter, converted. Throws [[io.eezo.http.BadRequest]], which the boundary maps to a
    * 400.
    */
  def param[A](name: String)(using from: FromPath[A]): A =
    io.eezo.http.Request.param(pathParams, name)

  /** The body, read into a model through its [[Serde]].
    *
    * A value rather than the `Either` the browser's `request.as` returns, because on an API route
    * there is no form to render again with its errors: the only answer to a body that will not read
    * is a refusal, so it is thrown to the boundary, a [[io.eezo.http.BadRequest]] when the body is
    * not JSON and an [[io.eezo.http.UnprocessableContent]] when it does not fit, each carrying the
    * adapter's reason as the problem's detail.
    */
  def as[A](using serde: Serde[A]): A =
    serde.deserialize(body) match {
      case Right(value)                          => value
      case Left(Serde.Failure.Malformed(reason)) => throw BadRequest(reason)
      case Left(Serde.Failure.Unfit(reason))     => throw UnprocessableContent(reason)
    }
}

object Request {

  /** What the generated row hands an API handler. Closed to an application because the request it
    * reads has already been through dispatch, which is what emptied its session: a door that built
    * one from any browser request would be a way to call an API handler with a request no API route
    * served.
    */
  private[http] def of(request: io.eezo.http.Request): Request =
    Request(
      request.method,
      request.path,
      request.query,
      request.headers,
      request.body,
      request.pathParams,
      request.currentUser
    )
}
