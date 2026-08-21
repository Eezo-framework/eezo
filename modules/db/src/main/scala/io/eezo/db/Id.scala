package io.eezo.db

import java.util.UUID

opaque type Id[T] = UUID

object Id {
  def apply[T](u: UUID): Id[T] = u
  def gen[T](): Id[T]          = UUID.randomUUID()

  extension [T](id: Id[T]) {
    def value: UUID  = id
    def show: String = id.toString
  }

  given [T]: Column[Id[T]] = Column[UUID].imap[Id[T]](u => u)(id => id)
}
