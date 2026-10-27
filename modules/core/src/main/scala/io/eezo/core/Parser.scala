package io.eezo.core

import java.net.{URI, URISyntaxException}
import java.time.{Instant, LocalDate, LocalDateTime}
import java.util.UUID

import scala.annotation.implicitNotFound

import io.eezo.core.html.Url

/** How text from outside becomes a value of one Scala type, and what a missing text means for it.
  *
  * It lives in `core` because more than forms read text. A form field and a configuration value are
  * the same question, "is this text an `Int`", asked from two modules that cannot see each other,
  * and answering it twice is how the two would come to disagree about what a number is. `http`'s
  * `Field` is composed over one of these and adds only what a browser input needs, so the reading
  * itself has one home.
  *
  * A message is shown to whoever typed the text, a form's user or an operator at boot, so it reads
  * as a predicate about the value and never repeats the text: the text may be a secret.
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
    * A separate answer from reading the empty text, because a missing key and an empty one differ
    * for some sources: a browser omits an unchecked checkbox entirely, and an unset variable is not
    * one set to nothing. Only a type that has a sensible default says so, which leaves every other
    * one able to report itself as required.
    */
  def absent: Option[X] = None
}

object Parser {

  private def numeric[X](parse: String => Option[X]): Parser[X] =
    text => parse(text.trim).toRight("is not a number")

  /** For the JDK's own parse methods, which say no by throwing. */
  private def catching[X](message: String)(parse: String => X): Parser[X] = text =>
    try Right(parse(text.trim))
    catch { case _: RuntimeException => Left(message) }

  given Parser[String] = text => Right(text)

  given Parser[Int] = numeric(_.toIntOption)

  given Parser[Long] = numeric(_.toLongOption)

  given Parser[Double] = numeric(_.toDoubleOption)

  /** Strict, because outside a browser there is no wire convention to honour. A typo such as `ture`
    * in a configuration value has to be refused rather than read as one of the two, and only the
    * spellings people write on purpose are accepted. A checkbox's `on` is a browser's convention,
    * and the form side keeps its own lenient reading for it.
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

  given Parser[UUID] = catching("is not an id")(UUID.fromString)

  given Parser[LocalDate] = catching("is not a date")(LocalDate.parse)

  given Parser[LocalDateTime] = catching("is not a date and time")(LocalDateTime.parse)

  given Parser[Instant] = catching("is not a date and time")(Instant.parse)

  /** Delegates to the UUID reading, so a key and a bare UUID cannot accept different texts. */
  given [T]: Parser[Id[T]] = text => summon[Parser[UUID]].read(text).map(Id.apply)

  /** Blank text and no text are the same thing for an optional value, so neither can ever be
    * reported as required.
    *
    * Named so it can be handed its inner Parser explicitly: an optional form field lifts the
    * reading its inner Field was built with, which need not be the given one for `X`.
    */
  given option[X](using inner: Parser[X]): Parser[Option[X]] with {
    def read(text: String): Either[String, Option[X]] =
      if (text.trim.isEmpty) Right(None) else inner.read(text).map(Some(_))
    override def absent: Option[Option[X]] = Some(None)
  }

  /** Always an `Absolute`, since text from outside is a finished address and only the application's
    * own routes know what to mount. Kept verbatim inside its surrounding blanks for the reason
    * `Absolute` itself is: normalising would mangle the scheme.
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
