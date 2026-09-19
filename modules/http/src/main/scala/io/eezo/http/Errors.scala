package io.eezo.http

/** eezo's HTTP failure channel.
  *
  * The set is sealed and lives in `modules/http` rather than in `core`, which is what makes it
  * impossible for a domain module to throw one: `db` depends on `core` and never on `http`. The
  * module boundary enforces what a convention would only ask for.
  *
  * No case carries a `status`, and none carries `headers`. Sealing makes the mapping at the
  * boundary an exhaustive match with no fallback, the `Allow` header lives in the 405 arm, and an
  * open base carrying a status is exactly the mechanism by which an HTTP status reaches code that
  * should not know one. See `docs/adr/0001-http-errors-live-in-http-and-carry-no-status.md`.
  */
sealed abstract class EezoException(message: String) extends RuntimeException(message)

/** A value that will not convert, in a path parameter or anywhere else the request is malformed. */
final case class BadRequest(detail: String) extends EezoException(detail)

/** No route matched the path at all. */
final case class NotFound(path: String) extends EezoException(s"no route matches $path")

/** A route matched the path but not the method. `allowed` is what the mandatory `Allow` header on a
  * 405 is built from, which is why it is carried here rather than recomputed at the boundary.
  */
final case class MethodNotAllowed(allowed: Seq[Method])
    extends EezoException(s"allowed: ${allowed.mkString(", ")}")

/** A request the application understood and refuses: a form whose CSRF token is missing or stale
  * (#170), a write to a row the current user does not own, or a guarded WebSocket handshake
  * carrying no live sign in. #166's constraint: no status here, and a redirect to login is a
  * `Response`, never an error.
  *
  * The one refusal there is, rather than a 403 beside a 401, because eezo signs in through a form
  * and a session cookie and has no honest challenge to put on a 401: the scheme token a 401 needs
  * would be one eezo invented, and a client reading the status alone learns as little from that as
  * from this. What kind of refusal it was is the detail's job. See
  * `docs/adr/0001-http-errors-live-in-http-and-carry-no-status.md`.
  */
final case class Forbidden(detail: String) extends EezoException(detail)

/** The request body exceeded `HttpApp.maxBodySize`. */
final case class PayloadTooLarge(limit: Long)
    extends EezoException(s"request body exceeds the $limit byte limit")

/** A method eezo does not model, which is a 501 rather than a match failure. */
final case class NotImplemented(method: String)
    extends EezoException(s"method $method is not supported")

/** Anything else that reached the boundary. It holds its cause so that the logger and the renderer
  * read the same value, and so the boundary carries one value type instead of two branches.
  */
final case class InternalServerError(cause: Throwable) extends EezoException(cause.toString)
