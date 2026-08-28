package io.eezo.db

import java.util.UUID

import io.eezo.core.Id
import io.eezo.core.internal.util.snake

trait RefTarget[A] { def table: String }

object RefTarget {
  given [A](using inner: RefTarget[A]): RefTarget[Option[A]] =
    new RefTarget[Option[A]] { def table = inner.table }
}

opaque type Ref[T] = UUID

object Ref {
  def apply[T](u: UUID): Ref[T] = u
  def to[T](id: Id[T]): Ref[T]  = id.value

  extension [T](r: Ref[T]) {
    def value: UUID = r
    def asId: Id[T] = Id[T](r)
  }

  given [T]: Column[Ref[T]] = Column[UUID].imap[Ref[T]](u => u)(r => r)

  given [T](using ct: scala.reflect.ClassTag[T]): RefTarget[Ref[T]] =
    new RefTarget[Ref[T]] {
      def table = {
        val n = ct.runtimeClass.getSimpleName
        if (n.isEmpty)
          sys.error(s"Cannot derive Ref target for anonymous class ${ct.runtimeClass.getName}")
        else snake(ct.runtimeClass.getSimpleName)
      }
    }
}
