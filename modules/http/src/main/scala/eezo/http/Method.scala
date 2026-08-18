package eezo.http

/** The HTTP methods eezo models.
  *
  * `HEAD` and `OPTIONS` are here because they arrive whether eezo models them or not, and a method
  * eezo has no case for should be a clean 501 rather than a match failure. `TRACE` and `CONNECT`
  * are excluded: neither reaches an application handler in a server sitting behind Caddy.
  */
enum Method {
  case GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS
}

object Method {

  /** Parses a method name. `None` is what the boundary turns into a 501. */
  def parse(name: String): Option[Method] =
    Method.values.find(_.toString.equalsIgnoreCase(name))
}
