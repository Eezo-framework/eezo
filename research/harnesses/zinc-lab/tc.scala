package model

import scala.deriving.Mirror
import scala.compiletime.{erasedValue, summonInline, constValue}

// Eight independent Mirror based derivable typeclasses. Every `derived` is an
// `inline def`, which is how Magnum's DbCodec and every other Scala 3
// derivation of this shape works. Used by derives-scale.sh to vary the LENGTH
// of a `derives` clause independently of the number of models.

trait Codec[A]:
  def encode(a: A): String
object Codec:
  given Codec[String] with { def encode(a: String) = a }
  given Codec[Int] with { def encode(a: Int) = a.toString }
  given Codec[Long] with { def encode(a: Long) = a.toString }
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

object Labels:
  inline def of[T <: Tuple]: List[String] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (t *: ts)  => constValue[t].asInstanceOf[String] :: of[ts]

trait Table[A]:
  def columns: List[String]
object Table:
  inline def derived[A](using m: Mirror.ProductOf[A]): Table[A] =
    val cols = Labels.of[m.MirroredElemLabels]
    new Table[A] { def columns = cols }

trait Form[A]:
  def html: String
object Form:
  inline def derived[A](using m: Mirror.ProductOf[A]): Form[A] =
    val cols = Labels.of[m.MirroredElemLabels]
    new Form[A] { def html = cols.map(c => s"<input name='$c'>").mkString }

trait Resource[A]:
  def routes: List[String]
object Resource:
  inline def derived[A](using m: Mirror.ProductOf[A]): Resource[A] =
    val cols = Labels.of[m.MirroredElemLabels]
    new Resource[A] { def routes = List("GET /", "POST /") ++ cols.map(c => s"GET /:id/$c") }

trait Show[A]:
  def show(a: A): String
object Show:
  inline def derived[A](using m: Mirror.ProductOf[A]): Show[A] =
    val cols = Labels.of[m.MirroredElemLabels]
    new Show[A] { def show(a: A) = cols.mkString("<", ",", ">") }

trait Json[A]:
  def json(a: A): String
object Json:
  inline def derived[A](using m: Mirror.ProductOf[A]): Json[A] =
    val cols = Labels.of[m.MirroredElemLabels]
    new Json[A]:
      def json(a: A) =
        a.asInstanceOf[Product].productIterator.zip(cols.iterator)
          .map((v, c) => s""""$c":"$v"""").mkString("{", ",", "}")

trait Csv[A]:
  def header: String
object Csv:
  inline def derived[A](using m: Mirror.ProductOf[A]): Csv[A] =
    val cols = Labels.of[m.MirroredElemLabels]
    new Csv[A] { def header = cols.mkString(",") }

trait Diff[A]:
  def fields: List[String]
object Diff:
  inline def derived[A](using m: Mirror.ProductOf[A]): Diff[A] =
    val cols = Labels.of[m.MirroredElemLabels]
    new Diff[A] { def fields = cols }
