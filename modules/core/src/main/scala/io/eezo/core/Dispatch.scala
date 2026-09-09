package io.eezo.core

import io.eezo.core.internal.Json

/** `main`, and the dispatch every entry trait shares. Users never name this trait: they extend an
  * edge's entry trait (`HttpApp`, `DbApp`) or the umbrella's `EezoApp`, and each of those chains
  * its own commands in front of [[commands]].
  *
  * `core` cannot see either edge, so nothing here starts or stops anything: no server, no database,
  * no lifecycle hook. What lives here is only what both edges would otherwise write twice, which is
  * `main`, the unknown-command answer, `help` over [[usage]], and the `--json` flag with the one
  * error shape it implies.
  */
trait Dispatch {

  /** The commands this application answers, as `first argument :: rest`. `Nil` is the application
    * itself. Each edge writes `own orElse super.commands`, so the later mixin's arms are consulted
    * first.
    */
  protected def commands: PartialFunction[List[String], Int] = PartialFunction.empty

  /** One line per command, for `help`. Each edge writes `own ++ super.usage`. */
  protected def usage: List[String] = Nil

  /** `--json` renders the same result values through the edge's `RenderJson` instead of its
    * `Render` — the machine-readable half of design/objective.md's dev loop. Exit codes are
    * identical in both modes, so a caller gates on the code and parses the body.
    */
  protected final def json(args: List[String]): Boolean = args.contains("--json")

  /** One failure line on stderr: `{"error": ...}` under `--json`, prose otherwise. The edges call
    * it for what they alone can catch; `core` calls it for the unknown command.
    */
  protected final def fail(args: List[String], message: String): Unit =
    Console.err.println(
      if (json(args)) Json.render(Json.Obj(List("error" -> Json.Str(message))))
      else s"[eezo] $message"
    )

  final def main(args: Array[String]): Unit = {
    val code = run(args.toList)
    // Only a failure exits explicitly: `sys.exit(0)` on the happy path would tear down anything
    // the process still owes — a test harness, an embedding — for no benefit.
    if (code != 0) sys.exit(code)
  }

  /** `main` without the exit, so a suite can assert on the code. `help` is matched here rather than
    * being an arm of [[commands]], so that no edge can shadow it.
    */
  private[eezo] final def run(args: List[String]): Int = args match {
    case "help" :: _ => println(help); 0
    case list        => commands.applyOrElse(list, unknown)
  }

  private def unknown(args: List[String]): Int = {
    fail(args, s"unknown command: ${args.mkString(" ")}. Run `help` for the list.")
    2
  }

  private def help: String =
    (("eezo" :: usage.map("  " + _)) ++
      List(
        "  help              this list",
        "",
        "every command takes --json for machine-readable output (same exit codes);",
        "no arguments runs the application"
      )).mkString("\n")
}
