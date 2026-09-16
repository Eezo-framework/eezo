package io.eezo

import io.eezo.db.DbApp
import io.eezo.http.HttpApp
import io.eezo.live.LiveApp

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
  * serving, and the `dev` and `routes` commands; `LiveApp` brings the live socket and client, so
  * `Live.mount` works with no configuration; `DbApp` brings `schema`, the database lifecycle, and
  * the schema commands. `boot` is concrete in `HttpApp` and abstract in `DbApp`, so an application
  * inherits "serve" regardless of which of the two supertypes below is written first, and a `Main`
  * that overrides `boot` still wins.
  *
  * What is this trait's own is the one idea both overrides spell: the http edge runs under the
  * database. `program` is concrete in both edges, `boot()` in one and `withDatabase(boot())` in the
  * other, so the compiler refuses any trait that stacks the two until it overrides `program` and
  * says which; this one says the database edge's. A user's own `extends DbApp with HttpApp` fails
  * the same way, in either order, instead of booting with no `Database` installed. `dev` composes
  * through `devServer` alike: the drift check before the server comes up, and the refusal page in
  * place of the app while the drift is dangerous, all under the same `Database`.
  */
trait EezoApp extends HttpApp with LiveApp with DbApp {

  /** The http edge's `boot` under the database edge's lifecycle: the one line the compiler makes
    * this trait write.
    */
  override protected def program(): Unit = withDatabase(boot())

  override protected def devServer(): Unit = withDatabase {
    DriftGate(schema, databaseSchema) match {
      case Some(page) => serve(page, dev = true)
      case None       => super.devServer()
    }
  }
}
