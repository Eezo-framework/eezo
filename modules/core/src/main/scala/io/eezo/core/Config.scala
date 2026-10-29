package io.eezo.core

import scala.deriving.Mirror

/** An application's own configuration, read from its environment into a case class it declares.
  *
  * The case class is the whole contract, as it is for a Form: its fields name the variables, its
  * defaults and `Option`s say which may be absent, and each field's Parser reads the text. There is
  * no second declaration to keep in step, and ticket 365's `eezo config` prints [[names]] from the
  * same derivation that [[read]] uses.
  *
  * Absence is decided by the case class alone and never by `Parser.absent`. A form needs that
  * answer, because a browser omits an unchecked checkbox, but an unset variable is a deployment
  * that forgot something, so a Boolean nobody set is missing rather than quietly false.
  */
trait Config[A] {

  /** A pure function of `env`, so a test hands it a map and never the process's environment. The
    * default argument is the one place the environment is read.
    */
  def read(env: Map[String, String] = sys.env): Either[Config.Refused, A]

  /** Every variable the application reads, in declaration order, without reading any of them: what
    * a command can list for an operator before a deployment has a single one set.
    */
  def names: Seq[Config.Name]
}

object Config {

  def apply[A](using config: Config[A]): Config[A] = config

  /** What an operator has to know about one variable before setting it. */
  enum Need {
    case Required, Defaulted, Optional
  }

  /** One variable, beside the field it fills. `redacted` is decided from the field's type, so that
    * whatever lists these never shows a Redacted value, nor asks for one to be typed in the clear.
    */
  final case class Name(variable: String, field: String, redacted: Boolean, need: Need)

  /** One variable that kept the case class from being built. It holds the variable and the Parser's
    * reason and never the text found, so that printing one, or a [[Refused]], cannot leak the text
    * it read.
    */
  enum Offender {
    case Missing(variable: String)
    case Malformed(variable: String, reason: String)
  }

  /** Every offender at once, in declaration order, because a deployment fixed one variable per
    * restart is a deployment fixed slowly.
    *
    * A value rather than an exception: following ADR 0006, the `Either` is answered where it is
    * returned, as a Form's is, and the boot that reads a Config decides how a refusal stops it.
    */
  final case class Refused(offenders: Seq[Offender]) {

    /** One line per offender, the variable first, for an operator reading a boot log. */
    def message: String =
      offenders
        .map {
          case Offender.Missing(variable)           => s"$variable is missing"
          case Offender.Malformed(variable, reason) => s"$variable $reason"
        }
        .mkString("\n")
  }

  /** `inline` only long enough for the macro to read the fields, then straight into `make`, which
    * is compiled once, so no anonymous class is copied to every `derives` site.
    */
  inline def derived[A](using m: Mirror.ProductOf[A]): Config[A] = make[A](slots[A], m)

  private inline def slots[A]: List[Slot] = ${ internal.ConfigMacro.slots[A] }

  /** The variable a field is read from. One function for the macro's prefix check and for the
    * lookup, so a field cannot pass the check under one spelling and be read under another. The
    * root locale, because the compiler's JVM and the application's may differ, and under a Turkish
    * one `i` would become a dotted capital nobody types.
    */
  private[core] def variable(field: String): String =
    internal.util.snake(field).toUpperCase(java.util.Locale.ROOT)

  /** The framework reads its own variables under this prefix, and a field spelling one would read
    * the framework's value or hand it the application's.
    */
  private[core] val FrameworkPrefix = "EEZO_"

  /** What the macro learns of one field, as a named tuple of public types, because the code it
    * expands to is compiled in the application's package, where nothing private to core is visible.
    */
  private[core] type Slot = (
      field: String,
      parser: Parser[Any],
      default: Option[() => Any],
      optional: Boolean,
      redacted: Boolean
  )

  private def make[A](slots: List[Slot], m: Mirror.ProductOf[A]): Config[A] = {
    val named: List[(Slot, Name)] = slots.map { slot =>
      val need =
        if (slot.default.isDefined) Need.Defaulted
        else if (slot.optional) Need.Optional
        else Need.Required
      slot -> Name(variable(slot.field), slot.field, slot.redacted, need)
    }

    /** A key in the map is never absent, the empty text included: only the case class decides what
      * absence means, so the Parser is handed whatever text is there.
      */
    def one(slot: Slot, name: Name, env: Map[String, String]): Either[Offender, Any] =
      env.get(name.variable) match {
        case Some(text) => slot.parser.read(text).left.map(Offender.Malformed(name.variable, _))
        case None       =>
          slot.default match {
            case Some(value)           => Right(value())
            case None if slot.optional => Right(None)
            case None                  => Left(Offender.Missing(name.variable))
          }
      }

    new Config[A] {

      val names: Seq[Name] = named.map(_._2)

      def read(env: Map[String, String]): Either[Refused, A] = {
        val (offenders, values) = named.partitionMap(one(_, _, env))
        if (offenders.nonEmpty) Left(Refused(offenders))
        else Right(m.fromProduct(Tuple.fromArray(values.toArray)))
      }
    }
  }
}
