package io.eezo.http

import io.eezo.core.html.Url

/** Where a route came from, which is all decision 18's precedence rule needs to know.
  *
  * Handwritten is the default, so every site that builds a route directly keeps writing what it
  * already wrote: users under `app/`, the sbt plugin that emits them, and any transformation over
  * `Seq[Route]`. [[Resource]] is the single place that mints derived routes, and the single place
  * that has to say so. Two values rather than a richer record naming the model, because the only
  * question [[RouteTable]] asks is which of two routes on the same method and path the user wrote
  * on purpose.
  */
enum Provenance {
  case Handwritten, Derived
}

/** A route, in the two kinds eezo serves.
  *
  * A sealed enum rather than one record with a kind field, because a WebSocket route has no
  * `Method`: the upgrade is always a `GET` and eezo never dispatches on it. Neither case carries a
  * field it does not use.
  */
enum Route {

  /** Declared here rather than read out of each case, so that code holding a `Route` can ask
    * without matching first. Both cases answer it with a constructor parameter that defaults to
    * [[Provenance.Handwritten]].
    */
  def provenance: Provenance

  case Http(
      method: Method,
      pattern: PathPattern,
      handler: Handler,
      provenance: Provenance = Provenance.Handwritten
  )

  /** The seam to `modules/live`. The payload is an eezo type rather than Jetty's
    * `WebSocketCreator`, which is what keeps Jetty off `live`'s classpath.
    */
  case Ws(
      pattern: PathPattern,
      endpoint: Request => WsListener,
      provenance: Provenance = Provenance.Handwritten
  )

  /** A route named the way a human reads it: `GET /widgets/:id`, `WS /live`.
    *
    * The shadow warning and the `dev = true` boot print both name routes and neither can reach for
    * the source text, so the one rendering lives here rather than being written twice. `WS` stands
    * in for the method a WebSocket route does not have, matching the key the duplicate check
    * already builds.
    */
  def describe: String = this match {
    case Http(method, pattern, _, _) => s"$method ${pattern.render}"
    case Ws(pattern, _, _)           => s"WS ${pattern.render}"
  }
}

object Route {

  /** A route eezo made rather than one the user wrote, which is what lets a handwritten route at
    * the same method and path win: the table drops the derived twin rather than refusing to boot.
    * The one spelling of that, for `Resource`'s seven and a guard's three.
    */
  private[eezo] def derived(method: Method, path: String, handler: Handler): Route =
    Http(method, PathPattern.parse(path), handler, Provenance.Derived)

  /** Mounts a set of routes under a prefix: the paths they answer on, and the URLs they emit.
    *
    * Moving the pattern alone is half a mount. A page whose links were built before the prefix
    * existed keeps pointing at the unmounted path, so an anchor 404s, a form posts nowhere and a
    * redirect lands outside the mount. Which URLs move is decided where they are written: a
    * `Url.Mounted` is the application's own and travels, a `String` and a `Url.Absolute` are
    * finished addresses and are left exactly as written.
    *
    * A plain function over `Seq[Route]` rather than a parameter on `Resource`, because `Route` is
    * public and that makes relocation free for handwritten routes too. Route transformation is the
    * general shape here: authorization and the middleware `modules/auth` will want are the same
    * `Seq[Route] => Seq[Route]`, so neither has to invent a mechanism.
    *
    * The prefix is normalised rather than validated, since `/admin`, `admin` and `/admin/` are one
    * intention written three ways, and rebuilding through `PathPattern.parse` is what keeps the
    * moved pattern under the same parse-time validation as the original. `Url.normalise` does that
    * normalising rather than a trim written here, because the prefix reaches the emitted URLs
    * through `Url` anyway: two spellings of one rule are two rules waiting to disagree, and a path
    * a route answers on that disagrees with the path its links point at is the bug this whole
    * function exists to prevent. `/` is the identity prefix, and an empty string spells it too.
    */
  def under(prefix: String)(routes: Seq[Route]): Seq[Route] = {
    val mount = Url.normalise(prefix)

    def moved(pattern: PathPattern): PathPattern =
      PathPattern.parse(s"$mount${pattern.render}")

    /** The other half of a mount: a page whose links stayed where the route no longer is is a page
      * of 404s. The handler is wrapped rather than told its prefix, so that nothing a user writes
      * has to know it is mounted, and the response comes back still mounted, which is what lets a
      * second `under` move it again.
      *
      * A WebSocket endpoint is not wrapped. It answers with frames rather than a `Response`, and
      * eezo has no rule for a URL inside one.
      */
    def mounting(handler: Handler): Handler = request => handler(request).under(mount)

    // The provenance travels with the route: moving a derived resource under `/admin` does not
    // make it something the user wrote, and a handwritten route at the new path still beats it.
    if (mount == "/") routes
    else
      routes.map {
        case Http(method, pattern, handler, provenance) =>
          Http(method, moved(pattern), mounting(handler), provenance)
        case Ws(pattern, endpoint, provenance) => Ws(moved(pattern), endpoint, provenance)
      }
  }
}

/** The routes an application serves, in the order they are matched.
  *
  * `Seq` order is the entire contract: the table never sorts, and `++` concatenates. Specificity
  * sorting was rejected because it is a rule a reader cannot see by reading the generated file top
  * to bottom, and that file is what people debug routing with. Rails, Phoenix and Play are all
  * declaration ordered for the same reason. The sbt plugin owns emit order.
  *
  * [[identify]] is who the table says is behind a request it serves, [[RouteTable.naming]] over the
  * `identify` of every declaration mounted into it: the generator writes that call, and a table
  * built by hand names nobody. It exists for the socket upgrade and for nothing else. A guarded
  * page is named by the guard's own wrapper, inside [[dispatch]] and inside `Csrf.protect` with it,
  * so a public page's handler reads nobody however the visitor signed in; an upgrade has no such
  * wrapper on a route nobody guarded, and the one thing that holds every declaration at once is
  * this table.
  */
final class RouteTable(mounted: Seq[Route], val identify: Request => Request) {

  /** [[Route.describe]] is the key the whole table is deduplicated on: it already renders the
    * method and the pattern for an HTTP route and `WS` plus the pattern for an upgrade, so the two
    * kinds share one key space without an HTTP route ever colliding with a WebSocket one.
    */
  private val handwrittenKeys: Set[String] = mounted.iterator
    .filter(_.provenance == Provenance.Handwritten)
    .map(_.describe)
    .toSet

  private def losesToHandwritten(route: Route): Boolean =
    route.provenance == Provenance.Derived && handwrittenKeys(route.describe)

  /** The derived routes a handwritten route on the same method and path replaced.
    *
    * Decision 18: a handwritten route wins over a derived one. A `GET /posts` written under `app/`
    * next to a `case class Post ... derives Resource` is the ordinary way to take over one page of
    * a resource and keep the other six, so it must boot rather than fail. The loser is dropped from
    * [[routes]] outright rather than left sitting behind the winner, which is what keeps it out of
    * the boot listing as well as out of dispatch.
    *
    * Pure, and deliberately not a log call, for the reason [[shadowed]] gives: `Eezo.start` is the
    * one site where the assembled table is the table the application actually serves.
    */
  val overridden: Seq[Route] = mounted.filter(losesToHandwritten)

  /** The routes served, in the order they are matched. */
  val routes: Seq[Route] = mounted.filterNot(losesToHandwritten)

  /** The two kinds, split once. `Eezo.run` reads `wsRoutes` to build its single WebSocket mapping
    * and hands `httpRoutes` to the Jetty handler, so neither walks past a route it cannot use.
    */
  val httpRoutes: Seq[Route.Http] = routes.collect { case route: Route.Http => route }

  val wsRoutes: Seq[Route.Ws] = routes.collect { case route: Route.Ws => route }

  locally {
    // Whatever key survives twice here is two handwritten routes or two derived ones, because the
    // one pairing with a rule to settle it has already been settled. Neither survivor has a reading
    // under which the user meant it, and picking one of them silently would hide the mistake.
    val keys = routes.map(_.describe)
    keys.diff(keys.distinct).headOption.foreach { key =>
      throw new IllegalArgumentException(
        s"duplicate route: $key is mounted twice. Shadowing is legal and ordered; " +
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
    *
    * Between the match and the handler sits the CSRF token, [[Csrf.protect]]: after the match, so a
    * 404 stays a 404 and a 405 a 405; before the handler and before any wrapper a guard puts around
    * it, so a forged `POST` to a guarded route is refused as forged, never redirected to login.
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
      case Some((handler, params)) => Csrf.protect(handler)(request.copy(pathParams = params))
      case None                    =>
        val methods = allowed.result().distinct
        if (methods.isEmpty) throw NotFound(request.path)
        else throw MethodNotAllowed(methods)
    }
  }

  /** Finds the first WebSocket route whose pattern matches, if any.
    *
    * The WebSocket half of `dispatch`: no method to disagree on, since an upgrade is always a
    * `GET`, so one pass with no `Allow` bookkeeping is the whole job. The single caller, `Eezo`'s
    * WebSocket creator, decides the 404 itself: Jetty requires that decision to complete a
    * `Callback` rather than throw.
    */
  def dispatchWs(path: String): Option[(Route.Ws, Map[String, String])] =
    wsRoutes.iterator
      .flatMap(route => route.pattern.matchPath(path).map(params => (route, params)))
      .nextOption()

  /** Every pair where an earlier route swallows a later one, in table order.
    *
    * Pure, and deliberately not a log call: a warning in this constructor would fire in every test
    * that builds a shadowing table on purpose, and once more for each intermediate `++` produces.
    * `Eezo.start` is the single site that emits, because the assembled table it serves is the only
    * one where shadowing is a defect rather than a step.
    *
    * Never an error. First-match order is what lets a handwritten `/widgets/new` beat a derived
    * `/widgets/:id`, so shadowing is legal by construction; only the duplicate the constructor
    * rejects, two routes of the same provenance on one method and path, is a bug with no reading
    * under which it was meant. Both kinds are checked against their own kind, since `dispatch` and
    * `dispatchWs` walk separate partitions and an HTTP route cannot swallow an upgrade.
    */
  def shadowed: Seq[(Route, Route)] = {
    def pairs(kind: Seq[Route]): Seq[(Route, Route)] =
      kind.zipWithIndex.flatMap { case (earlier, index) =>
        kind.drop(index + 1).collect {
          case later if shadows(earlier, later) => (earlier, later)
        }
      }

    pairs(httpRoutes) ++ pairs(wsRoutes)
  }

  private def shadows(earlier: Route, later: Route): Boolean = (earlier, later) match {
    case (Route.Http(method, pattern, _, _), Route.Http(otherMethod, otherPattern, _, _)) =>
      method == otherMethod && pattern.subsumes(otherPattern)
    case (Route.Ws(pattern, _, _), Route.Ws(otherPattern, _, _)) => pattern.subsumes(otherPattern)
    case _                                                       => false
  }

  /** Concatenation. Order is preserved, so the receiver's routes keep winning.
    *
    * The two namings are composed rather than one of them chosen, because the framework routes an
    * application appends, `HttpApp.serve`'s `table ++ RouteTable(frameworkRoutes)`, name nobody and
    * must not cost the application the naming its own declarations composed.
    */
  def ++(other: RouteTable): RouteTable =
    new RouteTable(routes ++ other.routes, identify.andThen(other.identify))
}

object RouteTable {

  /** A table over these routes, naming whoever `identify` names, and nobody when it is left out.
    *
    * The default is the one thing to be careful with, because it is silent. An application that
    * rebuilds the generated table by hand, to mount half of it under a prefix or to reorder it, has
    * the routes of a table that names the current user and is one argument away from a table that
    * names nobody: `RouteTable(table.routes)` compiles, serves every page exactly as before, and
    * turns every socket upgrade anonymous, with nothing in the request to say whether it was never
    * named or honestly named nobody. A table built from another table's routes therefore passes
    * that table's `identify` along with them, `RouteTable(rearranged, table.identify)`, and
    * examples/blog is the worked case. The default is for a table whose routes were never behind a
    * declaration at all, the framework's own routes among them.
    */
  def apply(routes: Seq[Route], identify: Request => Request = identity): RouteTable =
    new RouteTable(routes, identify)

  val empty: RouteTable = RouteTable(Seq.empty)

  /** The one naming function a table is handed, composed out of what every declaration mounted into
    * it names with. The generated `Routes.table()` calls this with the `identify` of the very
    * declarations its rows were mounted behind, in the order they were mounted, so no route can be
    * wrapped by one declaration and named by another.
    *
    * `distinct` for the reason the rows themselves are taken `distinct`: one guard hands the same
    * function to every declaration it makes, so an application with twenty guarded things reads the
    * session once per upgrade rather than twenty times. Equality on a function value is equality of
    * the reference and nothing else, which is exactly what a shared instance wants. A declaration
    * that names nobody hands the request straight back, so composing one costs an application with
    * no guard nothing it can measure.
    */
  def naming(namers: Seq[Request => Request]): Request => Request = Function.chain(namers.distinct)
}
