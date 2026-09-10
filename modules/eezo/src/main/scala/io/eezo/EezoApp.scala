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
  * and the schema commands. `boot` is concrete in `HttpApp` and abstract in `DbApp`, so an
  * application inherits "serve" regardless of which of the two supertypes below is written first,
  * and a `Main` that overrides `boot` still wins.
  *
  * The program, no arguments, used to be the one place mixin order mattered: each edge writes
  * `own orElse super.commands`, so whichever of `HttpApp` and `DbApp` is written second answered
  * `Nil` first, and only `DbApp`'s answer installs a `Database` around `boot`. That made the single
  * line below, `extends HttpApp with DbApp`, the only thing standing between a correct application
  * and one that boots with no `Database` installed, with nothing but convention saying so.
  * `commands` answers `Nil` itself, through `super[DbApp]` by name rather than through whichever
  * edge the linearization happens to favour, so the order the two supertypes are written in no
  * longer changes what the program runs under.
  *
  * The other override is the database edge's contribution to `dev`: the drift check before the
  * server comes up, and the refusal page in place of the app while the drift is dangerous. It needs
  * no such pinning, because `DbApp` never defines `devServer` at all.
  */
trait EezoApp extends HttpApp with DbApp {

  /** `Nil`, the program itself, answered through `DbApp` by name: see the trait scaladoc for why.
    * Every other command still reaches both edges through `super.commands`, unchanged.
    */
  override protected def commands: PartialFunction[List[String], Int] =
    ({ case Nil => super[DbApp].commands(Nil) }: PartialFunction[List[String], Int]) orElse
      super.commands

  override protected def devServer(): Unit = withDatabase {
    DriftGate(schema, databaseSchema) match {
      case Some(page) => serve(page, dev = true)
      case None       => super.devServer()
    }
  }
}
