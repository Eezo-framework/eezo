package io.eezo.core

import io.eezo.core.support.Captured.captured

/** `Dispatch` as `core` owns it: `main`, the chained `commands`, and `help` over `usage`.
  *
  * Nothing here starts or stops anything. Each edge chains its own arms in front of
  * `super.commands`, and this suite pins the two things the edges build on: an unknown first
  * argument is exit 2, and a later mixin's arm shadows an earlier one's.
  */
class DispatchSuite extends munit.FunSuite {

  trait Greeter extends Dispatch {
    override protected def commands: PartialFunction[List[String], Int] = ({
      case Nil            => println("greeter booted"); 0
      case "greet" :: who => println(s"hello ${who.mkString(" ")}"); 0
    }: PartialFunction[List[String], Int]) orElse super.commands

    override protected def usage: List[String] = List("greet <who>     says hello") ++ super.usage
  }

  trait Counter extends Dispatch {
    override protected def commands: PartialFunction[List[String], Int] = ({
      case Nil          => println("counter booted"); 0
      case "count" :: _ => println("1 2 3"); 0
    }: PartialFunction[List[String], Int]) orElse super.commands

    override protected def usage: List[String] = List("count           counts") ++ super.usage
  }

  object Greeting extends Greeter

  object Both extends Greeter with Counter

  test("no arguments is the application: the Nil arm runs") {
    val (code, out, _) = captured(Greeting.run(Nil))
    assertEquals(code, 0)
    assertEquals(out.trim, "greeter booted")
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

  test("help lists one line per usage entry and is exit 0") {
    val (code, out, _) = captured(Both.run(List("help")))
    assertEquals(code, 0)
    assert(out.contains("greet <who>     says hello"), out)
    assert(out.contains("count           counts"), out)
    assert(out.contains("help"), out)
  }

  test("a later mixin's arm is consulted first, and the earlier one's still answers") {
    val (_, booted, _) = captured(Both.run(Nil))
    assertEquals(booted.trim, "counter booted")

    val (code, out, _) = captured(Both.run(List("greet", "you")))
    assertEquals(code, 0)
    assertEquals(out.trim, "hello you")
  }
}
