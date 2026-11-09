package io.eezo.http

import java.time.{DateTimeException, Instant, LocalDate, LocalDateTime, ZoneOffset}
import java.time.temporal.ChronoUnit
import java.util.UUID

import scala.annotation.implicitNotFound

import io.eezo.core.{Id, Parser}

/** One case class field, as HTML sees it.
  *
  * Deliberately not `db`'s `Column`, which is `PreparedStatement` and `ResultSet` and nothing else.
  * A model with no database behind it still has to form, and `Login(email, password)` is the case
  * that proves it: it will never be a table, and it renders and parses like anything else.
  *
  * Composed over a [[io.eezo.core.Parser]] rather than being one, because how text becomes a value
  * is not a form's question: a configuration value asks it too, from a module that never sees a
  * browser. What a Field adds is only what an input needs, its type, its shown text, whether it
  * renders ticked and the step a number input takes. It does not extend Parser, so a Field can
  * never stand where a Parser is asked for, and a checkbox's lenient reading of `on` cannot leak
  * into anything that is not a form.
  *
  * A final class rather than a trait, so the reading has one place to live and a hand written
  * instance cannot override `read` past its Parser. [[Field.of]] is how an author writes one.
  *
  * `absent` comes from the Parser, and it is why a browser's habits need no hidden inputs here. A
  * browser omits an unchecked checkbox entirely rather than submitting `false`, so "the key is
  * missing" has to be answerable per type. Rails answers it by rendering a hidden companion input
  * before every checkbox and then relying on last wins; that is a second mental model, and a value
  * the server cannot distinguish from a genuine submission. Here `Field[Boolean].absent` is
  * `Some(false)`, `Field[Option[X]].absent` is `Some(None)`, and everything else is `None`, which
  * is exactly the set of fields that can report `"is required"`.
  */
@implicitNotFound(
  "No Field instance for ${X}, so it cannot appear in a form.\n" +
    "Provide one in scope:\n" +
    "  given Field[${X}] = Field.of(\"text\")(_.toString)(s => ...)"
)
final class Field[X: Parser](
    val inputType: String,
    write: X => String,
    ticked: X => Boolean = (_: X) => false,
    val step: Option[String] = None,
    displayed: Option[X => String] = None
) {

  /** Public so a Field built over another can compose over the same reading, rather than summoning
    * a Parser of its own that may read differently, as the core Boolean does.
    */
  def parser: Parser[X] = summon[Parser[X]]

  /** The field's value as text: what an input is filled with. A checkbox has no text to fill, so
    * `Form.render` asks [[checked]] instead.
    */
  def show(x: X): String = write(x)

  /** The field's value as a show page and an index print it. It is [[show]], unless the Field gives
    * the pages a text of its own, because the input refuses a text that a reader needs, such as the
    * zone of an `Instant`.
    */
  def display(x: X): String = displayed.fold(write(x))(_(x))

  /** Reads submitted text through the Parser. The message is what reaches the user, so it reads as
    * a predicate about the value rather than as a parser's complaint.
    */
  def read(text: String): Either[String, X] = parser.read(text)

  /** The value to use when the key is not in the submission at all, if there is one. */
  def absent: Option[X] = parser.absent

  /** Whether a checkbox holding `x` renders ticked. Only a field that renders as a checkbox has
    * anything to say, so every other one is never ticked.
    */
  def checked(x: X): Boolean = ticked(x)
}

object Field {

  def apply[X](using f: Field[X]): Field[X] = f

  /** The `<input type>` of a password box. `Form.render` never echoes a field that renders as one,
    * so whoever writes such a field names this rather than a literal of their own that can drift.
    */
  private[eezo] val PasswordInput: String = "password"

  /** Builds an instance from the three members that always differ. The reading becomes a Parser
    * known only to this Field, so writing a Field never puts a given Parser in anybody's scope.
    */
  def of[X](tpe: String)(write: X => String)(parse: String => Either[String, X]): Field[X] = {
    val parser: Parser[X] = text => parse(text)
    new Field[X](tpe, write)(using parser)
  }

  /** What a checkbox submits, read the way a browser means it. Every value a browser sends for a
    * checked box means `true`; the only thing that means `false` is the key being absent.
    *
    * Not a given, and not core's `Parser[Boolean]`, which refuses `on`. A browser's wire is the
    * only place this leniency is right; as a given it would answer any `Parser[Boolean]` asked
    * inside `object Field` or wherever its givens are imported, and read a typo in a configuration
    * value as `true`.
    */
  private[eezo] val Checkbox: Parser[Boolean] = new Parser[Boolean] {
    def read(text: String): Either[String, Boolean] =
      Right(text.trim.nonEmpty && !text.equalsIgnoreCase("false"))
    override def absent: Option[Boolean] = Some(false)
  }

  given Field[String] = new Field[String]("text", identity)

  given Field[Int] = new Field[Int]("number", _.toString)

  given Field[Long] = new Field[Long]("number", _.toString)

  /** A number input whose step is `any`. Without one a browser takes only whole steps from the
    * input's value, and refuses to submit 9.99 in an empty box.
    */
  given Field[Double] = new Field[Double]("number", _.toString, step = Some("any"))

  /** A checkbox, read through [[Checkbox]] rather than the core Boolean.
    *
    * It shows as `true` or `false`, the value rather than the wire. A ticked box submits `on`, but
    * that is the input's text and not the model's, and showing it put `on` in every index row and
    * left a `false` cell blank (#142).
    */
  given Field[Boolean] = new Field[Boolean]("checkbox", _.toString, identity)(using Checkbox)

  given Field[UUID] = new Field[UUID]("text", _.toString)

  given Field[LocalDate] = new Field[LocalDate]("date", _.toString)

  given Field[LocalDateTime] = new Field[LocalDateTime]("datetime-local", _.toString)

  /** A `datetime-local` input, read and shown in UTC.
    *
    * The input holds a date and a time with no zone, such as `2026-10-13T10:00`, and a browser
    * refuses a value that ends in `Z`. Core's Parser reads only a full instant, so this Field reads
    * the input's text through core's `LocalDateTime` Parser as UTC, and still takes a full instant
    * from a program. It shows the instant as UTC text, to the millisecond, which is the finest a
    * `datetime-local` input holds. An instant outside the years a `LocalDateTime` holds, such as
    * `Instant.MAX`, shows as the instant's own text. A show page and an index print the same text
    * followed by ` UTC`, so a reader knows the zone. ADR 0010 records why the zone is UTC.
    */
  given Field[Instant] = {
    val parser: Parser[Instant] = text =>
      summon[Parser[Instant]].read(text) match {
        case Left(why) =>
          summon[Parser[LocalDateTime]]
            .read(text)
            .map(_.toInstant(ZoneOffset.UTC))
            .left
            .map(_ => why)
        case read => read
      }
    def utc(at: Instant): String =
      try LocalDateTime.ofInstant(at, ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS).toString
      catch { case _: DateTimeException => at.toString }
    new Field[Instant]("datetime-local", utc, displayed = Some(at => s"${utc(at)} UTC"))(using
      parser
    )
  }

  /** A model's key.
    *
    * A key is never rendered into a form (see `Form`), but it is still read through its own
    * `Field`, and so through core's Parser for it, which is what keeps one reading of it rather
    * than two.
    */
  given [T]: Field[Id[T]] = new Field[Id[T]]("text", _.show)

  /** An optional field. Empty text and an absent key are the same thing to a browser, so both are
    * `None` here, and neither can ever be `"is required"`.
    *
    * Lifted from the inner Field's own Parser, not from a `Parser[X]` summoned here. A summoned one
    * would read an optional checkbox strictly and refuse its `on`, and would not exist at all for a
    * type whose Field was written with [[Field.of]].
    */
  given [X](using inner: Field[X]): Field[Option[X]] =
    new Field[Option[X]](
      inner.inputType,
      _.map(inner.show).getOrElse(""),
      _.exists(inner.checked),
      inner.step,
      Some(_.map(inner.display).getOrElse(""))
    )(using Parser.option(using inner.parser))
}
