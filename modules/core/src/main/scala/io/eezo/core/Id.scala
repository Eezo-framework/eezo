package io.eezo.core

import java.util.UUID

/** A model's key: a UUID that remembers what it identifies.
  *
  * It lives in `core` because both sides of the framework need it and neither can see the other.
  * `db` stores one, `http` puts one in a path and in a form, and `db` and `http` are siblings above
  * `core`. The type itself therefore knows about neither: each instance lives downstream, in its
  * own typeclass's companion, still companion-resolvable because a given in the *typeclass's*
  * companion is found just as reliably as one in the type's.
  *
  * The phantom `T` is what makes `Id[Author]` and `Id[Book]` different types while both are a bare
  * UUID at runtime, so passing one where the other belongs does not compile.
  */
opaque type Id[T] = UUID

object Id {
  def apply[T](u: UUID): Id[T] = u
  def gen[T](): Id[T]          = UUID.randomUUID()

  extension [T](id: Id[T]) {
    def value: UUID  = id
    def show: String = id.toString
  }
}
