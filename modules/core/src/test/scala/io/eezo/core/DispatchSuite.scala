package io.eezo.core

import io.eezo.core.Dispatch.Usage
import io.eezo.core.support.Captured.captured

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

  test("two layers that both implement program cannot be stacked without choosing one") {
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

  test("an unrecognised first argument without --json is one [eezo] line, with the same exit 2") {
    val (code, out, err) = captured(Greeting.run(List("status")))
    assertEquals(code, 2)
    assertEquals(out, "")
    assertEquals(err, "[eezo] unknown command: status. Run `help` for the list.\n")
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

  test("help is answered ahead of every layer's arms, and a layer's own help arm never runs") {
    object Shadowing extends Greeter {
      override protected def commands: PartialFunction[List[String], Int] = ({ case "help" :: _ =>
        println("the layer's help"); 7
      }: PartialFunction[List[String], Int]) orElse super.commands
    }
    val (code, out, _) = captured(Shadowing.run(List("help")))
    assertEquals(code, 0)
    assertEquals(out.linesIterator.next(), "eezo")
    assert(!out.contains("the layer's help"), out)
  }

  test("help prints the notes after the table and before the footer, once for every layer") {
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

  test(
    "main exits only on a failure, with its code"
  ) {
    val exits = List.newBuilder[Int]
    object Exiting extends Greeter {
      override private[eezo] def exit(code: Int): Unit = { val _ = exits += code }
    }
    val (_, out, _) = captured {
      Exiting.main(Array("greet", "eezo"))
      Exiting.main(Array("status"))
      0
    }
    assertEquals(out.trim, "hello eezo")
    assertEquals(exits.result(), List(2))
  }

  test("a later mixin's arm is consulted first, and the earlier one's still answers") {
    val (_, name, _) = captured(Both.run(List("name")))
    assertEquals(name.trim, "counter")

    val (code, out, _) = captured(Both.run(List("greet", "you")))
    assertEquals(code, 0)
    assertEquals(out.trim, "hello you")
  }
}
