package io.eezo.db

import io.eezo.core.Dispatch
import io.eezo.db.Scopes.{read, transact}
import io.eezo.db.cli.{Commands, MigrateResult, Render, RenderJson}
import io.eezo.db.engine.Installed
import io.eezo.db.migrate.Decision
import io.eezo.db.schema.Change

import scala.annotation.retainsCap
import scala.caps.unsafe.unsafeAssumePure
import scala.io.StdIn

/** The database edge's entry trait: the one an application on `eezo-db` alone extends.
  *
  * {{{
  * object Main extends DbApp {
  *   override def schema: Schema = AppSchema
  *   override def boot(): Unit   = transact { /* a job over rows */ }
  * }
  * }}}
  *
  * The edge is declarative. The application names its [[schema]] and says what [[boot]] does; the
  * framework builds the `Database` from `DbInit`'s overrides, installs it around the program, and
  * closes it after. `main` is inherited from `Dispatch`: `sbt run` runs the program once with a
  * database installed, and `sbt "run status|sync|freeze|migrate|reset|drop|dump|ddl"` are the
  * schema commands (design/cli.md §4). `dev` and `routes` are the http edge's and do not exist
  * here: `eezoDev` on a database-only application prints the unknown-command line on every save,
  * and a rerun-on-save loop for a job is sbt's own `~run`.
  *
  * A `Database` is installed only around what needs one. `ddl`, `dump`, `freeze` and `help` never
  * touch it, so they work with the database down; the program and the other commands get one for
  * their whole duration. Today installing is free even when nothing queries, because `Pool` opens
  * connections per use and `Database.connect` never touches the network — if the pool ever becomes
  * eager, the `Nil` arm below is the one that has to become lazy.
  */
trait DbApp extends Dispatch with DbInit {

  /** The application's schema — `object AppSchema extends Schema` named here. Abstract: a database
    * edge is its schema, and an application without one is a mistake.
    */
  def schema: Schema

  /** The Postgres schema the drift commands introspect. `search_path` is set per connection in
    * [[databaseInit]]; this is the same name, told to the side that reads catalogs.
    */
  def databaseSchema: String = "public"

  /** The program: what `sbt run` does, once, with a `Database` installed. Abstract, because a
    * database-only application is its program. The umbrella supplies the http edge's default, which
    * is to serve.
    */
  def boot(): Unit

  /** The lifecycle, scoped to one command: built, installed, closed. Final, and protected rather
    * than private so the umbrella can wrap the dev server in it.
    */
  protected final def withDatabase[A](f: => A): A = {
    val db = database
    Installed.install(db)
    try f
    finally {
      Installed.uninstall()
      db.close()
    }
  }

  override protected def commands: PartialFunction[List[String], Int] =
    own.unsafeAssumePure orElse super.commands

  /** This edge's arms: the program, and the eight schema commands. The patterns are the one list of
    * what this edge answers; `isDefinedAt` over them runs no body. The capture annotation is `^`
    * spelled the way scalafmt can parse: the literal closes over `this`, and [[own]] is where that
    * is asserted away.
    */
  private def arms: PartialFunction[List[String], Int] @retainsCap = {
    case Nil =>
      withDatabase(boot())
      0

    case "status" :: flags =>
      val result = withDatabase(read { Commands.status(schema, databaseSchema) })
      println(if (json(flags)) RenderJson.status(result) else Render.status(result))
      if (result.inSync) 0 else 1

    case "sync" :: flags =>
      val apply  = flags.contains("--apply")
      val force  = flags.contains("--force")
      val result = withDatabase(transact { Commands.sync(schema, apply, force, databaseSchema) })
      println(
        if (json(flags)) RenderJson.sync(result, applyRequested = apply)
        else Render.sync(result, applyRequested = apply)
      )
      if (apply && !result.applied && result.changes.nonEmpty) 1 else 0

    case "freeze" :: rest =>
      // Everything that is not a flag is the name, joined: `run freeze add isbn to book` names
      // the migration "add isbn to book" without the caller having to fight sbt's
      // space-splitting argument parser with quotes.
      val name = rest.filterNot(_.startsWith("--")).mkString(" ")
      if (name.isEmpty) throw SchemaError("freeze needs a name: eezo freeze add isbn to book")
      val result = Commands.freeze(schema, name, freezePolicy(rest))
      println(if (json(rest)) RenderJson.freeze(result) else Render.freeze(result))
      0

    case "migrate" :: flags =>
      val apply  = flags.contains("--apply")
      val result = withDatabase(transact {
        Commands.migrate(schema, apply, dbSchema = databaseSchema)
      })
      println(if (json(flags)) RenderJson.migrate(result) else Render.migrate(result))
      result match {
        case MigrateResult.Tampered(_)       => 1
        case MigrateResult.UpToDate(drift)   => if (drift.isEmpty) 0 else 1
        case MigrateResult.Applied(_, drift) => if (drift.isEmpty) 0 else 1
        case MigrateResult.Pending(_)        => 0
      }

    case "reset" :: flags =>
      val result = withDatabase(transact { Commands.reset(schema, databaseSchema) })
      println(if (json(flags)) RenderJson.reset(result) else "reset ✓")
      0

    case "drop" :: flags =>
      val result = withDatabase(transact { Commands.drop(databaseSchema) })
      println(if (json(flags)) RenderJson.drop(result) else "dropped ✓")
      0

    case "dump" :: flags =>
      println(if (json(flags)) RenderJson.dump(schema.snapshot) else Commands.dump(schema))
      0

    case "ddl" :: flags =>
      if (json(flags)) println(RenderJson.ddl(Commands.ddl(schema)))
      else Commands.ddl(schema).foreach(s => println(s + ";"))
      0
  }

  override protected def usage: List[String] = List(
    "status            code vs live database",
    "sync [--apply] [--force]",
    "                  apply the code/db diff directly (dev only)",
    "freeze <name>     write a migration from schema.json -> code",
    "migrate [--apply] apply pending migrations, then verify",
    "reset             drop everything and recreate from the model",
    "drop              drop everything",
    "dump              print the derived snapshot",
    "ddl               print full DDL",
    "",
    "freeze takes --accept-all / --skip-destructive in place of the prompt",
    "(bare --json implies --skip-destructive)",
    ""
  ) ++ super.usage

  /** [[arms]] behind the `SchemaError` catch. The catch is here and not in `core`, which cannot see
    * the type; the http edge has nothing that throws it. Exit 1, through `Dispatch.fail` so the
    * line is JSON under `--json`.
    *
    * The type is inferred and then asserted pure at the `commands` site, deliberately.
    * `Dispatch.commands` is typed in `core`, outside capture checking, so there it reads as a pure
    * function, while this object closes over `this`, which the checker counts as a capability
    * (DESIGN §8.8, research/capture-checking.md §3). The assertion holds because the application
    * carries no capability: `DbInit`'s hook is a `->`, and every other member is a value.
    */
  private def own = new PartialFunction[List[String], Int] {
    def isDefinedAt(args: List[String]): Boolean = arms.isDefinedAt(args)

    def apply(args: List[String]): Int =
      try arms(args)
      catch {
        case e: SchemaError =>
          fail(args, e.getMessage)
          1
      }
  }

  /** How `freeze` decides without a terminal. `--accept-all` and `--skip-destructive` are the two
    * canned policies an agent or a script names explicitly; bare `--json` implies
    * `--skip-destructive`, because a machine caller must never hang on a prompt and skip is the
    * choice that loses nothing.
    */
  private def freezePolicy(flags: List[String]): Change => Decision =
    if (flags.contains("--accept-all")) _ => Decision.Accept
    else if (flags.contains("--skip-destructive") || json(flags))
      change => if (change.destructive) Decision.Skip else Decision.Accept
    else decide

  /** The interactive freeze policy: safe changes pass, destructive ones prompt. An agent or a
    * script gets its own policy through flags; the mechanism — `Commands.freeze`'s `decide` — is
    * the same either way.
    */
  private def decide(change: Change): Decision =
    if (!change.destructive) Decision.Accept
    else {
      println(s"\n  ⚠ ${change.describe}")
      print("    [d] drop (data lost)   [k] keep, skip\n  > ")
      if (StdIn.readLine().trim.toLowerCase == "k") Decision.Skip else Decision.Accept
    }
}
