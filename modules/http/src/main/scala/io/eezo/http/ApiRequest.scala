package io.eezo.http

/** SPIKE. A request on an API route, one a program calls and no browser session takes part in.
  *
  * What a [[Request]] has minus everything that belongs to a browser: no session, no flash, no
  * CSRF token, no form. Built from the request at the one place the generated row hands it to the
  * handler; dispatch, the guards and the route table keep working on [[Request]].
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
  def param[A](name: String)(using from: FromPath[A]): A =
    pathParams.get(name).flatMap(from.apply).getOrElse(throw BadRequest(s"bad $name"))
}

object ApiRequest {

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
