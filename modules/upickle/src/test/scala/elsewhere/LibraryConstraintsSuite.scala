package elsewhere

import upickle.core.AbortException
import upickle.core.TraceVisitor.TraceException

object OwnApi extends upickle.AttributeTagged

object LibraryConstraintsSuite {
  final case class Item(sku: String, quantity: Int)

  final case class Authored(name: String)

  object Authored {
    given upickle.default.ReadWriter[Authored] = upickle.default.macroRW
  }
}

class LibraryConstraintsSuite extends munit.FunSuite {
  import LibraryConstraintsSuite.*

  private val itemReadWriter = upickle.default.macroRW[Item]

  test("upickle alone reads a JSON null into a String field as null and into an Int field as 0") {
    assertEquals(
      upickle.default.read[Item]("""{"sku":null,"quantity":null}""")(using itemReadWriter),
      Item(null, 0)
    )
  }

  test("upickle reports a model's complaint with the byte offset in its message, not in its clue") {
    val trace = intercept[TraceException](
      upickle.default.read[Item]("""{"sku":"a","quantity":true}""")(using itemReadWriter)
    )
    assertEquals(trace.jsonPath, "$['quantity']")
    trace.getCause match {
      case abort: AbortException =>
        assert(!clue(abort.clue).contains("index"))
        assert(clue(abort.getMessage).contains("at index"))
      case other => fail(s"expected an AbortException, got $other")
    }
  }

  test("a ujson.Value holds a Long past 2^53 as the nearest Double") {
    assertEquals(ujson.read("9007199254740993").num.toLong, 9007199254740992L)
  }

  test("an upickle Api of eezo's own would not find the author's upickle.default ReadWriter") {
    assertNoDiff(compileErrors("summon[upickle.default.ReadWriter[Authored]]"), "")
    val errors = compileErrors("summon[OwnApi.ReadWriter[Authored]]")
    assert(clue(errors).contains("No given instance of type elsewhere.OwnApi.ReadWriter"), errors)
  }
}
