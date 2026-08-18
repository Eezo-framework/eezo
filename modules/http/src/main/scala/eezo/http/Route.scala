package eezo.http

/** A route, in the two kinds eezo serves.
  *
  * A sealed enum rather than one record with a kind field, because a WebSocket route has no
  * `Method`: the upgrade is always a `GET` and eezo never dispatches on it. Neither case carries a
  * field it does not use.
  */
enum Route {

  case Http(method: Method, pattern: PathPattern, handler: Handler)

  /** The seam to `modules/live`. The payload is an eezo type rather than Jetty's
    * `WebSocketCreator`, which is what keeps Jetty off `live`'s classpath.
    */
  case Ws(pattern: PathPattern, endpoint: Request => WsListener)
}

/** The routes an application serves, in the order they are matched.
  *
  * `Seq` order is the entire contract: the table never sorts, and `++` concatenates. Specificity
  * sorting was rejected because it is a rule a reader cannot see by reading the generated file top
  * to bottom, and that file is what people debug routing with. Rails, Phoenix and Play are all
  * declaration ordered for the same reason. The sbt plugin owns emit order.
  */
final class RouteTable(val routes: Seq[Route]) {

  /** The two kinds, split once. `Eezo.run` reads `wsRoutes` to build its single WebSocket mapping
    * and hands `httpRoutes` to the Jetty handler, so neither walks past a route it cannot use.
    */
  val httpRoutes: Seq[Route.Http] = routes.collect { case route: Route.Http => route }

  val wsRoutes: Seq[Route.Ws] = routes.collect { case route: Route.Ws => route }

  locally {
    val httpKeys = httpRoutes.map(route => (route.method, route.pattern.render))
    val wsKeys   = wsRoutes.map(route => ("WS", route.pattern.render))
    val keys     = httpKeys ++ wsKeys
    keys.diff(keys.distinct).headOption.foreach { case (method, pattern) =>
      throw new IllegalArgumentException(
        s"duplicate route: $method $pattern is mounted twice. Shadowing is legal and ordered; " +
          "the same method and pattern twice is a bug."
      )
    }
  }

  /** Runs the first HTTP route whose method and pattern both match.
    *
    * One pass. If no route matched but some route's pattern matched the path, their methods are
    * already in hand, so the failure is a [[MethodNotAllowed]] carrying exactly what `Allow` needs;
    * otherwise it is a [[NotFound]]. Only a single pass can populate that header, which RFC 9110
    * makes mandatory on a 405.
    */
  def dispatch(request: Request): Response = {
    val allowed = Seq.newBuilder[Method]

    val matched = httpRoutes.iterator
      .flatMap { route =>
        route.pattern.matchPath(request.path) match {
          case None         => None
          case Some(params) =>
            if (route.method == request.method) Some(route.handler -> params)
            else { allowed += route.method; None }
        }
      }
      .nextOption()

    matched match {
      case Some((handler, params)) => handler(request.copy(pathParams = params))
      case None                    =>
        val methods = allowed.result().distinct
        if (methods.isEmpty) throw NotFound(request.path)
        else throw MethodNotAllowed(methods)
    }
  }

  /** Concatenation. Order is preserved, so the receiver's routes keep winning. */
  def ++(other: RouteTable): RouteTable = RouteTable(routes ++ other.routes)
}

object RouteTable {

  def apply(routes: Seq[Route]): RouteTable = new RouteTable(routes)

  val empty: RouteTable = new RouteTable(Seq.empty)
}
