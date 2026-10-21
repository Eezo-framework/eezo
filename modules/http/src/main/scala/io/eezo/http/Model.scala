package io.eezo.http

import scala.compiletime.{constValue, constValueTuple, erasedValue, error}
import scala.deriving.Mirror

import io.eezo.core.Id
import io.eezo.core.html.Url
import io.eezo.core.internal.util.snake

/** What [[Resource]] and [[Api]] both learn from a model's `Mirror`, learnt once.
  *
  * The two derivations mount different routes over the same rows, so the facts they read off the
  * case class have to agree: the same plural, the same key, the same way of putting a value back in
  * a field. Kept in one value rather than worked out by each derivation, so that a change to how
  * the key is found or a row is rebuilt cannot reach one and miss the other.
  *
  * A class built by an ordinary constructor rather than by the `inline def` that feeds it, because
  * whatever an `inline def` returns is duplicated into every model's companion.
  */
private[http] final class Model[A](
    val name: String,
    val labels: List[String],
    val keyIndex: Int,
    build: Array[Any] => A
) {

  /** The route name, inflected off the class name.
    *
    * It names paths and nothing else. A store no longer takes a bucket, so this string cannot reach
    * storage even by accident, and a legacy table name renames no URL.
    */
  val plural: String = Model.pluralise(snake(name))

  /** A [[Url.Mounted]] rather than a `String`, so that every link a page renders and every
    * `Location` a write sets travels with `Route.under`. Derivation has no mount to bake in; the
    * prefix is only the one each derivation always has, nothing for the pages and `/api` for the
    * calls, so both mount the same plural.
    */
  def collection(prefix: String): Url = Url.Mounted(s"$prefix/$plural")

  def keyOf(row: A): Id[A] =
    row.asInstanceOf[Product].productElement(keyIndex).asInstanceOf[Id[A]]

  /** The row with fields replaced, addressed by position rather than by name so that a caller
    * writing the same fields on every request looks the names up once, when it mounts. All of them
    * in one rebuild, applied in the order given, so a later write wins over an earlier one.
    */
  def set(row: A, writes: (Int, Any)*): A = {
    val values = row.asInstanceOf[Product].productIterator.toArray[Any]
    writes.foreach((at, value) => values(at) = value)
    build(values)
  }
}

private[http] object Model {

  /** `derivation` is an inline literal because `compiletime.error` accepts only a message it can
    * fold to a constant, and a refusal that names the derivation the user wrote reads as theirs.
    */
  inline def of[A](inline derivation: String)(using m: Mirror.ProductOf[A]): Model[A] =
    new Model[A](
      constValue[m.MirroredLabel],
      constValueTuple[m.MirroredElemLabels].toList.map(_.asInstanceOf[String]),
      keyIndex[A, m.MirroredElemLabels, m.MirroredElemTypes](derivation, 0),
      values => m.fromProduct(Tuple.fromArray(values))
    )

  /** The index of the field named `id`, which has to be typed `Id[A]`.
    *
    * A separate check with its own message rather than an `@implicitNotFound` on the trait, because
    * "no `Form` instance for `Widget`" and "`Widget`'s `id` is a `Long`" are different mistakes and
    * one message cannot say both well.
    *
    * The key type is fixed rather than summoned, and that is `create`'s doing: it has to mint a key
    * before there is anything to insert, and `Id.gen()` is the only generator eezo has. A
    * `Keygen[K]` typeclass would be the same constraint with a name on it, designed against one
    * implementation. A keyless model such as `Login(email, password)` still forms and parses; it
    * does not mount, because a `create` whose success has nowhere to redirect and an `index` that
    * cannot link a row are not half a resource.
    */
  private[http] inline def keyIndex[A, Labels <: Tuple, Types <: Tuple](
      inline derivation: String,
      inline soFar: Int
  ): Int =
    inline erasedValue[Labels] match {
      case _: EmptyTuple =>
        error(
          "A model deriving " + derivation + " needs a field named `id`, typed Id[Model]: its " +
            "routes read it out of the path, and `create` mints one with Id.gen()."
        )
      case _: (label *: labels) =>
        inline erasedValue[Types] match {
          case _: (tpe *: types) =>
            inline erasedValue[label] match {
              case _: "id" =>
                inline erasedValue[tpe] match {
                  case _: Id[A] => soFar
                  case _        =>
                    error(
                      "A model deriving " + derivation + " needs its `id` field typed " +
                        "Id[Model]: `create` mints a key with Id.gen(), which no other key type has."
                    )
                }
              case _ => keyIndex[A, labels, types](derivation, soFar + 1)
            }
        }
    }

  /** The routes a derivation mounts: one per [[Action]] the model keeps, each wrapped by the
    * declaration only when it names that route's role, so a model can guard its writes and leave
    * its index open. Applied here, where the [[Action]] that earned a route is still in hand: one
    * step later, a `Seq[Route]` has only methods and paths, and the mapping back would be each
    * derivation's table written a second time. Shared by [[Resource]] and [[Api]] so that
    * subtraction and guarding mean the same thing on a page and on a JSON route; `mint` answers
    * `None` for a role the derivation has no route for.
    */
  def derivedRoutes[A](actions: Actions[A], guarded: Guarded[A])(
      mint: Action => Option[Route]
  ): Seq[Route] =
    Action.values.toSeq.filter(actions.has).flatMap { action =>
      mint(action)
        .map(route => if (guarded.actions.contains(action)) guarded.through(route) else route)
    }

  /** #110's deliberately dumb inflector: `+s`, a consonant before `y` becoming `ies`, and a
    * sibilant taking `es`. No dictionary and no irregular list, because a plural that reads badly
    * is a companion override away, while a dictionary is a dependency and a surprise.
    *
    * Underscores survive, so `blog_post` mounts at `/blog_posts`.
    */
  def pluralise(word: String): String =
    if (word.isEmpty) word
    else if (
      word.endsWith("y") && word.length > 1 && !"aeiou".contains(word.charAt(word.length - 2))
    )
      word.dropRight(1) + "ies"
    else if (
      word.endsWith("s") || word.endsWith("x") || word.endsWith("z") ||
      word.endsWith("ch") || word.endsWith("sh")
    ) word + "es"
    else word + "s"
}
