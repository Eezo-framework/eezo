package io.eezo.http.api

import scala.annotation.implicitNotFound

import io.eezo.core.Id

/** How a model reads from and shows as JSON on an API route, the way [[io.eezo.http.Form]] is how
  * it reads from and shows as a form.
  *
  * A seam and not an implementation, because `http` depends on Jetty alone: the JSON library is the
  * application's choice, made by adding an adapter such as `eezo-upickle`, and the adapter is the
  * only code that sees it. Text out and bytes in, because those are what the wire carries, so no
  * library's value type reaches a signature here.
  *
  * The message below names the adapter's module and its import literally, since an application that
  * has neither is exactly the one that does not know what to add.
  */
@implicitNotFound(
  "No Serde instance for ${A}. Add eezo-upickle to the build and import io.eezo.json.upickle.given, or write a given api.Serde[${A}]"
)
trait Serde[A] {

  /** No `Either` here, because a value the application already holds always has a JSON shape: only
    * the way in can be wrong.
    */
  def serialize(value: A): String

  /** For a payload with no key, or one whose key the client is allowed to choose. */
  def deserialize(body: Array[Byte]): Either[Serde.Failure, A]

  /** For a write whose fields eezo has decided, the key it minted or read from the path and the
    * owner it filled from the current user: each value goes under its name over whatever the body
    * said, and a body that said nothing there is not a failure, because a client names neither the
    * row eezo is about to write nor whose row it is.
    *
    * Keys of any model, as `Id[Any]`, because an owner is the key of another model than this one
    * and the seam has no way to name which.
    */
  def deserialize(body: Array[Byte], decided: Map[String, Id[Any]]): Either[Serde.Failure, A]

  /** For a model whose key the caller owns and nothing else decided. Concrete, so an instance
    * written by hand implements one decided read and not two that could disagree.
    */
  final def deserialize(body: Array[Byte], key: Id[A]): Either[Serde.Failure, A] =
    deserialize(body, Map("id" -> Id[Any](key.value)))
}

object Serde {

  /** Why a body did not become a model, as one reason and never a list: the JSON libraries an
    * adapter wraps stop at the first wrong field, so a list would promise what none of them gives.
    *
    * Two kinds, because a client needs to know which of its mistakes to fix. The kinds follow the
    * split the HTML routes already make between a request eezo cannot decode and one that decodes
    * and does not fit.
    */
  enum Failure {
    case Malformed(reason: String)
    case Unfit(reason: String)
  }
}
