package io.eezo.core

/** `main`, and the dispatch every entry trait shares. Users never name this trait: they extend an
  * edge's entry trait (`HttpApp`, `DbApp`) or the umbrella's `EezoApp`, and each of those chains
  * its own commands in front of [[commands]].
  *
  * `core` cannot see either edge, so nothing here starts or stops anything: no server, no database,
  * no lifecycle hook. What lives here is only what both edges would otherwise write twice, which is
  * `main`, the unknown-command answer, and `help` over [[usage]].
  *
  * The unknown-command message is plain text even under `--json`: `core` cannot see either edge's
  * JSON renderer, and it cannot say which edge is missing because it does not know their
  * vocabularies.
  */
trait Dispatch {

  /** The commands this application answers, as `first argument :: rest`. `Nil` is the application
    * itself. Each edge writes `own orElse super.commands`, so the later mixin's arms are consulted
    * first.
    */
  protected def commands: PartialFunction[List[String], Int] = PartialFunction.empty

  /** One line per command, for `help`. Each edge writes `own ++ super.usage`. */
  protected def usage: List[String] = Nil

  final def main(args: Array[String]): Unit = {
    val code = run(args.toList)
    // Only a failure exits explicitly: `sys.exit(0)` on the happy path would tear down anything
    // the process still owes — a test harness, an embedding — for no benefit.
    if (code != 0) sys.exit(code)
  }

  /** `main` without the exit, so a suite can assert on the code. */
  private[eezo] final def run(args: List[String]): Int = args match {
    case "help" :: _ => println(help); 0
    case list        => commands.applyOrElse(list, unknown)
  }

  private def unknown(args: List[String]): Int = {
    Console.err.println(
      s"[eezo] unknown command: ${args.mkString(" ")}. Run `help` for the list."
    )
    2
  }

  private def help: String =
    (("eezo" :: usage.map("  " + _)) ++
      List("  help              this list", "", "no arguments runs the application")).mkString("\n")
}
