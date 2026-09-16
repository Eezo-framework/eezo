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

  override protected def frameworkRoutes: Seq[Route] = super.frameworkRoutes ++ Live.routes
}
