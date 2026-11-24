package io.eezo.core

import java.net.{URI, URISyntaxException}
import java.time.{Instant, LocalDate, LocalDateTime}
import java.util.UUID

import scala.annotation.implicitNotFound

import io.eezo.core.html.Url

/** How text from outside becomes a value of one Scala type, and what a missing text means for it. A
  * form field and a configuration value both read their text through one.
  *
  * A message is shown to whoever typed the text, a user of a form or an operator at boot. Write it
  * as a predicate about the value, such as `is not a number`, and never repeat the text: the text
  * may be a secret.
  */
@implicitNotFound(
  "No Parser for ${X}, so text cannot be read into one.\n" +
    "Provide one in scope:\n" +
    "  given Parser[${X}] = text => ... // Right(value), or Left(\"is not a ...\")"
)
trait Parser[X] {

  /** Reads the text, or says why it will not read. */
  def read(text: String): Either[String, X]

  /** The value to use when there is no text at all, if there is one.
    *
    * It is a separate answer from reading the empty text, because a missing key and an empty one
    * differ for some sources: a browser omits an unchecked checkbox, and an unset variable is not
    * one set to nothing. Give one only for a type with a sensible default; every other type is then
    * reported as required.
    */
  def absent: Option[X] = None
}

object Parser {

  private def numeric[X](parse: String => Option[X]): Parser[X] =
    text => parse(text.trim).toRight("is not a number")

  private def fromThrowingParse[X](message: String)(parse: String => X): Parser[X] = text =>
    try Right(parse(text.trim))
    catch { case _: RuntimeException => Left(message) }

  given Parser[String] = text => Right(text)

  given Parser[Int] = numeric(_.toIntOption)

  given Parser[Long] = numeric(_.toLongOption)

  given Parser[Double] = numeric(_.toDoubleOption)

  /** Strict: reads `true`, `1`, `yes`, `false`, `0` and `no`, in any case, and refuses anything
    * else, such as `ture` or a checkbox's `on`. The form side has its own lenient reading for a
    * checkbox.
    *
    * Absent is `false`, so a flag nobody set is off rather than required.
    */
  given Parser[Boolean] with {
    def read(text: String): Either[String, Boolean] =
      text.trim.toLowerCase match {
        case "true" | "1" | "yes" => Right(true)
        case "false" | "0" | "no" => Right(false)
        case _                    => Left("is not true or false")
      }
    override def absent: Option[Boolean] = Some(false)
  }

  given Parser[UUID] = fromThrowingParse("is not an id")(UUID.fromString)

  given Parser[LocalDate] = fromThrowingParse("is not a date")(LocalDate.parse)

  given Parser[LocalDateTime] = fromThrowingParse("is not a date and time")(LocalDateTime.parse)

  given Parser[Instant] = fromThrowingParse("is not a date and time")(Instant.parse)

  /** Reads the texts a UUID reads, and no others. */
  given [T]: Parser[Id[T]] = text => summon[Parser[UUID]].read(text).map(Id.apply)

  /** Blank text and no text are both `None`, so an optional value is never reported as required.
    *
    * Named, so that you can hand it its inner Parser explicitly.
    */
  given option[X](using inner: Parser[X]): Parser[Option[X]] with {
    def read(text: String): Either[String, Option[X]] =
      if (text.trim.isEmpty) Right(None) else inner.read(text).map(Some(_))
    override def absent: Option[Option[X]] = Some(None)
  }

  /** Always an `Absolute`, because text from outside is a finished address. The surrounding blanks
    * are trimmed and the rest is kept verbatim.
    */
  given Parser[Url] = text => {
    val trimmed = text.trim
    if (trimmed.isEmpty) Left("is not a url")
    else
      try {
        val _ = new URI(trimmed)
        Right(Url.Absolute(trimmed))
      } catch { case _: URISyntaxException => Left("is not a url") }
  }
}
