package eezo.http

import eezo.core.html.*

/** Where a thrown failure becomes a response.
  *
  * Status is a fact about this boundary and never a field on an exception, so the mapping is one
  * exhaustive match over the sealed set. Extension sits in the same place: a user's own exception
  * reaches a status through the `problems` hook on `Eezo.run`, tried after eezo's set and before
  * the 500 fallback.
  */
private[http] object Boundary {

  /** What a 500 says to the world when `dev` is false. */
  private val Redacted = "The server encountered an unexpected error."

  /** Resolves any throwable to a `Problem`. Total by construction: whatever arrives, a value comes
    * back, which is what lets the single completion site in the Jetty handler be unconditional.
    */
  def problemOf(
      failure: Throwable,
      path: String,
      dev: Boolean,
      problems: PartialFunction[Throwable, Problem]
  ): Problem = failure match {
    case e: EezoException =>
      e match {
        case BadRequest(detail)         => Problem(400, detail, path)
        case NotFound(_)                => Problem(404, e.getMessage, path)
        case MethodNotAllowed(_)        => Problem(405, e.getMessage, path)
        case PayloadTooLarge(_)         => Problem(413, e.getMessage, path)
        case NotImplemented(_)          => Problem(501, e.getMessage, path)
        case InternalServerError(cause) =>
          Problem(500, if (dev) messageOf(cause) else Redacted, path)
      }

    case other =>
      problems.lift(other).getOrElse(Problem(500, if (dev) messageOf(other) else Redacted, path))
  }

  /** The response a failure becomes: one rendering path, and the `Allow` header in the one arm that
    * has the methods to put in it.
    */
  def errorResponse(
      failure: Throwable,
      path: String,
      dev: Boolean,
      problems: PartialFunction[Throwable, Problem] = PartialFunction.empty
  ): Response = {
    val problem = problemOf(failure, path, dev, problems)
    val page    = Response.Ok(render(problem)).copy(status = problem.status)

    failure match {
      case MethodNotAllowed(allowed) => page.withHeader("Allow", allowed.mkString(", "))
      case _                         => page
    }
  }

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
