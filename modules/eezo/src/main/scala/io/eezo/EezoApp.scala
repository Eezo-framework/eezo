package io.eezo

import io.eezo.cli.{Commands, MigrateResult, Render, RenderJson}
import io.eezo.db.{DbInit, Schema, SchemaError}
import io.eezo.db.Scopes.{read, transact}
import io.eezo.db.engine.Installed
import io.eezo.db.migrate.Decision
import io.eezo.db.schema.Change
import io.eezo.http.{Eezo, RouteTable}

import scala.io.StdIn

/** The entry point an application extends: the one trait, in the one package.
  *
  * {{{
  * object Main extends EezoApp {
  *   override def schema: Schema     = AppSchema
  *   override def routes: RouteTable = Routes.table()
  * }
  * }}}
  *
  * `main` dispatches (design/cli.md §4): a recognised first argument runs a command from
  * `io.eezo.cli`, and anything else — including no arguments — is the application, so
  * `sbt "run status"` works with no plugin involved and `sbt run` boots. The members are ordinary
  * values the app names explicitly, never found by reflection, for the reason
  * `examples/blog/src/main/scala/Main.scala` gave when `Routes.table()` was made a parameter: a
  * wrong name should be a compile error here, not a runtime message.
  *
  * A `Database` is installed only around what needs one. `routes`, `ddl`, `dump`, `freeze` and
  * `help` never touch it, so they work with the database down; `boot` gets one installed for its
  * whole duration, exactly as `io.eezo.db.EezoApp` did. Today installing is free even for an app
  * with no database at all, because `Pool` opens connections per use and `Database.connect` never
  * touches the network — if the pool ever becomes eager, the `boot` arm below is the one that has
  * to become lazy, and `design/cli.md` §4 already asks for that.
  */
trait EezoApp extends DbInit {

  /** The application's schema — `object AppSchema extends Schema` named here, the same way
    * [[routes]] names the generated table. Defaults to no tables, so an application without a
    * database overrides nothing and the drift commands answer "in sync".
    */
  def schema: Schema = Schema.empty

  /** The route table, usually `Routes.table()` from the sbt plugin's generated object. */
  def routes: RouteTable = RouteTable.empty

  /** The Postgres schema the drift commands introspect. `search_path` is set per connection in
    * [[databaseInit]]; this is the same name, told to the side that reads catalogs.
    */
  def databaseSchema: String = "public"

  /** Where [[boot]]'s default and the `dev` command serve. */
  def port: Int = 8080

  /** The application. A `Database` is installed for its whole duration.
    *
    * The default serves [[routes]] on [[port]], which is what makes the minimal application one
    * override — `routes` — and nothing else. Override it for anything more: a different server
    * setup, background work, no server at all.
    */
  def boot(args: Array[String]): Unit = {
    val _ = args
    Eezo.run(port = port, routes = routes)
  }

  final def main(args: Array[String]): Unit = {
    val code =
      try dispatch(args)
      catch {
        case e: SchemaError =>
          if (args.contains("--json")) System.err.println(RenderJson.error(e.getMessage))
          else System.err.println(s"\n[eezo] ${e.getMessage}\n")
          1
      }
    // Only a failure exits explicitly: `sys.exit(0)` on the happy path would tear down anything
    // the process still owes — a test harness, an embedding — for no benefit.
    if (code != 0) sys.exit(code)
  }

  private def dispatch(args: Array[String]): Int = {
    // `--json` renders the same result values through `RenderJson` instead of `Render` — the
    // machine-readable half of design/objective.md's dev loop. Exit codes are identical in both
    // modes, so a caller gates on the code and parses the body.
    val json = args.contains("--json")

    args.toList match {
      case "status" :: _ =>
        val result = withDatabase(read { Commands.status(schema, databaseSchema) })
        println(if (json) RenderJson.status(result) else Render.status(result))
        if (result.inSync) 0 else 1

      case "sync" :: rest =>
        val apply  = rest.contains("--apply")
        val force  = rest.contains("--force")
        val result = withDatabase(transact { Commands.sync(schema, apply, force, databaseSchema) })
        println(
          if (json) RenderJson.sync(result, applyRequested = apply)
          else Render.sync(result, applyRequested = apply)
        )
        if (apply && !result.applied && result.changes.nonEmpty) 1 else 0

      case "freeze" :: rest =>
        // Everything that is not a flag is the name, joined: `run freeze add isbn to book` names
        // the migration "add isbn to book" without the caller having to fight sbt's
        // space-splitting argument parser with quotes.
        val name = rest.filterNot(_.startsWith("--")).mkString(" ")
        if (name.isEmpty) throw SchemaError("freeze needs a name: eezo freeze add isbn to book")
        val result = Commands.freeze(schema, name, freezePolicy(rest, json))
        println(if (json) RenderJson.freeze(result) else Render.freeze(result))
        0

      case "migrate" :: rest =>
        val apply  = rest.contains("--apply")
        val result = withDatabase(transact {
          Commands.migrate(schema, apply, dbSchema = databaseSchema)
        })
        println(if (json) RenderJson.migrate(result) else Render.migrate(result))
        result match {
          case MigrateResult.Tampered(_)                       => 1
          case MigrateResult.UpToDate(drift) if drift.nonEmpty => 1
          case MigrateResult.Applied(_, drift)                 => if (drift.isEmpty) 0 else 1
          case _                                               => 0
        }

      case "reset" :: _ =>
        val result = withDatabase(transact { Commands.reset(schema, databaseSchema) })
        println(if (json) RenderJson.reset(result) else "reset ✓")
        0

      case "drop" :: _ =>
        val result = withDatabase(transact { Commands.drop(databaseSchema) })
        println(if (json) RenderJson.drop(result) else "dropped ✓")
        0

      case "dev" :: _ =>
        withDatabase(DevServer.serve(schema, databaseSchema, port, routes))
        0

      case "routes" :: _ =>
        val result = Commands.routes(routes)
        println(if (json) RenderJson.routes(result) else Render.routes(result))
        0

      case "dump" :: _ =>
        println(if (json) RenderJson.dump(schema.snapshot) else Commands.dump(schema))
        0

      case "ddl" :: _ =>
        if (json) println(RenderJson.ddl(Commands.ddl(schema)))
        else Commands.ddl(schema).foreach(s => println(s + ";"))
        0

      case "help" :: _ =>
        println(help)
        0

      case _ =>
        withDatabase(boot(args))
        0
    }
  }

  /** How `freeze` decides without a terminal. `--accept-all` and `--skip-destructive` are the two
    * canned policies an agent or a script names explicitly; bare `--json` implies
    * `--skip-destructive`, because a machine caller must never hang on a prompt and skip is the
    * choice that loses nothing.
    */
  private def freezePolicy(flags: List[String], json: Boolean): Change => Decision =
    if (flags.contains("--accept-all")) _ => Decision.Accept
    else if (flags.contains("--skip-destructive") || json)
      change => if (change.destructive) Decision.Skip else Decision.Accept
    else decide

  /** The interactive freeze policy: safe changes pass, destructive ones prompt. An agent or a
    * script gets its own policy through flags in a later stage; the mechanism — `Commands.freeze`'s
    * `decide` — is the same either way.
    */
  private def decide(change: Change): Decision =
    if (!change.destructive) Decision.Accept
    else {
      println(s"\n  ⚠ ${change.describe}")
      print("    [d] drop (data lost)   [k] keep, skip\n  > ")
      if (StdIn.readLine().trim.toLowerCase == "k") Decision.Skip else Decision.Accept
    }

  /** `io.eezo.db.EezoApp`'s lifecycle, scoped to one command: built, installed, closed. */
  private def withDatabase[A](f: => A): A = {
    val db = database
    Installed.install(db)
    try f
    finally {
      Installed.uninstall()
      db.close()
    }
  }

  private def help: String =
    """eezo
      |  dev               serve with the drift check and the route listing on
      |  status            code vs live database
      |  sync [--apply] [--force]
      |                    apply the code/db diff directly (dev only)
      |  freeze <name>     write a migration from schema.json -> code
      |  migrate [--apply] apply pending migrations, then verify
      |  reset             drop everything and recreate from the model
      |  drop              drop everything
      |  routes            the mounted table, with boot's warnings
      |  dump              print the derived snapshot
      |  ddl               print full DDL
      |
      |every command takes --json for machine-readable output (same exit codes);
      |freeze takes --accept-all / --skip-destructive in place of the prompt
      |(bare --json implies --skip-destructive)
      |
      |anything else runs the application""".stripMargin
}
