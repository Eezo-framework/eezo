package io.eezo.http

/** A request on an API route: one a program sends, in which no session takes part.
  *
  * A type of its own rather than a [[Request]] with an empty session, because the type a handler
  * takes is the one place a page says what it is: a file whose handler takes this is an API route,
  * with no declaration anywhere else to forget or to contradict. It follows that nothing a browser
  * carries is here to read. A session, a flash, a CSRF token, the cookies and the form would each
  * be a question with no honest answer on a route no browser session reaches, so they are absent
  * rather than empty. The `Cookie` header is still among the headers, as sent, because a header is
  * what a program chose to send and not something eezo read for it.
  *
  * Public and a plain case class so that an application's test can build one and call its handler
  * directly; only turning a served [[Request]] into one, [[ApiRequest.of]], is eezo's own.
  */
final case class ApiRequest(
    method: Method,
    path: String,
    query: Map[String, Seq[String]],
    headers: Map[String, Seq[String]],
    body: Array[Byte],
    pathParams: Map[String, String]
) {

  /** A header, case insensitively, first value wins. */
  def header(name: String): Option[String] =
    Request.headerValues(headers, name).headOption

  /** A query parameter, first value wins. */
  def queryParam(name: String): Option[String] = query.get(name).flatMap(_.headOption)

  /** A path parameter, converted. Throws [[BadRequest]], which the boundary maps to a 400. */
  def param[A](name: String)(using from: FromPath[A]): A = Request.param(pathParams, name)
}

object ApiRequest {

  /** What the generated row hands an API handler. Closed to an application because the request it
    * reads has already been through dispatch, which is what emptied its session: a door that built
    * one from any `Request` would be a way to call an API handler with a request no API route
    * served.
    */
  private[http] def of(request: Request): ApiRequest =
    ApiRequest(
      request.method,
      request.path,
      request.query,
      request.headers,
      request.body,
      request.pathParams
    )
}
