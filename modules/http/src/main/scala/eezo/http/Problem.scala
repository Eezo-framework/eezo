package eezo.http

/** A failure, as RFC 9457 section 3's data model.
  *
  * Only the data model is adopted. Section 4's `application/problem+json` waits for `derives Api`,
  * which will serialize this identical value, so the HTML and JSON paths cannot diverge on errors.
  * A browser hitting a missing widget gets a page, not raw JSON, and eezo does not negotiate on
  * `Accept`, which is out of scope.
  *
  * `tpe` is RFC 9457's `type` member, spelled the way `core`'s HTML DSL already spells that
  * keyword. It stays `about:blank`: minting `https://eezo.io/problems/...` URIs is a documentation
  * obligation taken on before eezo has documentation, and section 4.2.1 makes `title` the status
  * phrase exactly when `type` is `about:blank`.
  */
final case class Problem(
    tpe: String,
    title: String,
    status: Int,
    detail: String,
    instance: String
)

object Problem {

  /** The usual way to build one: the title follows from the status. */
  def apply(status: Int, detail: String, instance: String): Problem =
    Problem("about:blank", phrase(status), status, detail, instance)

  /** The status phrases eezo can produce itself, plus the ones a `problems` hook is most likely to
    * reach for. Anything else is titled by its code, which is honest rather than invented.
    */
  private[http] def phrase(status: Int): String = status match {
    case 400   => "Bad Request"
    case 401   => "Unauthorized"
    case 403   => "Forbidden"
    case 404   => "Not Found"
    case 405   => "Method Not Allowed"
    case 409   => "Conflict"
    case 413   => "Content Too Large"
    case 422   => "Unprocessable Content"
    case 500   => "Internal Server Error"
    case 501   => "Not Implemented"
    case 503   => "Service Unavailable"
    case other => s"HTTP $other"
  }
}
