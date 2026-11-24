package io.eezo.core

import java.util.UUID

/** A model's key: a UUID that remembers what it identifies. `Id[Author]` and `Id[Book]` are
  * different types, though both are a bare UUID at runtime.
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
