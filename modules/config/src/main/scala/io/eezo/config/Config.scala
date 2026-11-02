package io.eezo.config

import scala.deriving.Mirror
import scala.jdk.CollectionConverters.*

import com.typesafe.config.{
  Config as Tree,
  ConfigException,
  ConfigFactory,
  ConfigObject,
  ConfigRenderOptions,
  ConfigResolveOptions,
  ConfigResolver,
  ConfigValue,
  ConfigValueFactory,
  ConfigValueType
}

import io.eezo.core.Parser

/** SPIKE (issue 393): the application's configuration, read from `application.conf` into one case
  * class with nested case classes as its sections.
  *
  * The derivation reads a parsed tree instead of a map of variables: each leaf by its path, rendered
  * as text, handed to the field's `Parser`. A field is either a type with a `Parser` or a case class
  * that derives `Config`, which is a section read under the field's name.
  */
trait Config[A] {

  /** A pure function of the file as parsed, unresolved, and a map standing for the environment. The
    * tree is resolved against the map alone, with the process's environment switched off, so a suite
    * hands both and never touches the real one.
    */
  def read(file: Tree, env: Map[String, String] = sys.env): Either[Config.Refused, A]

  /** Reads this class at `prefix` in the resolved tree. `None` is a section the file never states,
    * whose every required field is then missing. `subs` is the unresolved tree's substitutions by
    * the path they feed, for the refusal.
    */
  private[config] def readAt(
      resolved: Option[Tree],
      prefix: String,
      subs: Map[String, List[Config.Substitution]]
  ): Either[List[Config.Offender], A]

  /** What boot installs: the value, and every section of it under the section's own Config. */
  def install(value: A): Unit

  /** What a handler reads, as it reads a table. */
  def current: A

  /** A suite's scope: `value` is current inside `body`, and whatever was before is back after. */
  def installed[R](value: A)(body: => R): R
}

object Config {

  def apply[A](using c: Config[A]): Config[A] = c

  /** One `${?VAR}` or `${VAR}` in the file, by the path it feeds. */
  final case class Substitution(path: String, variable: String, optional: Boolean)

  enum Offender {
    case Missing(path: String, variables: List[String])
    case Malformed(path: String, reason: String)
  }

  final case class Refused(offenders: Seq[Offender]) {
    def message: String =
      offenders
        .map {
          case Offender.Missing(path, Nil)       => s"$path is missing: application.conf never states it"
          case Offender.Missing(path, variables) =>
            s"$path is missing: set ${variables.mkString(" or ")}"
          case Offender.Malformed(path, reason) => s"$path $reason"
        }
        .mkString("\n")
  }

  /** Every substitution of the unresolved tree, by the path it feeds, in file order. The library
    * exposes no walk over references, but an unresolved leaf renders as the `${?VAR}` the author
    * wrote, and a merge of a literal and a reference renders both.
    */
  def substitutions(file: Tree): List[Substitution] = walk(file.root(), "")

  private val Reference = """\$\{(\??)([^}]+)\}""".r
  private val Concise   = ConfigRenderOptions.concise()

  private def walk(obj: ConfigObject, prefix: String): List[Substitution] =
    obj.entrySet().asScala.toList.sortBy(_.getValue.origin().lineNumber()).flatMap { e =>
      val path = if (prefix.isEmpty) e.getKey else s"$prefix.${e.getKey}"
      e.getValue match {
        case o: ConfigObject   => walk(o, path)
        case v if resolved(v) => Nil
        case v                 =>
          Reference
            .findAllMatchIn(v.render(Concise))
            .map(m => Substitution(path, m.group(2).trim, m.group(1) == "?"))
            .toList
      }
    }

  /** Whether a value of the unresolved tree is a literal: `valueType` is the one public question
    * an unresolved reference refuses to answer.
    */
  def resolved(v: ConfigValue): Boolean =
    try { v.valueType(); true }
    catch { case _: ConfigException.NotResolved => false }

  /** The value at `path` in an unresolved object, literal or reference, or `None` where the file
    * neither states nor feeds it. Walked by hand, because `hasPath` resolves on the way.
    */
  def at(obj: ConfigObject, path: List[String]): Option[ConfigValue] = path match {
    case Nil          => Some(obj)
    case key :: Nil   => Option(obj.get(key))
    case key :: rest  =>
      Option(obj.get(key)) match {
        case Some(o: ConfigObject) => at(o, rest)
        case _                     => None
      }
  }

  /** The tree resolved against `env` alone, through the library's resolver hook: a reference is
    * looked up in the file first, then, with the process's environment switched off, in the map.
    * The hook and not `withFallback`, so the resolved tree holds the file's paths and nothing of
    * the map; and not `resolveWith`, which skips the file's own paths and breaks on a merged root.
    */
  def resolve(file: Tree, env: Map[String, String]): Tree = {
    val source: ConfigResolver = new ConfigResolver {
      def lookup(path: String): ConfigValue =
        env.get(path).map(ConfigValueFactory.fromAnyRef(_)).orNull
      def withFallback(fallback: ConfigResolver): ConfigResolver = this
    }
    file.resolve(
      ConfigResolveOptions.defaults().setUseSystemEnvironment(false).appendResolver(source)
    )
  }

  /** The class the application reads, checked against the file at compile time where the build
    * writes the root into the generated unit, and read at boot.
    */
  final class Root[A](val config: Config[A], val resource: String) {

    /** Parses the packaged resource, reads it against the real environment, installs on success.
      * The caller decides how a refusal stops the program.
      */
    def boot(): Either[Refused, A] = {
      val file = ConfigFactory.parseResources(resource)
      config.read(file).map { value =>
        config.install(value)
        value
      }
    }
  }

  object Root {

    /** What the generated unit's object is: the build names the class, the plugin writes
      * `object ConfigFile extends Config.Root.Generated` beside `Routes`, and boot finds it here.
      */
    trait Generated { def root: Root[?] }

    /** The root the build wrote, or `None` in a project whose build names no class. Looked up by
      * its fixed name through the context class loader, which is the application's under `run`
      * and in the forked JVM alike.
      */
    def generated: Option[Root[?]] = {
      val loader = Option(Thread.currentThread.getContextClassLoader).getOrElse(getClass.getClassLoader)
      try {
        val module = Class.forName("io.eezo.generated.ConfigFile$", true, loader)
        Some(module.getField("MODULE$").get(null).asInstanceOf[Generated].root)
      } catch { case _: ClassNotFoundException => None }
    }
  }

  /** A root with no file to check against: what a suite or a project without the plugin writes. */
  inline def root[A](using inline c: Config[A]): Root[A] =
    ${ internal.ConfigMacro.root[A]('c, '{ None }) }

  /** The root the generated unit writes: the class checked against the file at `path`. */
  inline def checked[A](inline path: String)(using inline c: Config[A]): Root[A] =
    ${ internal.ConfigMacro.root[A]('c, '{ Some(path) }) }

  inline def derived[A](using m: Mirror.ProductOf[A]): Config[A] = make[A](slots[A], m)

  private inline def slots[A]: List[Slot] = ${ internal.ConfigMacro.slots[A] }

  /** What the macro learns of one field. Public types, because the code `derives` expands to is
    * compiled in the application's package.
    */
  sealed trait Slot { def field: String }

  final case class Leaf(
      field: String,
      parser: Parser[Any],
      default: Option[() => Any],
      optional: Boolean,
      redacted: Boolean
  ) extends Slot

  final case class Section(field: String, config: Config[Any]) extends Slot

  private def make[A](slots: List[Slot], m: Mirror.ProductOf[A]): Config[A] = {

    def path(prefix: String, field: String): String =
      if (prefix.isEmpty) field else s"$prefix.$field"

    def leaf(
        slot: Leaf,
        at: String,
        resolved: Option[Tree],
        subs: Map[String, List[Substitution]]
    ): Either[Offender, Any] =
      resolved.filter(_.hasPath(at)).map(_.getValue(at)) match {
        case Some(v) if v.valueType == ConfigValueType.OBJECT =>
          Left(Offender.Malformed(at, "is a section, and a value was expected"))
        case Some(v) if v.valueType == ConfigValueType.LIST =>
          Left(Offender.Malformed(at, "is a list, and one value was expected"))
        case Some(v) =>
          slot.parser.read(v.unwrapped.toString).left.map(Offender.Malformed(at, _))
        case None =>
          slot.default match {
            case Some(value)           => Right(value())
            case None if slot.optional => Right(None)
            case None                  =>
              Left(Offender.Missing(at, subs.getOrElse(at, Nil).map(_.variable)))
          }
      }

    def section(
        slot: Section,
        at: String,
        resolved: Option[Tree],
        subs: Map[String, List[Substitution]]
    ): Either[List[Offender], Any] =
      resolved.filter(_.hasPath(at)).map(_.getValue(at)) match {
        case Some(v) if v.valueType != ConfigValueType.OBJECT =>
          Left(List(Offender.Malformed(at, "is a value, and a section was expected")))
        // The whole tree goes down, not the subtree: every path is read from the root.
        case Some(_) => slot.config.readAt(resolved, at, subs)
        case None    => slot.config.readAt(None, at, subs)
      }

    new Config[A] {

      private var held: Option[A] = None

      def read(file: Tree, env: Map[String, String]): Either[Refused, A] =
        readAt(Some(resolve(file, env)), "", substitutions(file).groupBy(_.path))
          .left
          .map(Refused(_))

      private[config] def readAt(
          resolved: Option[Tree],
          prefix: String,
          subs: Map[String, List[Substitution]]
      ): Either[List[Offender], A] = {
        val (offenders, values) = slots.partitionMap {
          case s: Leaf    => leaf(s, path(prefix, s.field), resolved, subs).left.map(List(_))
          case s: Section => section(s, path(prefix, s.field), resolved, subs)
        }
        if (offenders.nonEmpty) Left(offenders.flatten)
        else Right(m.fromProduct(Tuple.fromArray(values.toArray)))
      }

      def install(value: A): Unit = {
        held = Some(value)
        val product = value.asInstanceOf[Product]
        slots.zipWithIndex.foreach {
          case (s: Section, i) => s.config.install(product.productElement(i))
          case _               => ()
        }
      }

      def current: A =
        held.getOrElse(
          throw new IllegalStateException(
            "the Config was not read: name its class on the entry object, or wrap the suite's " +
              "call in Config.installed"
          )
        )

      def installed[R](value: A)(body: => R): R = {
        val before = held
        install(value)
        try body
        finally held = before
      }
    }
  }
}
