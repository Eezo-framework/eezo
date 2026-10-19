package elsewhere

import java.nio.charset.StandardCharsets

import upickle.default.{ReadWriter, readwriter}

import io.eezo.core.Id
import io.eezo.http.api.Serde
import io.eezo.json.upickle.given

/** A model whose author wrote the JSON shape by hand, naming `name` as `nome`, so that whether the
  * adapter took this instance or derived its own is visible in the text.
  */
final case class Renamed(name: String)

object Renamed {
  given ReadWriter[Renamed] =
    readwriter[ujson.Value].bimap[Renamed](
      renamed => ujson.Obj("nome" -> renamed.name),
      json => Renamed(json("nome").str)
    )
}

/** The same shape with no instance and no `derives` clause: the adapter has to derive one. */
final case class Plain(name: String)

final case class Product(name: String)

/** A model with a key, a key of another model, and a field of each JSON kind a payload carries. */
final case class Order(id: Id[Order], product: Id[Product], quantity: Int, price: BigDecimal)

/** A class upickle has no way to write: not a case class, and no instance anywhere. */
final class Opaque(val secret: String)

/** A case class whose one field is a type upickle cannot write. */
final case class Holder(opaque: Opaque)

/** A key and a count past what a `Double` holds exactly, the way a 64 bit id or an amount in cents
  * arrives as a JSON number.
  */
final case class Tally(id: Id[Tally], count: Long)

/** A flat model with an integral field, the shape a quantity in an order payload has. */
final case class Line(sku: String, quantity: Int)

/** A model whose one field the author left optional, so a JSON `null` there means absent. */
final case class Note(text: Option[String])

/** The adapter, as an application meets it: one import, and the Serde of any model it names.
  *
  * In a package outside `io.eezo` on purpose. The given is inline and expands here, in the
  * application's code, so whatever it reaches for has to be reachable from here at compile time and
  * still be there at run time; a suite inside `io.eezo` would be let through by qualified access an
  * application never has. No server is booted: the adapter knows nothing of HTTP, so its answers
  * are read straight off the `Serde`.
  */
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

  test("a key that is not a UUID is Unfit at its path") {
    val serde = summon[Serde[Order]]
    val body  = s"""{"id":"${order.id.show}","product":"seven","quantity":2,"price":"9.50"}"""
    assert(clue(unfit(serde.deserialize(bytes(body)))).endsWith(" at $['product']"))
  }

  // Decided against the ticket's four test rule, which calls a body that is not an object
  // Malformed: `[1,2]` and `42` are JSON, the seam's own definition puts JSON that does not fit the
  // model in Unfit, and the accepted spike answered the same. Calling them Malformed would also
  // refuse a payload that is a list or a number, which a Serde for any type can rightly read.
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

  // upickle reads a JSON number into an Int or a Long the way its own readers do, and the adapter
  // takes those readers as they are: making them strict would mean an Api object of eezo's, whose
  // ReadWriter is a different type from the one an author writes, so the author's instance would
  // stop winning. These pin what that choice lets through.
  test("a number with a fraction, or past the range of an Int, is read the way upickle reads it") {
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
