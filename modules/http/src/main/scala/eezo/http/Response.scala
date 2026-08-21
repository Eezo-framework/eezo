package eezo.http

/** A response body.
  *
  * `Stream` is deliberately absent and reserved: `Html.renderTo` already writes into a builder, so
  * the case can arrive without reshaping anything here.
  */
enum Body {

  /** Bytes, already encoded. What anything that is not a page comes back as. */
  case Bytes(value: Array[Byte])

  case Html(value: eezo.core.html.Html)

  case Empty
}

/** A response, as a value.
  *
  * The status is a plain `Int`, reached through the named constructors below, because a framework
  * that cannot return 418 is a framework people work around. Headers are an ordered `Seq` that
  * permits duplicates, deliberately asymmetric with the request's `Map`: writing wants order and
  * repetition, `Set-Cookie` being both, while reading wants keyed lookup.
  */
final case class Response(status: Int, headers: Seq[(String, String)], body: Body) {

  /** Appends a header, keeping any header of the same name that is already there. */
  def withHeader(name: String, value: String): Response =
    copy(headers = headers :+ (name -> value))
}

/** Named constructors stop at `Ok` and `Redirect` because those are the only two responses that
  * carry structure beyond a bare code: a page and its Content Type for the former, a Location for
  * the latter. A `NotFound` constructor is deliberately absent even though it would round out the
  * set: eezo's own failure statuses are reached by throwing the sealed set in `Errors.scala`, which
  * `Boundary` catches and renders as a full RFC 9457 problem page. A `Response.NotFound` would read
  * as the way to say 404 while returning an empty body, skipping that page entirely, and it would
  * sit one letter from the `NotFound` exception that does produce it. Every status eezo does not
  * give a name to, including 404 when it is reached outside that thrown path, goes through `status`
  * instead.
  */
object Response {

  /** The one HTML content type in the package, with the charset every HTML body is encoded in.
    * `private[http]` rather than `private`, because `Boundary` renders error pages at their own
    * statuses and must name the same header; a second copy of the literal would drift silently,
    * since each copy is pinned by its own suite.
    */
  private[http] val HtmlContentType = "Content-Type" -> "text/html; charset=utf-8"

  /** A 200 carrying a page. */
  def Ok(html: eezo.core.html.Html): Response =
    Response(200, Seq(HtmlContentType), Body.Html(html))

  /** A 303, which is the redirect a form POST wants: the follow-up is a GET. */
  def Redirect(location: String): Response =
    Response(303, Seq("Location" -> location), Body.Empty)

  /** Any status at all, including the ones eezo does not model. */
  def status(code: Int): Response = Response(code, Seq.empty, Body.Empty)
}

/** What a route runs.
  *
  * Flat, with no capability in its type: capabilities live in the *user's* signature, as `using`
  * parameters, and are closed over when the generated table is built. That keeps `RouteTable` from
  * being generic over a union of every capability any route in the application needs.
  */
type Handler = Request => Response
