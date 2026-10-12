package io.eezo.db

import io.eezo.core.Dispatch
import io.eezo.core.Dispatch.Usage
import io.eezo.db.Scopes.{read, transact}
import io.eezo.db.cli.{Commands, MigrateResult, Render, RenderJson}
import io.eezo.db.engine.Installed
import io.eezo.db.migrate.Decision
import io.eezo.db.schema.Change

import scala.annotation.retainsCap
import scala.caps.unsafe.unsafeAssumePure
import scala.io.StdIn

/** The database layer's entry trait: the one an application on `eezo-db` alone extends.
  *
  * {{{
  * object Main extends DbApp {
  *   override def schema: Schema = AppSchema
  *   override def boot(): Unit   = transact { /* a job over rows */ }
  * }
  * }}}
  *
  * The layer is declarative. The application names its [[schema]] and says what [[boot]] does; the
  * framework builds the `Database` from `DbInit`'s overrides, installs it around the program, and
  * closes it after. `main` is inherited from `Dispatch`: `sbt run` runs the program once with a
  * database installed, and `eezo db status|sync|freeze|migrate|reset|drop|dump|ddl` are the schema
  * commands (design/cli.md §4), one namespace so that `help` reads as the application's commands
  * and then the database's. `db` alone, or `db help`, lists that namespace by itself. The bare
  * spellings (`migrate --apply` without `db`) are still answered, silently: a deploy's release
  * command written before the namespace existed must keep running in the image that carries this
  * code. `dev` and `routes` are the http layer's and do not exist here: `eezoDev` on a database
  * only application prints the unknown command line on every save, and a rerun on save loop for a
  * job is sbt's own `~run`.
  *
  * A `Database` is installed only around what needs one. `ddl`, `dump`, `freeze` and `help` never
  * touch it, so they work with the database down; the program and the other commands get one for
  * their whole duration. Installing never waits on the network: the pool starts empty
  * (`initializationFailTimeout` negative), so `Database.connect` returns at once and the pool fills
  * in the background. It is deliberately not eager, as Phoenix is not: with Postgres down, `dev`
  * keeps serving every page that needs no database, and `sbt run` fails on the first query that
  * needs Postgres, after the acquire timeout, rather than refusing to start.
  */
trait DbApp extends Dispatch with DbInit {

  /** The application's schema, `object AppSchema extends Schema` named here. Abstract: a database
    * layer is its schema, and an application without one is a mistake.
    */
  def schema: Schema

  /** The Postgres schema the drift commands introspect. `search_path` is set per connection in
    * [[databaseInit]]; this is the same name, told to the side that reads catalogs.
    */
  def databaseSchema: String = "public"

  /** The program: what `sbt run` does, once, with a `Database` installed. Abstract, because a
    * database only application is its program. The umbrella supplies the http layer's default,
    * which is to serve.
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

  /** What `sbt run` does on this layer: [[boot]] with a `Database` installed around it. Concrete
    * here and in the http layer both, so a trait that stacks the two inherits two and must say
    * which one it means; the umbrella says this one. No `override` here, deliberately: see
    * `Dispatch.program`.
    */
  protected def program(): Unit = withDatabase(boot())

  override protected def commands: PartialFunction[List[String], Int] = guarded.unsafeAssumePure

  /** This layer's arms: the `db` namespace over [[schemaArms]], its own `help`, its own unknown
    * command answer, and the bare spellings as an alias (see the trait's note). The capture
    * annotation is `^` spelled the way scalafmt can parse: the literal closes over `this`, and
    * [[guarded]] is where that is asserted away.
    */
  private def arms: PartialFunction[List[String], Int] @retainsCap = {
    val bare = schemaArms

    {
      case "db" :: Nil                            => println(dbHelp); 0
      case "db" :: "help" :: _                    => println(dbHelp); 0
      case "db" :: rest if bare.isDefinedAt(rest) => bare(rest)
      case "db" :: rest                           =>
        fail(rest, s"unknown db command: ${rest.mkString(" ")}. Run `db help` for the list.")
        2
      case rest if bare.isDefinedAt(rest) => bare(rest)
    }
  }

  /** The eight schema commands, spelled without the namespace. The patterns are the one list of
    * what this layer answers; `isDefinedAt` over them runs no body.
    */
  private def schemaArms: PartialFunction[List[String], Int] @retainsCap = {
    // Read once, outside every closure below. `read` and `transact` take a pure body under capture
    // checking, and a closure over `this` is not pure, while these two values are. The same lines
    // compiled on `main` because they lived in the umbrella, which is not capture checked.
    val model = schema
    val live  = databaseSchema

    {
      case "status" :: flags =>
        val result = withDatabase(read { Commands.status(model, live) })
        emit(flags)(RenderJson.status(result), Render.status(result))
        if (result.inSync) 0 else 1

      case "sync" :: flags =>
        val apply  = flags.contains("--apply")
        val force  = flags.contains("--force")
        val result = withDatabase(transact { Commands.sync(model, apply, force, live) })
        emit(flags)(
          RenderJson.sync(result, applyRequested = apply),
          Render.sync(result, applyRequested = apply)
        )
        if (apply && !result.applied && result.changes.nonEmpty) 1 else 0

      case "freeze" :: rest =>
        // Everything that is not a flag is the name, joined: `run freeze add isbn to book` names
        // the migration "add isbn to book" without the caller having to fight sbt's
        // space splitting argument parser with quotes.
        val name = rest.filterNot(_.startsWith("--")).mkString(" ")
        if (name.isEmpty) throw SchemaError("freeze needs a name: eezo db freeze add isbn to book")
        val result = Commands.freeze(schema, name, freezePolicy(rest))
        emit(rest)(RenderJson.freeze(result), Render.freeze(result))
        0

      case "migrate" :: flags =>
        val apply  = flags.contains("--apply")
        val result = withDatabase(transact {
          Commands.migrate(model, apply, dbSchema = live)
        })
        emit(flags)(RenderJson.migrate(result), Render.migrate(result))
        result match {
          case MigrateResult.Tampered(_)       => 1
          case MigrateResult.UpToDate(drift)   => if (drift.isEmpty) 0 else 1
          case MigrateResult.Applied(_, drift) => if (drift.isEmpty) 0 else 1
          case MigrateResult.Pending(_)        => 0
        }

      case "reset" :: flags =>
        val result = withDatabase(transact { Commands.reset(model, live) })
        emit(flags)(RenderJson.reset(result), "reset ✓")
        0

      case "drop" :: flags =>
        val result = withDatabase(transact { Commands.drop(live) })
        emit(flags)(RenderJson.drop(result), "dropped ✓")
        0

      case "dump" :: flags =>
        emit(flags)(RenderJson.dump(schema.snapshot), Commands.dump(schema))
        0

      case "ddl" :: flags =>
        // As text, one statement per line, each closed with its semicolon: the output is pasteable
        // into psql as it stands.
        val statements = Commands.ddl(schema)
        if (json(flags)) println(RenderJson.ddl(statements))
        else statements.foreach(s => println(s + ";"))
        0
    }
  }

  /** The namespace's rows, spelled as `db help` prints them; [[usage]] prefixes each with `db` for
    * the application's own table.
    */
  private val schemaUsage: List[Usage] = List(
    Usage("status", "code vs live database"),
    Usage("sync [--apply] [--force]", "apply the code/db diff directly (dev only)"),
    Usage("freeze <name>", "write a migration from schema.json -> code"),
    Usage("migrate [--apply]", "apply pending migrations, then verify"),
    Usage("reset", "drop everything and recreate from the model"),
    Usage("drop", "drop everything"),
    Usage("dump", "print the derived snapshot"),
    Usage("ddl", "print full DDL")
  )

  private val schemaNotes: List[String] = List(
    "db freeze takes --accept-all / --skip-destructive in place of the prompt",
    "(bare --json implies --skip-destructive)"
  )

  /** What `db` and `db help` print: the namespace's own table and its notes. */
  private def dbHelp: String =
    (("eezo db <command>" :: usageTable(schemaUsage)) ++ ("" :: schemaNotes)).mkString("\n")

  override protected def usage: List[Usage] =
    schemaUsage.map(row => row.copy(command = s"db ${row.command}")) ++ super.usage

  override protected def notes: List[String] = schemaNotes ++ super.notes

  /** [[arms]], and the rest of the chain after them, behind the `SchemaError` catch. The catch is
    * here and not in `core`, which cannot see the type; the http layer has nothing that throws it,
    * but the program does run under this layer's `Database` and may, so the catch covers
    * `super.commands` too rather than this layer's arms alone. Exit 1, through `Dispatch.fail` so
    * the line is JSON under `--json`.
    *
    * The type is inferred and then asserted pure at the `commands` site, deliberately.
    * `Dispatch.commands` is typed in `core`, outside capture checking, so there it reads as a pure
    * function, while this object closes over `this`, which the checker counts as a capability
    * (DESIGN §8.8, research/capture-checking.md §3). The assertion holds because the application
    * carries no capability: `DbInit`'s hook is a `->`, and every other member is a value.
    */
  private def guarded = new PartialFunction[List[String], Int] {
    private def chain = arms orElse DbApp.super.commands

    def isDefinedAt(args: List[String]): Boolean = chain.isDefinedAt(args)

    def apply(args: List[String]): Int =
      try chain(args)
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
    * script gets its own policy through flags; the mechanism (`Commands.freeze`'s `decide`) is the
    * same either way.
    */
  private def decide(change: Change): Decision =
    if (!change.destructive) Decision.Accept
    else {
      println(s"\n  ⚠ ${change.describe}")
      print("    [d] drop (data lost)   [k] keep, skip\n  > ")
      if (StdIn.readLine().trim.toLowerCase == "k") Decision.Skip else Decision.Accept
    }
}
