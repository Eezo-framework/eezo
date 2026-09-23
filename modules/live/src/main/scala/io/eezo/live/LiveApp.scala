package io.eezo.live

import io.eezo.http.{HttpApp, Route}

/** The live layer, mixed into an http-edge application: everything [[Live.mount]] needs on the
  * server, contributed through `frameworkRoutes` so every way of serving — `boot`'s default, an
  * overridden `boot` calling `serve`, `dev` — carries it without knowing.
  *
  * The umbrella's `EezoApp` extends this, so an application on `eezo` mounts components with no
  * configuration at all. An application on `eezo-http` alone opts in by writing `extends LiveApp`
  * instead of `extends HttpApp`. The cost when unused is one WebSocket route, one script route, and
  * an idle registry.
  */
trait LiveApp extends HttpApp {

  /** Origins other than the server's own whose pages may open a live socket, each written
    * `scheme://host` or `scheme://host:port`. Empty by default, because the page and its socket
    * come from the same server unless something in between rewrites the `Host` the browser asked
    * for. That something is usually a proxy, and forwarding the browser's `Host` is the better fix;
    * this list is for when it cannot. Matching is exact, with no wildcard, since a pattern that
    * admits a subdomain admits whoever controls that subdomain. An entry that is not an origin
    * fails the boot.
    */
  protected def allowedOrigins: Set[String] = Set.empty

  override protected def frameworkRoutes: Seq[Route] =
    super.frameworkRoutes ++ Live.routes(allowedOrigins)
}
