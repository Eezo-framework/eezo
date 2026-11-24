package io.eezo.core

import io.eezo.core.Dispatch.Usage
import io.eezo.core.internal.Json

/** `main`, and the dispatch every entry trait shares. Extend a layer's entry trait (`HttpApp`,
  * `DbApp`) or the umbrella's `EezoApp`, not this trait: each of those chains its own commands in
  * front of [[commands]].
  */
trait Dispatch {

  /** The application itself: what `sbt run` with no arguments does.
    *
    * Implement it in a layer without `override`. A trait that stacks two layers then inherits two
    * implementations, and the compiler refuses the stacking until that trait overrides `program` to
    * say which one runs. With `override` on a layer, a later mixin replaces that layer's `program`
    * with no error.
    */
  protected def program(): Unit

  /** The commands this application answers, as `first argument :: rest`, each arm returning the
    * exit code. `Nil` runs [[program]] and is answered here. `ConfigApp`, in `eezo-config`, answers
    * `Nil` first, to read the configuration before [[program]] runs. A layer adds its own commands
    * with `own orElse super.commands`, so the arms of the later mixin are tried first.
    */
  protected def commands: PartialFunction[List[String], Int] = { case Nil => program(); 0 }

  /** One row per command, for `help`. A layer writes `own ++ super.usage` and gives only the
    * spelling and the description: `help` lays out the columns once, over the rows of every layer.
    */
  protected def usage: List[Usage] = Nil

  /** What `help` prints after the command table and before its footer: the caveats that belong to
    * no single row. A layer writes `own ++ super.notes`.
    */
  protected def notes: List[String] = Nil

  /** Whether the arguments ask for `--json`. Under `--json` a command prints the same result as
    * without it, rendered as JSON by the layer instead of as text, and gives the same exit code.
    * Print the result with [[emit]] and report a failure with [[fail]], and both modes follow.
    */
  protected final def json(args: List[String]): Boolean = args.contains("--json")

  /** Prints the result of a command in the shape `flags` asks for: `asJson` under `--json`,
    * `asText` otherwise. Both are by name, and only the printed one is evaluated.
    *
    * Print every result that a command writes on stdout through `emit`, so that each command of the
    * layer answers `--json`.
    */
  protected final def emit(flags: List[String])(asJson: => String, asText: => String): Unit =
    println(if (json(flags)) asJson else asText)

  /** One failure line on stderr: `{"error": ...}` under `--json`, `[eezo] message` otherwise. A
    * layer calls it for a failure only it can catch; `Dispatch` calls it for an unknown command.
    */
  protected final def fail(args: List[String], message: String): Unit =
    Console.err.println(
      if (json(args)) Json.render(Json.Obj(List("error" -> Json.Str(message))))
      else s"[eezo] $message"
    )

  final def main(args: Array[String]): Unit = {
    val code = run(args.toList)
    if (code != 0) exit(code)
  }

  private[eezo] def exit(code: Int): Unit = sys.exit(code)

  private[eezo] final def run(args: List[String]): Int = args match {
    case "help" :: _ => println(help); 0
    case list        => commands.applyOrElse(list, unknown)
  }

  private def unknown(args: List[String]): Int = {
    fail(args, s"unknown command: ${args.mkString(" ")}. Run `help` for the list.")
    2
  }

  /** Rows laid out as one table, every spelling padded to the widest, the way `help` lays them out.
    * A layer with a namespace of its own, such as `db`, uses it for the list of that namespace.
    */
  protected final def usageTable(rows: List[Usage]): List[String] = {
    val width = rows.map(_.command.length).max
    rows.map(row => s"  ${row.command.padTo(width, ' ')}  ${row.description}")
  }

  private def help: String = {
    val extra = if (notes.isEmpty) Nil else "" :: notes
    (("eezo" :: usageTable(usage :+ Usage("help", "this list"))) ++ extra ++
      List(
        "",
        "every command takes --json for machine-readable output (same exit codes);",
        "no arguments runs the application"
      )).mkString("\n")
  }
}

object Dispatch {

  /** One row of `help`: the command as the user spells it, flags included, and what it does. */
  final case class Usage(command: String, description: String)
}
