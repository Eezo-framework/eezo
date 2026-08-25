package eezo.http

import java.time.{Instant, LocalDate, LocalDateTime}
import java.util.UUID

import scala.annotation.implicitNotFound

/** One case class field, as HTML sees it.
  *
  * Deliberately not `db`'s `Column`, which is `PreparedStatement` and `ResultSet` and nothing else.
  * A model with no database behind it still has to form, and `Login(email, password)` is the case
  * that proves it: it will never be a table, and it renders and parses like anything else.
  *
  * `absent` is the whole reason the trait has four members rather than three. A browser omits an
  * unchecked checkbox entirely rather than submitting `false`, so "the key is missing" has to be
  * answerable per type. Rails answers it by rendering a hidden companion input before every
  * checkbox and then relying on last-wins; that is a second mental model, and a value the server
  * cannot distinguish from a genuine submission. Here `Field[Boolean].absent` is `Some(false)`,
  * `Field[Option[X]].absent` is `Some(None)`, and everything else is `None`, which is exactly the
  * set of fields that can report `"is required"`.
  */
@implicitNotFound(
  "No Field instance for ${X}, so it cannot appear in a form.\n" +
    "Provide one in scope:\n" +
    "  given Field[${X}] = Field.of(\"text\")(_.toString)(s => ...)"
)
trait Field[X] {

  /** The `type` attribute of the rendered `<input>`. */
  def inputType: String

  /** The field's value as submitted text. */
  def show(x: X): String

  /** Reads submitted text, or says why it will not read. The message is what reaches the user, so
    * it reads as a predicate about the value rather than as a parser's complaint.
    */
  def read(text: String): Either[String, X]

  /** The value to use when the key is not in the submission at all, if there is one. */
  def absent: Option[X] = None
}

object Field {

  def apply[X](using f: Field[X]): Field[X] = f

  /** Builds an instance from the three members that always differ. */
  def of[X](tpe: String)(write: X => String)(parse: String => Either[String, X]): Field[X] =
    new Field[X] {
      def inputType: String                     = tpe
      def show(x: X): String                    = write(x)
      def read(text: String): Either[String, X] = parse(text)
    }

  private def numeric[X](parse: String => Option[X]): String => Either[String, X] =
    text => parse(text.trim).toRight("is not a number")

  given Field[String] = of[String]("text")(identity)(text => Right(text))

  given Field[Int] = of[Int]("number")(_.toString)(numeric(_.toIntOption))

  given Field[Long] = of[Long]("number")(_.toString)(numeric(_.toLongOption))

  given Field[Double] = of[Double]("number")(_.toString)(numeric(_.toDoubleOption))

  /** A checkbox. Every value a browser sends for a checked box means `true`; the only thing that
    * means `false` is the key being absent, which is [[Field.absent]]'s job rather than this one's.
    */
  given Field[Boolean] = new Field[Boolean] {
    def inputType: String                           = "checkbox"
    def show(x: Boolean): String                    = if (x) "on" else ""
    def read(text: String): Either[String, Boolean] =
      Right(text.trim.nonEmpty && !text.equalsIgnoreCase("false"))
    override def absent: Option[Boolean] = Some(false)
  }

  given Field[UUID] = of[UUID]("text")(_.toString) { text =>
    try Right(UUID.fromString(text.trim))
    catch { case _: IllegalArgumentException => Left("is not an id") }
  }

  given Field[LocalDate] = of[LocalDate]("date")(_.toString) { text =>
    try Right(LocalDate.parse(text.trim))
    catch { case _: RuntimeException => Left("is not a date") }
  }

  given Field[LocalDateTime] = of[LocalDateTime]("datetime-local")(_.toString) { text =>
    try Right(LocalDateTime.parse(text.trim))
    catch { case _: RuntimeException => Left("is not a date and time") }
  }

  given Field[Instant] = of[Instant]("datetime-local")(_.toString) { text =>
    try Right(Instant.parse(text.trim))
    catch { case _: RuntimeException => Left("is not a date and time") }
  }

  /** An optional field. Empty text and an absent key are the same thing to a browser, so both are
    * `None` here, and neither can ever be `"is required"`.
    */
  given [X](using inner: Field[X]): Field[Option[X]] = new Field[Option[X]] {
    def inputType: String                             = inner.inputType
    def show(x: Option[X]): String                    = x.map(inner.show).getOrElse("")
    override def absent: Option[Option[X]]            = Some(None)
    def read(text: String): Either[String, Option[X]] =
      if (text.trim.isEmpty) Right(None) else inner.read(text).map(Some(_))
  }
}
