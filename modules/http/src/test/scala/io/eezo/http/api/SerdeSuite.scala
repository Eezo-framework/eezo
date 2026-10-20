package io.eezo.http.api

import java.nio.charset.StandardCharsets

import io.eezo.core.Id

/** A model nobody wrote an instance for. Top level so that the message names it by its package, as
  * it would name an application's model, with no enclosing suite in front.
  */
final case class Order(quantity: Int)

/** The seam a model crosses the wire through, held by `http` with no JSON library behind it.
  *
  * The instance here is written by hand on purpose: whatever an adapter does, the seam has to be
  * something an application can implement alone, and the message it reads when it has neither an
  * adapter nor an instance is the only documentation it is guaranteed to meet.
  */
class SerdeSuite extends munit.FunSuite {

  private final case class Note(id: Id[Note], text: String)

  /** A deliberately crude instance: `id|text`, so that nothing in it could be mistaken for JSON. */
  private given Serde[Note] with {
    def serialize(value: Note): String = s"${value.id.show}|${value.text}"

    def deserialize(body: Array[Byte]): Either[Serde.Failure, Note] =
      new String(body, StandardCharsets.UTF_8).split('|') match {
        case Array(id, text) if id.nonEmpty =>
          Right(Note(Id(java.util.UUID.fromString(id)), text))
        case Array(_, _) => Left(Serde.Failure.Unfit("no id at $"))
        case _           => Left(Serde.Failure.Malformed("no bar at $"))
      }

    def deserialize(body: Array[Byte], decided: Map[String, Id[Any]]): Either[Serde.Failure, Note] =
      deserialize(body).map(note =>
        decided.get("id").fold(note)(id => note.copy(id = Id(id.value)))
      )
  }

  private val key  = Id.gen[Note]()
  private val note = Note(key, "hello")

  private def bytes(text: String): Array[Byte] = text.getBytes(StandardCharsets.UTF_8)

  test("an application writes an instance with nothing but the three methods") {
    val serde = summon[Serde[Note]]
    assertEquals(serde.serialize(note), s"${key.show}|hello")
    assertEquals(serde.deserialize(bytes(s"${key.show}|hello")), Right(note))
    assertEquals(serde.deserialize(bytes("|hello")), Left(Serde.Failure.Unfit("no id at $")))
    assertEquals(serde.deserialize(bytes("hello")), Left(Serde.Failure.Malformed("no bar at $")))
    val other = Id.gen[Note]()
    assertEquals(serde.deserialize(bytes(s"${key.show}|hi"), other), Right(Note(other, "hi")))
  }

  test("the keyed read is the general one, handed the key alone under id") {
    var handed: Map[String, Id[Any]] = Map.empty
    val serde                        = new Serde[Note] {
      def serialize(value: Note): String                              = ""
      def deserialize(body: Array[Byte]): Either[Serde.Failure, Note] = Left(unread)
      def deserialize(
          body: Array[Byte],
          decided: Map[String, Id[Any]]
      ): Either[Serde.Failure, Note] = {
        handed = decided
        Left(unread)
      }
      private def unread = Serde.Failure.Unfit("not read here")
    }
    assertEquals(
      serde.deserialize(bytes("anything"), key),
      Left(Serde.Failure.Unfit("not read here"))
    )
    assertEquals(handed, Map("id" -> Id[Any](key.value)))
  }

  test("a failure is one reason, in one of two kinds") {
    val failures: Seq[Serde.Failure] =
      Seq(Serde.Failure.Malformed("a at $"), Serde.Failure.Unfit("b at $['c']"))
    val reasons = failures.map {
      case Serde.Failure.Malformed(reason) => s"malformed: $reason"
      case Serde.Failure.Unfit(reason)     => s"unfit: $reason"
    }
    assertEquals(reasons, Seq("malformed: a at $", "unfit: b at $['c']"))
  }

  test("a model with no instance and no adapter names the module and the import to add") {
    val errors = compileErrors("summon[Serde[Order]]")
    assert(
      clue(errors).contains(
        "No Serde instance for io.eezo.http.api.Order. Add eezo-upickle to the build and " +
          "import io.eezo.json.upickle.given, or write a given api.Serde[io.eezo.http.api.Order]"
      )
    )
  }
}
