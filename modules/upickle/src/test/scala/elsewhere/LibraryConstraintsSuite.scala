package elsewhere

import java.nio.charset.StandardCharsets

import scala.deriving.Mirror

import upickle.core.{AbortException, NoOpVisitor}
import upickle.core.TraceVisitor.TraceException

object OwnApi extends upickle.AttributeTagged

class LibraryConstraintsSuite extends munit.FunSuite {

  private val lineReader = upickle.default.macroRW[Line]

  test("upickle alone reads a JSON null into a String field as null and into an Int field as 0") {
    assertEquals(
      upickle.default.read[Line]("""{"sku":null,"quantity":null}""")(using lineReader),
      Line(null, 0)
    )
  }

  test("upickle reports a model's complaint with the byte offset in its message, not in its clue") {
    val trace = intercept[TraceException](
      upickle.default.read[Line]("""{"sku":"a","quantity":true}""")(using lineReader)
    )
    assertEquals(trace.jsonPath, "$['quantity']")
    trace.getCause match {
      case abort: AbortException =>
        assertEquals(abort.clue, "expected number got boolean")
        assertEquals(abort.getMessage, "expected number got boolean at index 22")
      case other => fail(s"expected an AbortException, got $other")
    }
  }

  test("a ujson.Value holds a number as a Double, so a Long past 2^53 comes back changed") {
    assertEquals(ujson.read("9007199254740993").num.toLong, 9007199254740992L)
  }

  test("the ujson parser reads a body nested 50000 deep with no thread stack") {
    val depth = 50000
    val body  = (("""{"x":""" * depth) + "1" + ("}" * depth)).getBytes(StandardCharsets.UTF_8)
    ujson.Readable.fromByteArray(body).transform(NoOpVisitor)
  }

  test("the ujson parser undoes the escapes of a name before a visitor sees it") {
    assertEquals(ujson.read("""{"\u006fwner":1}""").obj.keys.toSet, Set("owner"))
  }

  test("an upickle Api of eezo's own would not find the author's upickle.default ReadWriter") {
    val errors = compileErrors("summon[OwnApi.ReadWriter[Renamed]]")
    assert(clue(errors).contains("No given instance of type elsewhere.OwnApi.ReadWriter"), errors)
  }

  test("Scala finds no Mirror and no ClassTag for an abstract type parameter") {
    val mirror = compileErrors("def f[A]: Mirror.ProductOf[A] = summon[Mirror.ProductOf[A]]")
    assert(clue(mirror).contains("No given instance of type"), mirror)
    val tag =
      compileErrors("def f[A]: scala.reflect.ClassTag[A] = summon[scala.reflect.ClassTag[A]]")
    assert(clue(tag).contains("No ClassTag available for A"), tag)
  }

  test("List and Option are sums with a Mirror of their own, as a sealed trait is") {
    assert(summon[Mirror.SumOf[List[Int]]] != null)
    assert(summon[Mirror.SumOf[Option[Int]]] != null)
  }
}
