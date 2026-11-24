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
  * inside a `List` or an `Option`. When an instance is missing, the compiler's message names the
  * type that needs it at the place where the `Serde` is summoned.
  *
  * Imported, it is found before any `Serde` in a model's companion. An application that writes its
  * own `Serde` for one model brings it into scope at a nearer level than this import for it to win.
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

private def adapt[A](rw: ReadWriter[A]): Serde[A] = UpickleSerde(rw)

private final class UpickleSerde[A](rw: ReadWriter[A]) extends Serde[A] {

  def serialize(value: A): String = _root_.upickle.default.write(value)(using rw)

  def deserialize(body: Array[Byte]): Either[Serde.Failure, A] =
    parsed(body).flatMap(_ => fitted(body, rw))

  def deserialize(body: Array[Byte], decided: Map[String, Id[Any]]): Either[Serde.Failure, A] =
    parsed(body).flatMap(_ => fitted(body, Decided(rw, decided.view.mapValues(_.show).toMap)))

  private def parsed(body: Array[Byte]): Either[Serde.Failure, Unit] =
    traced(
      TraceVisitor.withTrace(true, NoOpVisitor)(Readable.fromByteArray(body).transform(_))
    ).left
      .map(Serde.Failure.Malformed(_))

  private def fitted(body: Array[Byte], model: Visitor[Any, A]): Either[Serde.Failure, A] =
    traced(
      TraceVisitor.withTrace(true, NoNulls(model))(Readable.fromByteArray(body).transform(_))
    ).left
      .map(Serde.Failure.Unfit(_))

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
