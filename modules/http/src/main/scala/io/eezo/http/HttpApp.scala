package io.eezo.http

import io.eezo.core.Dispatch
import io.eezo.core.Dispatch.Usage
import io.eezo.http.cli.{Commands, Render, RenderJson}

/** The http edge's entry trait: the one an application on `eezo-http` alone extends.
  *
  * {{{
  * object Main extends HttpApp {
  *   override def routes: RouteTable = Routes.table()
  * }
  * }}}
  *
  * The edge is declarative. The application names its [[routes]], and every knob of the server is
  * an override beside [[port]]; nothing in user code calls `Eezo.run`. `main` is inherited from
  * `Dispatch`: `sbt run` boots, `sbt "run dev"` (or the plugin's `eezoDev`) serves with the listing
  * and the reload client on, `sbt "run routes"` prints the table, and any other first argument is
  * unknown. The database edge's commands do not exist here: an application that needs them depends
  * on `eezo` and extends `EezoApp`, which stacks both edges.
  *
  * [[serve]] is the only call site of `Eezo.run` in the entry traits, so [[boot]] and `dev` see the
  * same overrides. Both are virtual hooks the umbrella overrides: [[program]] to run `boot` under
  * the database, [[devServer]] to run the drift check first.
  */
trait HttpApp extends Dispatch {

  /** The route table, usually `Routes.table()` from the sbt plugin's generated object. Abstract: an
    * http edge is its routes, and an application without any is a mistake.
    */
  def routes: RouteTable

  /** Where [[boot]]'s default and the `dev` command serve. */
  def port: Int = 8080

  /** The request body cap, and the WebSocket text message cap with it. */
  def maxBodySize: Long = Config.DefaultMaxBodySize

  /** The key the session cookie is signed with. `EEZO_SECRET` by default. The sbt plugin's dev loop
    * sets it to one secret per sbt session, so a restart on edit keeps the developer signed in. A
    * run outside that loop with nothing set gets a throwaway announced at WARNING, so that `hello`
    * runs with no configuration and a deployment that forgot is told. Read once, when [[serve]]
    * boots.
    */
  def secret: Secret = Secret.fromEnv()

  /** How the application's own failures are answered: a partial function from what a handler threw
    * to the problem the client sees. What it does not cover, the boundary answers as 500.
    */
  def problems: PartialFunction[Throwable, Problem] = Config.DefaultProblems

  /** The application. The default serves [[routes]] on [[port]], which is what makes the minimal
    * application one override and nothing else. Override it for anything more: background work,
    * another setup, no server at all.
    */
  def boot(): Unit = serve(routes)

  /** Routes the framework itself contributes, appended to whatever table [[serve]] is given so that
    * every caller — `boot`'s default, a user's own `serve` in an overridden `boot`, `dev` — serves
    * them without knowing they exist. Empty here: `eezo-live`'s `LiveApp` overrides it with the
    * live socket and its client script, and `EezoApp` mixes that in. Appended, so the user's table
    * keeps winning any path both name; they show in the boot listing (they are served, and the
    * listing does not lie) but not in `eezo routes`, which is the user's table.
    */
  protected def frameworkRoutes: Seq[Route] = Nil

  /** The only caller of `Eezo.run`: boots the server on this trait's overrides and blocks until it
    * stops. `dev` turns the listing and the reload client on.
    */
  protected final def serve(table: RouteTable, dev: Boolean = false): Unit =
    Eezo.run(port, table ++ RouteTable(frameworkRoutes), maxBodySize, dev, problems)

  /** What `dev` serves. The umbrella overrides it to run the drift check first and serve the drift
    * page when the check blocks.
    */
  protected def devServer(): Unit = serve(routes, dev = true)

  /** What `sbt run` does on this edge alone: [[boot]], under nothing. The umbrella overrides it to
    * run the same `boot` under the database edge's lifecycle, the way it overrides [[devServer]].
    * No `override` here, deliberately: see `Dispatch.program`.
    */
  protected def program(): Unit = boot()

  override protected def commands: PartialFunction[List[String], Int] = ({
    case "dev" :: _        => devServer(); 0
    case "routes" :: flags =>
      val result = Commands.routes(routes)
      emit(flags)(RenderJson.routes(result), Render.routes(result))
      0
  }: PartialFunction[List[String], Int]) orElse super.commands

  override protected def usage: List[Usage] = List(
    Usage("dev", "serve with the route listing and the reload client on"),
    Usage("routes", "the mounted table, with boot's warnings")
  ) ++ super.usage
}
