package io.eezo.core

import io.eezo.core.Dispatch.Usage
import io.eezo.core.support.Captured.captured

/** `Dispatch` as `core` owns it: `main`, the chained `commands`, `help` over `usage`, and `emit`.
  *
  * Nothing here starts or stops anything. Each edge chains its own arms in front of
  * `super.commands`, and this suite pins the things the edges build on: an unknown first argument
  * is exit 2, `Nil` is `program` and is answered here rather than by an edge, two edges that both
  * implement `program` cannot be stacked without saying which one wins, a later mixin's arm shadows
  * an earlier one's, `help` lays the table out once for every edge, and `emit` is the one place the
  * `--json` choice is made.
  */
class DispatchSuite extends munit.FunSuite {

  trait Greeter extends Dispatch {
    protected def program(): Unit = println("greeter booted")

    override protected def commands: PartialFunction[List[String], Int] = ({
      case "greet" :: who => println(s"hello ${who.mkString(" ")}"); 0
      case "name" :: _    => println("greeter"); 0
    }: PartialFunction[List[String], Int]) orElse super.commands

    override protected def usage: List[Usage] =
      List(Usage("greet <who>", "says hello")) ++ super.usage
  }

  trait Counter extends Dispatch {
    protected def program(): Unit = println("counter booted")

    override protected def commands: PartialFunction[List[String], Int] = ({
      case "count" :: flags =>
        emit(flags)("{\"count\": [1, 2, 3]}", "1 2 3")
        0
      case "name" :: _ => println("counter"); 0
    }: PartialFunction[List[String], Int]) orElse super.commands

    override protected def usage: List[Usage] = List(Usage("count", "counts")) ++ super.usage

    override protected def notes: List[String] =
      List("count takes no arguments", "(it always counts to three)") ++ super.notes
  }

  object Greeting extends Greeter

  /** Two edges stacked. `program` is concrete in both, so this object has to say which one it
    * means, and it says both, in the earlier one's order.
    */
  object Both extends Greeter with Counter {
    override protected def program(): Unit = {
      super[Greeter].program()
      super[Counter].program()
    }
  }

  private val footer = List(
    "",
    "every command takes --json for machine-readable output (same exit codes);",
    "no arguments runs the application"
  )

  test("no arguments is the application: program runs, through Dispatch's own Nil arm") {
    val (code, out, _) = captured(Greeting.run(Nil))
    assertEquals(code, 0)
    assertEquals(out.trim, "greeter booted")
  }

  test("two edges that both implement program cannot be stacked without choosing one") {
    val e = compileErrors("object Wrong extends Greeter with Counter")
    assert(!e.contains("Not found"), s"the snippet did not resolve, so it proves nothing: $e")
    assert(e.contains("conflicting members"), e)
    assert(e.contains("program"), e)

    val (code, out, _) = captured(Both.run(Nil))
    assertEquals(code, 0)
    assertEquals(out.linesIterator.toList, List("greeter booted", "counter booted"))
  }

  test("a recognised first argument runs its arm with the rest of the line") {
    val (code, out, _) = captured(Greeting.run(List("greet", "eezo", "users")))
    assertEquals(code, 0)
    assertEquals(out.trim, "hello eezo users")
  }

  test("an unrecognised first argument is exit 2, said on stderr, nothing on stdout") {
    val (code, out, err) = captured(Greeting.run(List("status", "--json")))
    assertEquals(code, 2)
    assertEquals(out, "")
    assert(err.contains("unknown command: status --json"), err)
    assert(err.contains("help"), err)
  }

  test("an unrecognised first argument under --json is the one error shape, escaped") {
    val (code, out, err) = captured(Greeting.run(List("say", "\"hi\"", "--json")))
    assertEquals(code, 2)
    assertEquals(out, "")
    assert(err.startsWith("{\n  \"error\": \"unknown command: say \\\"hi\\\" --json"), err)
  }

  test("help pads every command to the widest spelling, help itself included, and is exit 0") {
    val (code, out, _) = captured(Greeting.run(List("help")))
    assertEquals(code, 0)
    val expected = List(
      "eezo",
      "  greet <who>  says hello",
      "  help         this list"
    ) ++ footer
    assertEquals(out.linesIterator.toList, expected)
  }

  test("help prints the notes after the table and before the footer, once for every edge") {
    val (code, out, _) = captured(Both.run(List("help")))
    assertEquals(code, 0)
    val expected = List(
      "eezo",
      "  count        counts",
      "  greet <who>  says hello",
      "  help         this list",
      "",
      "count takes no arguments",
      "(it always counts to three)"
    ) ++ footer
    assertEquals(out.linesIterator.toList, expected)
  }

  test("emit prints the text side, or the JSON side under --json, and evaluates only that one") {
    val (code, text, _) = captured(Both.run(List("count")))
    assertEquals(code, 0)
    assertEquals(text.trim, "1 2 3")

    val (jsonCode, json, _) = captured(Both.run(List("count", "--json")))
    assertEquals(jsonCode, 0)
    assertEquals(json.trim, "{\"count\": [1, 2, 3]}")

    object Lazy extends Dispatch {
      override protected def program(): Unit = ()

      def onlyText(flags: List[String]): Int = {
        emit(flags)(sys.error("json was forced"), "text")
        0
      }
      def onlyJson(flags: List[String]): Int = {
        emit(flags)("json", sys.error("text was forced"))
        0
      }
    }
    assertEquals(captured(Lazy.onlyText(Nil))._2.trim, "text")
    assertEquals(captured(Lazy.onlyJson(List("--json")))._2.trim, "json")
  }

  test("a later mixin's arm is consulted first, and the earlier one's still answers") {
    val (_, name, _) = captured(Both.run(List("name")))
    assertEquals(name.trim, "counter")

    val (code, out, _) = captured(Both.run(List("greet", "you")))
    assertEquals(code, 0)
    assertEquals(out.trim, "hello you")
  }
}
