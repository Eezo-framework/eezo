package io.eezo.http

import java.time.{Instant, LocalDate, LocalDateTime}
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
  * browser. What a Field adds is only what an input needs, its type, its shown text and whether it
  * renders ticked. It does not extend Parser, so a Field can never stand where a Parser is asked
  * for, and a checkbox's lenient reading of `on` cannot leak into anything that is not a form.
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
    ticked: X => Boolean = (_: X) => false
) {

  /** Public so a Field built over another can compose over the same reading, rather than summoning
    * a Parser of its own that may read differently, as the core Boolean does.
    */
  def parser: Parser[X] = summon[Parser[X]]

  /** The field's value as text: what an input is filled with, and what a show page or an index
    * prints. A checkbox has no text to fill, so `Form.render` asks [[checked]] instead.
    */
  def show(x: X): String = write(x)

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

  given Field[Double] = new Field[Double]("number", _.toString)

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

  given Field[Instant] = new Field[Instant]("datetime-local", _.toString)

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
      _.exists(inner.checked)
    )(using Parser.option(using inner.parser))
}
