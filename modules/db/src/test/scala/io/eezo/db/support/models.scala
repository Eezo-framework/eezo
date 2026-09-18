package io.eezo.db.support

import io.eezo.db.*
import io.eezo.core.Id

import java.time.{Instant, LocalDate}
import java.util.UUID

/** An opaque type whose validation *is* the column's CHECK constraint. */
opaque type Title = String
object Title {
  def apply(s: String): Title            = s
  extension (t: Title) def value: String = t
  given Column[Title]                    =
    Column[String].withCheck(Check.MaxLen(100)).imap[Title](s => s)(t => t)
}

case class Author(
    id: Id[Author],
    name: String,
    country: Option[String],
    mentor: Option[Ref[Author]]
) derives Table

case class PublishingHouse(
    id: Id[PublishingHouse],
    name: String,
    location: String
) derives Table

case class Book(
    id: Id[Book],
    author: Ref[Author],
    title: Title,
    publishedOn: Option[LocalDate],
    isbn: Option[String],
    publishedBy: Option[Ref[PublishingHouse]],
    format: String
) derives Table

/** The reference schema: a required FK, two optional ones, a self-reference, an opaque type
  * carrying a check, a composite index and a unique index.
  */
object Library extends Schema {
  val authors = table[Author]
  val books   = table[Book].index(_.author, _.publishedOn, _.publishedBy).unique(_.title)
  val houses  = table[PublishingHouse]
}

/** Every `Column` given whose value survives a round-trip through `==`.
  *
  * `Array[Byte]` is absent because case-class equality on arrays is reference equality — see
  * `Blob`. `Option[Instant]` and `Option[BigDecimal]` are absent because they throw on a NULL read;
  * that is BACKLOG item 19 and `CodecSuite` pins it directly.
  */
case class Widget(
    id: Id[Widget],
    name: String,
    count: Int,
    size: Long,
    active: Boolean,
    price: BigDecimal,
    external: UUID,
    day: LocalDate,
    at: Instant,
    note: Option[String],
    when: Option[LocalDate],
    tally: Option[Int],
    flagged: Option[Boolean]
) derives Table

object Widgets extends Schema { val widgets = table[Widget] }

case class Blob(id: Id[Blob], payload: Array[Byte]) derives Table
object Blobs extends Schema { val blobs = table[Blob] }

/** For the NULL-read cases in BACKLOG item 19. */
case class Moment(id: Id[Moment], at: Option[Instant]) derives Table
object Moments extends Schema { val moments = table[Moment] }

case class Money(id: Id[Money], amount: Option[BigDecimal]) derives Table
object Monies extends Schema { val monies = table[Money] }

/** A model whose rows belong to somebody, for the owner seam.
  *
  * The owner is a plain `String` rather than an `Id[User]` because `db` has no user model and needs
  * none: what it binds is whatever `Column[V]` the caller brought, and a `String` keeps this suite
  * and the in-memory one scoped by the same type.
  */
case class Memo(id: Id[Memo], owner: String, text: String) derives Table
object Memos extends Schema { val memos = table[Memo] }
