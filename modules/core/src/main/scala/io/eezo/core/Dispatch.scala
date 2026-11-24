package io.eezo.core

import io.eezo.core.Dispatch.Usage
import io.eezo.core.internal.Json

/** `main`, and the dispatch every entry trait shares. Users never name this trait: they extend an
  * layer's entry trait (`HttpApp`, `DbApp`) or the umbrella's `EezoApp`, and each of those chains
  * its own commands in front of [[commands]].
  *
  * `core` cannot see either layer, so nothing here starts or stops anything: no server, no
  * database, no lifecycle hook. What lives here is only what both layers would otherwise write
  * twice, which is `main`, the unknown command answer, `help` over [[usage]], and the `--json` flag
  * with the one error shape it implies.
  */
trait Dispatch {

  protected def program(): Unit

  protected def commands: PartialFunction[List[String], Int] = { case Nil => program(); 0 }

  protected def usage: List[Usage] = Nil

  protected def notes: List[String] = Nil

  protected final def json(args: List[String]): Boolean = args.contains("--json")

  protected final def emit(flags: List[String])(asJson: => String, asText: => String): Unit =
    println(if (json(flags)) asJson else asText)

  protected final def fail(args: List[String], message: String): Unit =
    Console.err.println(
      if (json(args)) Json.render(Json.Obj(List("error" -> Json.Str(message))))
      else s"[eezo] $message"
    )

  final def main(args: Array[String]): Unit = exitOnlyOnFailure(run(args.toList))

  private def exitOnlyOnFailure(code: Int): Unit = if (code != 0) sys.exit(code)

  private[eezo] final def run(args: List[String]): Int = args match {
    case "help" :: _ => println(help); 0
    case list        => commands.applyOrElse(list, unknown)
  }

  private def unknown(args: List[String]): Int = {
    fail(args, s"unknown command: ${args.mkString(" ")}. Run `help` for the list.")
    2
  }

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
