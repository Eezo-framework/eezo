package io.eezo

import io.eezo.db.DbApp
import io.eezo.http.HttpApp

/** The entry point an application on the umbrella extends: both edges, one trait, one package.
  *
  * {{{
  * object Main extends EezoApp {
  *   override def schema: Schema     = AppSchema
  *   override def routes: RouteTable = Routes.table()
  * }
  * }}}
  *
  * Nothing here is new: `HttpApp` brings `routes`, the server's overrides, `boot`'s default of
  * serving, and the `dev` and `routes` commands; `DbApp` brings `schema`, the database lifecycle,
  * and the schema commands. The order `HttpApp with DbApp` is load-bearing and this trait is
  * written once: each edge chains its commands in front of `super`'s, so the later mixin's arms
  * win, and `DbApp`'s empty-argument arm — the program, with a `Database` installed — shadows
  * `HttpApp`'s. `boot` is concrete in `HttpApp` and abstract in `DbApp`, so an application inherits
  * "serve" and a `Main` that overrides it still wins.
  *
  * The one override is the database edge's contribution to `dev`: the drift check before the server
  * comes up, and the refusal page in place of the app while the drift is dangerous.
  */
trait EezoApp extends HttpApp with DbApp {

  override protected def devServer(): Unit = withDatabase {
    DriftGate(schema, databaseSchema) match {
      case Some(page) => serve(page, dev = true)
      case None       => super.devServer()
    }
  }
}
