package elsewhere

import java.nio.charset.StandardCharsets

import upickle.default.{ReadWriter, readwriter}

import io.eezo.core.Id
import io.eezo.http.api.Serde
import io.eezo.json.upickle.given

final case class Renamed(name: String)

object Renamed {
  given ReadWriter[Renamed] =
    readwriter[ujson.Value].bimap[Renamed](
      renamed => ujson.Obj("nome" -> renamed.name),
      json => Renamed(json("nome").str)
    )
}

final case class Plain(name: String)

final case class Product(name: String)

final case class Order(id: Id[Order], product: Id[Product], quantity: Int, price: BigDecimal)

final class Opaque(val secret: String)

final case class Holder(opaque: Opaque)

final case class Tally(id: Id[Tally], count: Long)

final case class Line(sku: String, quantity: Int)

final case class Entry(id: Id[Entry], owner: Id[Product], count: Long)

final case class Note(text: Option[String])

final class Fixed[A](text: String) extends Serde[A] {
  def serialize(value: A): String                              = text
  def deserialize(body: Array[Byte]): Either[Serde.Failure, A] = Left(Serde.Failure.Unfit(text))
  def deserialize(body: Array[Byte], decided: Map[String, Id[Any]]): Either[Serde.Failure, A] =
    deserialize(body)
}

final case class Companioned(name: String)

object Companioned {
  given Serde[Companioned] = Fixed("companion")
}

final case class Preferred(name: String)

given Serde[Preferred] = Fixed("application")

class UpickleAdapterSuite extends munit.FunSuite {

  private def bytes(text: String): Array[Byte] = text.getBytes(StandardCharsets.UTF_8)

  private def unfit[A](result: Either[Serde.Failure, A]): String = result match {
    case Left(Serde.Failure.Unfit(reason)) => reason
    case other                             => fail(s"expected Unfit, got $other")
  }

  private def malformed[A](result: Either[Serde.Failure, A]): String = result match {
    case Left(Serde.Failure.Malformed(reason)) => reason
    case other                                 => fail(s"expected Malformed, got $other")
  }

  private val order = Order(Id.gen(), Id.gen(), 2, BigDecimal("9.50"))

  test("the author's ReadWriter wins over derivation, both ways") {
    val serde = summon[Serde[Renamed]]
    assertEquals(serde.serialize(Renamed("x")), """{"nome":"x"}""")
    assertEquals(serde.deserialize(bytes("""{"nome":"y"}""")), Right(Renamed("y")))
  }

  test("the imported serde wins over a Serde in the model's companion") {
    assertEquals(summon[Serde[Companioned]].serialize(Companioned("x")), """{"name":"x"}""")
  }

  test("a Serde the application gives for one model wins over the imported serde, at any level") {
    assertEquals(summon[Serde[Preferred]].serialize(Preferred("x")), "application")
    given Serde[Companioned] = Fixed("nearer")
    assertEquals(summon[Serde[Companioned]].serialize(Companioned("x")), "nearer")
  }

  test("a model with no ReadWriter is derived, with its fields named as declared") {
    val serde = summon[Serde[Plain]]
    assertEquals(serde.serialize(Plain("x")), """{"name":"x"}""")
    assertEquals(serde.deserialize(bytes("""{"name":"y"}""")), Right(Plain("y")))
    assertEquals(
      unfit(serde.deserialize(bytes("""{"nome":"y"}"""))),
      "missing keys in dictionary: name at $"
    )
  }

  test("a key the caller owns wins over the id the body carried, and over no id at all") {
    val serde = summon[Serde[Order]]
    val key   = Id.gen[Order]()
    val other = serde.serialize(order)
    val none  = s"""{"product":"${order.product.show}","quantity":2,"price":"9.50"}"""
    assertEquals(serde.deserialize(bytes(other), key), Right(order.copy(id = key)))
    assertEquals(serde.deserialize(bytes(none), key), Right(order.copy(id = key)))
  }

  private val entryKey   = Id.gen[Entry]()
  private val entryOwner = Id.gen[Product]()
  private val decided    =
    Map("id" -> Id[Any](entryKey.value), "owner" -> Id[Any](entryOwner.value))

  test("every name eezo decided wins over whatever the body carried there, and over nothing") {
    val serde = summon[Serde[Entry]]
    val any   =
      """{"id":{"a":[1,{"b":null}]},"owner":[{"c":{"d":"e"}},null],"count":9007199254740993}"""
    assertEquals(
      serde.deserialize(bytes(any), decided),
      Right(Entry(entryKey, entryOwner, 9007199254740993L))
    )
    assertEquals(
      serde.deserialize(bytes("""{"count":3}"""), decided),
      Right(Entry(entryKey, entryOwner, 3))
    )
  }

  test("a decided name the body repeats, or spells with an escape, is skipped every time") {
    val serde = summon[Serde[Entry]]
    val body  =
      """{"owner":"x","id":"y","count":1,"owner":"z","\u006fwner":{"w":1},"i\u0064":[]}"""
    assertEquals(serde.deserialize(bytes(body), decided), Right(Entry(entryKey, entryOwner, 1)))
  }

  test(
    "a field nobody decided is still required and refused null, non JSON is malformed, and deep nesting is a failure, not a crash"
  ) {
    val serde = summon[Serde[Entry]]
    assertEquals(
      unfit(serde.deserialize(bytes("""{"id":"x"}"""), decided)),
      "missing keys in dictionary: count at $"
    )
    assertEquals(
      unfit(serde.deserialize(bytes("""{"count":null}"""), decided)),
      "expected a value got null at $['count']"
    )
    assertEquals(
      malformed(serde.deserialize(bytes("not json"), decided)),
      "expected null got \"n\" at index 0 at $"
    )
    assert(clue(unfit(serde.deserialize(bytes("[1,2]"), decided))).endsWith(" at $"))
    val depth = 50000
    val deep  = bytes(("""{"count":""" * depth) + "1" + ("}" * depth))
    assert(clue(serde.deserialize(deep, decided)).isLeft)
  }

  test("only the names eezo decided are filled: the owner left out is still required") {
    val serde = summon[Serde[Entry]]
    assertEquals(
      unfit(serde.deserialize(bytes("""{"count":3}"""), entryKey)),
      "missing keys in dictionary: owner at $"
    )
    assertEquals(
      serde.deserialize(bytes(s"""{"owner":"${entryOwner.show}","count":3}"""), Map.empty),
      serde.deserialize(bytes(s"""{"owner":"${entryOwner.show}","count":3}"""))
    )
  }

  test("a key crosses the wire as the text Id.show gives, and reads back to an equal key") {
    val serde = summon[Serde[Order]]
    val text  = serde.serialize(order)
    assert(clue(text).contains(s""""id":"${order.id.show}""""))
    assert(clue(text).contains(s""""product":"${order.product.show}""""))
    assertEquals(serde.deserialize(bytes(text)), Right(order))
  }

  test("a body that is not JSON, or no body at all, is Malformed with the cause and the path") {
    val serde = summon[Serde[Order]]
    assertEquals(
      malformed(serde.deserialize(bytes("not json"))),
      "expected null got \"n\" at index 0 at $"
    )
    assert(clue(malformed(serde.deserialize(Array.emptyByteArray))).endsWith(" at $"))
    assertEquals(
      malformed(serde.deserialize(bytes("not json"), order.id)),
      "expected null got \"n\" at index 0 at $"
    )
  }

  test("a missing or wrongly typed field is Unfit, as one reason naming the field's path") {
    val serde   = summon[Serde[Order]]
    val missing = s"""{"id":"${order.id.show}","product":"${order.product.show}","quantity":2}"""
    val wrong   =
      s"""{"id":"${order.id.show}","product":"${order.product.show}","quantity":"two","price":"9.50"}"""
    assertEquals(unfit(serde.deserialize(bytes(missing))), "missing keys in dictionary: price at $")
    assert(clue(unfit(serde.deserialize(bytes(wrong)))).endsWith(" at $['quantity']"))
  }

  test("a complaint the model raises carries its JSON path and not the parser's byte offset") {
    val line   = summon[Serde[Line]]
    val reason = unfit(line.deserialize(bytes("""{"sku":"a","quantity":true}""")))
    assertEquals(reason, "expected number got boolean at $['quantity']")
  }

  test("a key that is not a UUID is Unfit at its path") {
    val serde = summon[Serde[Order]]
    val body  = s"""{"id":"${order.id.show}","product":"seven","quantity":2,"price":"9.50"}"""
    assert(clue(unfit(serde.deserialize(bytes(body)))).endsWith(" at $['product']"))
  }

  test("JSON that is not an object is Unfit for a record, with or without a key") {
    val serde = summon[Serde[Order]]
    Seq("[1,2]", "42", "\"text\"", "null").foreach { body =>
      assert(clue(unfit(serde.deserialize(bytes(body)))).endsWith(" at $"), body)
      assert(clue(unfit(serde.deserialize(bytes(body), order.id))).endsWith(" at $"), body)
    }
    assertEquals(
      unfit(serde.deserialize(bytes("[1,2]"), order.id)),
      "expected dictionary got sequence at $"
    )
  }

  test("a JSON array reads into a Serde of a list") {
    assertEquals(summon[Serde[List[Int]]].deserialize(bytes("[1,2]")), Right(List(1, 2)))
  }

  test("a null field is Unfit at its path, whatever the field's type, with or without a key") {
    val line  = summon[Serde[Line]]
    val serde = summon[Serde[Order]]
    assertEquals(
      unfit(line.deserialize(bytes("""{"sku":null,"quantity":2}"""))),
      "expected a value got null at $['sku']"
    )
    assertEquals(
      unfit(line.deserialize(bytes("""{"sku":"a","quantity":null}"""))),
      "expected a value got null at $['quantity']"
    )
    val product = s"""{"id":"${order.id.show}","product":null,"quantity":2,"price":"9.50"}"""
    val price   =
      s"""{"id":"${order.id.show}","product":"${order.product.show}","quantity":2,"price":null}"""
    assertEquals(
      unfit(serde.deserialize(bytes(product))),
      "expected a value got null at $['product']"
    )
    assertEquals(
      unfit(serde.deserialize(bytes(price), order.id)),
      "expected a value got null at $['price']"
    )
    assert(
      clue(unfit(summon[Serde[List[String]]].deserialize(bytes("""["a",null]""")))).endsWith(
        " at $[1]"
      )
    )
  }

  test("a null the model reads as absent, or an id the key replaces, is not refused") {
    assertEquals(summon[Serde[Note]].deserialize(bytes("""{"text":null}""")), Right(Note(None)))
    val serde = summon[Serde[Order]]
    val body  = s"""{"id":null,"product":"${order.product.show}","quantity":2,"price":"9.50"}"""
    assertEquals(serde.deserialize(bytes(body), order.id), Right(order))
  }

  test("an integer past what a Double holds reads back exactly, with or without a key") {
    val serde = summon[Serde[Tally]]
    val key   = Id.gen[Tally]()
    val body  = bytes(s"""{"id":"${key.show}","count":9007199254740993}""")
    assertEquals(serde.deserialize(body), Right(Tally(key, 9007199254740993L)))
    assertEquals(serde.deserialize(body, key), Right(Tally(key, 9007199254740993L)))
  }

  test("a body nested deeper than any model is refused as a failure, never thrown") {
    val serde = summon[Serde[Plain]]
    val depth = 50000
    val body  = bytes(("""{"x":""" * depth) + "1" + ("}" * depth))
    assert(clue(serde.deserialize(body)).isLeft)
    assert(clue(serde.deserialize(body, Id.gen[Plain]())).isLeft)
  }

  test(
    "a number with a fraction, in quotes or past the range of an Int reads as upickle reads it"
  ) {
    val serde = summon[Serde[Line]]
    assertEquals(
      serde.deserialize(bytes("""{"sku":"a","quantity":1.5}""")),
      Right(Line("a", 1))
    )
    assertEquals(
      serde.deserialize(bytes("""{"sku":"a","quantity":"2"}""")),
      Right(Line("a", 2))
    )
    assertEquals(
      serde.deserialize(bytes("""{"sku":"a","quantity":99999999999}""")),
      Right(Line("a", 99999999999L.toInt))
    )
  }

  test("a collection of a model with no ReadWriter fails to compile naming the collection") {
    val listed = compileErrors("summon[Serde[List[Plain]]]")
    assert(clue(listed).contains("ReadWriter[List[elsewhere.Plain]]"), listed)
    val optional = compileErrors("summon[Serde[Option[Plain]]]")
    assert(clue(optional).contains("ReadWriter[Option[elsewhere.Plain]]"), optional)
  }

  test(
    "a field upickle cannot write fails to compile where the Serde is summoned, naming its type"
  ) {
    val errors = compileErrors("summon[Serde[Holder]]")
    assert(clue(errors).contains("No given instance of type"), errors)
    assert(clue(errors).contains("Reader[elsewhere.Opaque]"), errors)
  }

  test("a type with neither an instance nor a shape to derive from fails to compile, naming it") {
    val errors = compileErrors("summon[Serde[Opaque]]")
    assert(clue(errors).contains("No given instance of type"), errors)
    assert(clue(errors).contains("ReadWriter[elsewhere.Opaque]"), errors)
  }
}
