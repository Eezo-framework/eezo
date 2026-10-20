package io.eezo.json.upickle

import java.util.UUID

import scala.compiletime.{summonFrom, summonInline}
import scala.deriving.Mirror
import scala.reflect.ClassTag

import _root_.upickle.core.TraceVisitor.TraceException
import _root_.upickle.core.{
  Abort,
  AbortException,
  ArrVisitor,
  NoOpVisitor,
  ObjVisitor,
  StringVisitor,
  TraceVisitor,
  Visitor
}
import _root_.upickle.default.{ReadWriter, macroRW, readwriter}
import ujson.Readable

import io.eezo.core.Id
import io.eezo.http.api.Serde

/** A key crosses the wire as the text [[io.eezo.core.Id.show]] gives, the same text it has in a
  * path and in a form, so a client can put the `id` it read from one response into the URL of the
  * next with nothing to translate.
  */
given idReadWriter[A]: ReadWriter[Id[A]] =
  readwriter[String].bimap[Id[A]](_.show, text => Id(UUID.fromString(text)))

/** The [[io.eezo.http.api.Serde]] of any model, through upickle.
  *
  * The author's own `ReadWriter` wins when one is in scope, so a model that already crosses the
  * wire in a shape of its own keeps it; otherwise a case class has one derived, so a flat model
  * needs no `derives` clause to be a payload. Derivation reaches no further than that one case
  * class: each of its fields needs a `ReadWriter` of its own, and so does the model when it sits
  * inside a `List` or an `Option`. Only a case class is derived because `List` and `Option` are
  * sums of the standard library too, and deriving them would end in an error about `::` or `Some`
  * rather than the collection the author wrote; anything else is asked for as an instance, so the
  * compiler's message names the type at the summon site. Inline because both questions can only be
  * asked where the model is known: neither the instance nor the `Mirror` and `ClassTag` derivation
  * needs resolves for an abstract `A`.
  *
  * Imported, so it sits in the caller's lexical scope and is found before any `Serde` in a model's
  * companion. An application that writes its own `Serde` for one model brings it into scope at a
  * nearer level than this import for it to win.
  */
inline given serde[A]: Serde[A] = summonFrom {
  case rw: ReadWriter[A]         => adapt(rw)
  case given Mirror.ProductOf[A] =>
    summonFrom {
      case given ClassTag[A] => adapt(macroRW[A])
      case _                 => adapt(summonInline[ReadWriter[A]])
    }
  case _ => adapt(summonInline[ReadWriter[A]])
}

/** The one place the inline given hands off to compiled code, so the class behind it is compiled
  * once rather than copied into every call site, and stays out of the public API: an application
  * writes `serde`, never this.
  */
private def adapt[A](rw: ReadWriter[A]): Serde[A] = UpickleSerde(rw)

/** A `ReadWriter` seen through the seam. It answers `Either` and throws nothing of eezo's: the HTTP
  * status a failure earns is `http`'s decision, and an adapter that threw would make it twice.
  *
  * A JSON number becomes an `Int` or a `Long` the way upickle's own readers make it: a fraction is
  * dropped, a quoted number is read, and a number past the range of an `Int` wraps. Strict readers
  * would need an upickle `Api` of eezo's, whose `ReadWriter` is a type apart from the one an author
  * writes, so the author's instance would stop winning; a model that must refuse such a number says
  * so in a `ReadWriter` of its own.
  */
private final class UpickleSerde[A](rw: ReadWriter[A]) extends Serde[A] {

  def serialize(value: A): String = _root_.upickle.default.write(value)(using rw)

  def deserialize(body: Array[Byte]): Either[Serde.Failure, A] =
    parsed(body).flatMap(_ => fitted(body, rw))

  def deserialize(body: Array[Byte], decided: Map[String, Id[Any]]): Either[Serde.Failure, A] =
    parsed(body).flatMap(_ => fitted(body, Decided(rw, decided.view.mapValues(_.show).toMap)))

  /** Whether the body is JSON at all, checked on its own before any reading, so that which step
    * failed is what tells a body that was never JSON from one that was and did not fit, rather than
    * which exception the library happened to wrap. Nothing is built: the parser walks the bytes
    * with a stack of its own, so a body nested past any model costs no thread stack.
    */
  private def parsed(body: Array[Byte]): Either[Serde.Failure, Unit] =
    traced(
      TraceVisitor.withTrace(true, NoOpVisitor)(Readable.fromByteArray(body).transform(_))
    ).left
      .map(Serde.Failure.Malformed(_))

  /** The body read into the model straight from its bytes. Through no `ujson.Value` in between,
    * because that tree holds every number as a `Double`, so a `Long` past 2^53 would come back
    * changed, and reading it back walks the tree by recursion, which a deeply nested body turns
    * into a `StackOverflowError` no `Either` can hold.
    *
    * The reader goes through [[NoNulls]] because upickle takes a JSON `null` for the model and for
    * any of its fields, which no handler is written to receive.
    */
  private def fitted(body: Array[Byte], model: Visitor[Any, A]): Either[Serde.Failure, A] =
    traced(
      TraceVisitor.withTrace(true, NoNulls(model))(Readable.fromByteArray(body).transform(_))
    ).left
      .map(Serde.Failure.Unfit(_))

  /** upickle reports a failure as the JSON path, with the complaint as its cause, and both go in
    * the one reason, so a client learns what was wrong and where. A complaint the model raised
    * leaves out the byte offset the parser adds to it, because the path already says where, in
    * words a client can act on.
    */
  private def traced[B](reading: => B): Either[String, B] =
    try Right(reading)
    catch {
      case trace: TraceException =>
        val complaint = trace.getCause match {
          case abort: AbortException => abort.clue
          case other                 => Option(other.getMessage).getOrElse(other.toString)
        }
        Left(s"$complaint at ${trace.jsonPath}")
    }
}

/** A model's reader with every field eezo decided put under its name as the body streams through
  * it, each as the text its key has on the wire.
  *
  * On the way past rather than by editing a parsed tree, for the same reasons the body is never
  * parsed into one: numbers stay exact and nesting costs no thread stack. Whatever the body carried
  * under a decided name is skipped unread, as often as it names it, and the decided values go in
  * last, so a body that said nothing there reads the same as one that said something else. A name
  * is compared after the parser has undone its escapes, so no spelling of it gets through. Anything
  * but an object at the top goes to the model untouched, which refuses it in its own words.
  */
private final class Decided[A](model: Visitor[Any, A], decided: Map[String, String])
    extends Visitor.Delegate[Any, A](model) {

  override def visitObject(length: Int, jsonableKeys: Boolean, index: Int): ObjVisitor[Any, A] = {
    val fields = model.visitObject(length, jsonableKeys, index)
    new ObjVisitor[Any, A] {
      private var keyIndex = index
      private var skipping = false

      def visitKey(index: Int): Visitor[?, ?] = {
        keyIndex = index
        StringVisitor
      }

      def visitKeyValue(name: Any): Unit = {
        val text = name.toString
        skipping = decided.contains(text)
        if (!skipping) named(text, keyIndex)
      }

      def subVisitor: Visitor[?, ?] = if (skipping) NoOpVisitor else fields.subVisitor

      def visitValue(value: Any, index: Int): Unit =
        if (!skipping) fields.visitValue(value, index)

      def visitEnd(index: Int): A = {
        decided.foreach { (name, key) =>
          named(name, index)
          fields.visitValue(fields.subVisitor.visitString(key, index), index)
        }
        fields.visitEnd(index)
      }

      private def named(name: String, index: Int): Unit =
        fields.visitKeyValue(fields.visitKey(index).visitString(name, index))
    }
  }
}

/** A reader that refuses a JSON `null` wherever the reader it wraps would turn it into a Scala
  * `null`, at the top and in every field and element below it.
  *
  * upickle's own readers take `null` for a `String`, a key or a decimal and hand it on as a `null`,
  * and for an `Int` they hand on a boxed `null` that unboxes to `0`, so a client that sent nothing
  * would be read as having sent something, and the handler would meet the `null` later as a 500
  * rather than the client hearing a 422 now. A reader that gives `null` a meaning of its own, as an
  * `Option` reads it as `None`, keeps it: only a `null` that came out as `null` is refused, and at
  * its own path, because the refusal is raised inside the trace.
  */
private final class NoNulls[T, J](model: Visitor[T, J]) extends Visitor.Delegate[T, J](model) {

  override def visitNull(index: Int): J = {
    val value = model.visitNull(index)
    if (value == null) throw Abort("expected a value got null")
    value
  }

  override def visitObject(length: Int, jsonableKeys: Boolean, index: Int): ObjVisitor[T, J] = {
    val fields = model.visitObject(length, jsonableKeys, index)
    new ObjVisitor[T, J] {
      def visitKey(index: Int): Visitor[?, ?]    = fields.visitKey(index)
      def visitKeyValue(name: Any): Unit         = fields.visitKeyValue(name)
      def subVisitor: Visitor[?, ?]              = NoNulls(fields.subVisitor)
      def visitValue(value: T, index: Int): Unit = fields.visitValue(value, index)
      def visitEnd(index: Int): J                = fields.visitEnd(index)
    }
  }

  override def visitArray(length: Int, index: Int): ArrVisitor[T, J] = {
    val items = model.visitArray(length, index)
    new ArrVisitor[T, J] {
      def subVisitor: Visitor[?, ?]              = NoNulls(items.subVisitor)
      def visitValue(value: T, index: Int): Unit = items.visitValue(value, index)
      def visitEnd(index: Int): J                = items.visitEnd(index)
    }
  }
}
