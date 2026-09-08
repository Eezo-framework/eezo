package io.eezo.http

import io.eezo.core.Dispatch
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
  * same overrides; the umbrella overrides [[devServer]] to run the drift check first.
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

  /** How the application's own failures are answered: a partial function from what a handler threw
    * to the problem the client sees. What it does not cover, the boundary answers as 500.
    */
  def problems: PartialFunction[Throwable, Problem] = Config.DefaultProblems

  /** The application. The default serves [[routes]] on [[port]], which is what makes the minimal
    * application one override and nothing else. Override it for anything more: background work,
    * another setup, no server at all.
    */
  def boot(): Unit = serve(routes)

  /** The only caller of `Eezo.run`: boots the server on this trait's overrides and blocks until it
    * stops. `dev` turns the listing and the reload client on.
    */
  protected final def serve(table: RouteTable, dev: Boolean = false): Unit =
    Eezo.run(port, table, maxBodySize, dev, problems)

  /** What `dev` serves. The umbrella overrides it to run the drift check first and serve the drift
    * page when the check blocks.
    */
  protected def devServer(): Unit = serve(routes, dev = true)

  override protected def commands: PartialFunction[List[String], Int] = ({
    case Nil               => boot(); 0
    case "dev" :: _        => devServer(); 0
    case "routes" :: flags =>
      val result = Commands.routes(routes)
      println(if (flags.contains("--json")) RenderJson.routes(result) else Render.routes(result))
      0
  }: PartialFunction[List[String], Int]) orElse super.commands

  override protected def usage: List[String] = List(
    "dev               serve with the route listing and the reload client on",
    "routes [--json]   the mounted table, with boot's warnings"
  ) ++ super.usage
}
