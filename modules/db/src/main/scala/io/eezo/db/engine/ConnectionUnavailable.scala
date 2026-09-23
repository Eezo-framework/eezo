package io.eezo.db.engine

/** No connection came out of the pool within the acquire timeout.
  *
  * An environment failure (ADR 0006): the database is down, or the pool is too small for the load,
  * and no handler can do anything about either. So it is a plain exception that the request
  * boundary answers as a 500, rather than an `SQLException` that a `problems` hook or an
  * `attempt[SQLException]` would catch and turn into something that looks recoverable.
  */
final class ConnectionUnavailable(message: String, cause: Throwable)
    extends RuntimeException(message, cause)
