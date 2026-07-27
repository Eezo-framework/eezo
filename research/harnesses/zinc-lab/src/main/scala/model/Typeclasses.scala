package model

import scala.deriving.Mirror
import scala.compiletime.{erasedValue, summonInline, constValue}

// --- Four independent Mirror-based derivable typeclasses, mimicking eezo's
// --- DbCodec / Table / Form / Resource.

trait Codec[A]:
  def encode(a: A): String
object Codec:
  given Codec[String] with { def encode(a: String) = a }
  given Codec[Int] with { def encode(a: Int) = a.toString }
  given Codec[Boolean] with { def encode(a: Boolean) = a.toString }

  inline def summonAll[T <: Tuple]: List[Codec[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (t *: ts)  => summonInline[Codec[t]] :: summonAll[ts]

  inline def derived[A](using m: Mirror.ProductOf[A]): Codec[A] =
    val elems = summonAll[m.MirroredElemTypes]
    new Codec[A]:
      def encode(a: A) =
        a.asInstanceOf[Product].productIterator.zip(elems.iterator)
          .map((v, c) => c.asInstanceOf[Codec[Any]].encode(v)).mkString(",")

trait Table[A]:
  def columns: List[String]
object Table:
  inline def labels[T <: Tuple]: List[String] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (t *: ts)  => constValue[t].asInstanceOf[String] :: labels[ts]
  inline def derived[A](using m: Mirror.ProductOf[A]): Table[A] =
    val cols = labels[m.MirroredElemLabels]
    new Table[A] { def columns = cols }

trait Form[A]:
  def html: String
object Form:
  inline def derived[A](using m: Mirror.ProductOf[A]): Form[A] =
    val cols = Table.labels[m.MirroredElemLabels]
    new Form[A] { def html = cols.map(c => s"<input name='$c'>").mkString }

trait Resource[A]:
  def routes: List[String]
object Resource:
  inline def derived[A](using m: Mirror.ProductOf[A]): Resource[A] =
    val cols = Table.labels[m.MirroredElemLabels]
    new Resource[A] { def routes = List("GET /", "POST /") ++ cols.map(c => s"GET /:id/$c") }
