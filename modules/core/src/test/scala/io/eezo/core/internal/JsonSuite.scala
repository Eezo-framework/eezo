package io.eezo.core.internal

/** The reader, and the round trip against the writer.
  *
  * `parse(render(j)) == j` is the property that matters: the writer is what every `--json` command
  * already pins, so the reader is tested as its inverse rather than against a second set of
  * expected strings. The generator is a seeded `Random` walk rather than a property testing
  * library, because determinism is worth more here than shrinking: a failing seed is its own
  * reproduction.
  */
class JsonSuite extends munit.FunSuite {

  test("parse(render(j)) == j over generated values") {
    val rnd = new scala.util.Random(20260914L)

    def gen(depth: Int): Json =
      rnd.nextInt(if (depth <= 0) 4 else 6) match {
        case 0 => Json.Str(rnd.alphanumeric.take(rnd.nextInt(8)).mkString)
        case 1 => Json.Num(rnd.nextLong())
        case 2 => Json.Bool(rnd.nextBoolean())
        case 3 => Json.Null
        case 4 => Json.Arr(List.fill(rnd.nextInt(4))(gen(depth - 1)))
        case _ =>
          Json.Obj(List.tabulate(rnd.nextInt(4))(i => s"k$i" -> gen(depth - 1)))
      }

    for (_ <- 1 to 500) {
      val j = gen(3)
      assertEquals(Json.parse(Json.render(j)), Right(j), clues(Json.render(j)))
    }
  }

  test("every escape the writer emits comes back") {
    val j = Json.Str("a\"b\\c\nd\re\tfg")
    assertEquals(Json.parse(Json.render(j)), Right(j))
  }

  test("ordinary JSON from outside parses: compact, spaced, nested") {
    assertEquals(
      Json.parse("""{"kind":"event","name":"inc","payload":{"count":3,"on":true,"x":null}}"""),
      Right(
        Json.Obj(
          List(
            "kind"    -> Json.Str("event"),
            "name"    -> Json.Str("inc"),
            "payload" -> Json.Obj(
              List("count" -> Json.Num(3), "on" -> Json.Bool(true), "x" -> Json.Null)
            )
          )
        )
      )
    )
    assertEquals(Json.parse("  [ 1 , -2 ]  "), Right(Json.Arr(List(Json.Num(1), Json.Num(-2)))))
  }

  test("a unicode escape decodes") {
    assertEquals(Json.parse("\"\\u00e9\\u0041\""), Right(Json.Str("éA")))
  }

  test("malformed input is refused with an offset, not half a value") {
    assert(Json.parse("""{"a":""").isLeft)
    assert(Json.parse("""[1,]""").isLeft)
    assert(Json.parse("""{"a" 1}""").isLeft)
    assert(Json.parse(""""unterminated""").isLeft)
    assert(Json.parse("").isLeft)
    assert(Json.parse("tru").isLeft)
    assertEquals(
      Json.parse("[1] trailing").left.map(_.contains("offset 4")),
      Left(true)
    )
  }

  test("a fractional or exponent number is refused by name") {
    assertEquals(Json.parse("1.5").left.map(_.contains("integers only")), Left(true))
    assert(Json.parse("1e3").isLeft)
  }

  test("a number out of Long range is refused rather than wrapped") {
    assert(Json.parse("9223372036854775808").isLeft)
    assertEquals(Json.parse("9223372036854775807"), Right(Json.Num(Long.MaxValue)))
    assertEquals(Json.parse("-9223372036854775808"), Right(Json.Num(Long.MinValue)))
  }

  test("a control character inside a string is refused, as RFC 8259 requires") {
    assert(Json.parse("\"a\nb\"").isLeft)
  }
}
