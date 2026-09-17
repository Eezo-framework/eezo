package io.eezo.http

import io.eezo.core.html.*

/** Where a thrown failure becomes a response.
  *
  * Status is a fact about this boundary and never a field on an exception, so the mapping is one
  * exhaustive match over the sealed set. Extension sits in the same place: a user's own exception
  * reaches a status through the `problems` override on `HttpApp`, tried after eezo's set and before
  * the 500 fallback.
  */
private[http] object Boundary {

  /** What a 500 says to the world when `dev` is false. */
  private val Redacted = "The server encountered an unexpected error."

  /** Everything the boundary decides about a failure, in one pass over it: the `Problem` it becomes
    * and any headers earned along the way. `Allow` on a 405 is the only header eezo decides itself,
    * and it is decided here rather than on a second match, because `Problem` (RFC 9457's data
    * model) has no room for a header that is not part of that model.
    */
  private[http] final case class Resolution(
      problem: Problem,
      headers: Seq[(String, String)] = Seq.empty
  )

  /** The single exhaustive match. `errorResponse` is read off one call to this, so a failure is
    * matched once rather than once per question asked about it.
    *
    * The `case other` arm is what makes `InternalServerError` reachable: a throwable outside eezo's
    * set and outside the `problems` hook is wrapped in it and re-matched, so the boundary carries
    * the one 500 case rather than two.
    */
  def resolve(failure: Throwable, path: String, config: Config): Resolution = failure match {
    case e: EezoException =>
      e match {
        case BadRequest(detail) => Resolution(Problem(400, detail, path))
        // No `WWW-Authenticate`, which RFC 9110 makes mandatory on a 401 for the schemes it defines.
        // eezo authenticates with a form and a session cookie, which is not one of those schemes,
        // and the header would make a browser open its own credential dialog over the page. Every
        // form based framework answers a 401 without it for that reason.
        case Unauthorized(detail)      => Resolution(Problem(401, detail, path))
        case Forbidden(detail)         => Resolution(Problem(403, detail, path))
        case NotFound(_)               => Resolution(Problem(404, e.getMessage, path))
        case MethodNotAllowed(allowed) =>
          Resolution(Problem(405, e.getMessage, path), Seq("Allow" -> allowed.mkString(", ")))
        case PayloadTooLarge(_)         => Resolution(Problem(413, e.getMessage, path))
        case NotImplemented(_)          => Resolution(Problem(501, e.getMessage, path))
        case InternalServerError(cause) =>
          Resolution(Problem(500, if (config.dev) messageOf(cause) else Redacted, path))
      }

    case other =>
      config.problems.lift(other) match {
        case Some(problem) => Resolution(problem)
        case None          => resolve(InternalServerError(other), path, config)
      }
  }

  /** Renders a `Resolution` into a `Response`. No matching left to do: the headers are already
    * decided, so this is the rendering path alone.
    *
    * The `Response` is built directly rather than through `Response.Ok`, because `Ok` names a 200
    * and an error page is never one: borrowing it and overwriting the status would make every error
    * page inherit whatever else `Ok` ever grows.
    */
  def toResponse(resolution: Resolution): Response = {
    val page =
      Response(
        resolution.problem.status,
        Seq(Response.HtmlContentType),
        Body.Html(render(resolution.problem))
      )
    resolution.headers.foldLeft(page) { case (response, (name, value)) =>
      response.withHeader(name, value)
    }
  }

  /** The response a failure becomes: one rendering path, and the `Allow` header in the one arm that
    * has the methods to put in it.
    */
  def errorResponse(failure: Throwable, path: String, config: Config): Response =
    toResponse(resolve(failure, path, config))

  /** A stack trace is worth a log at ERROR when the server is at fault. A 4xx is a client mistake,
    * and logging it at ERROR is how log noise starts.
    */
  def logsStackTrace(status: Int): Boolean = status >= 500

  /** eezo's own error page. Deliberately plain: whether an application can replace it is an open
    * question on the map, and shipping a layout seam before it is answered risks shipping the wrong
    * one and then having two.
    */
  private def render(problem: Problem): Html =
    Html.doctype ++ html(
      head(
        meta(Attrs.charset := "utf-8"),
        title(s"${problem.status} ${problem.title}")
      ),
      body(
        h1(s"${problem.status} ${problem.title}"),
        p(problem.detail),
        p(small(problem.instance))
      )
    )

  private def messageOf(cause: Throwable): String =
    Option(cause.getMessage).getOrElse(cause.toString)
}
