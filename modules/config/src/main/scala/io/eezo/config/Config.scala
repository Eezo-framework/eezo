package io.eezo.config

import java.util.concurrent.atomic.AtomicReference

import scala.compiletime.constValue
import scala.deriving.Mirror
import scala.jdk.CollectionConverters.*

import com.typesafe.config.{
  Config as Tree,
  ConfigException,
  ConfigObject,
  ConfigResolveOptions,
  ConfigResolver,
  ConfigValue,
  ConfigValueFactory,
  ConfigValueType
}

import io.eezo.core.Parser

/** An application's own configuration, read from `application.conf` into a case class it declares.
  *
  * The case class and the file are the whole contract. The class says what is read and what may be
  * absent, through its defaults and its `Option`s; the file says where each value comes from,
  * either stated in it or fed from a variable through a substitution. A nested case class that
  * derives Config is a section, read under the field's name, so the class's shape is the file's.
  *
  * Absence is decided by the case class alone and never by `Parser.absent`. A form needs that
  * answer, because a browser omits an unchecked checkbox, but a value nobody stated is a deployment
  * that forgot something, so a Boolean nobody set is missing rather than quietly false.
  */
trait Config[A] {

  /** A pure function of the file as parsed, unresolved, and a map that stands for the environment.
    * The default argument is the one place the process's environment is read, so a suite hands both
    * and never touches the real one.
    */
  def read(file: Tree, env: Map[String, String] = sys.env): Either[Config.Refused, A]

  /** Every variable the file names, in file order, without reading any of them: what a command can
    * list for an operator before a deployment has a single one set. The file and not the class,
    * because only the file says which variable feeds which path.
    */
  def names(file: Tree): Seq[Config.Name]

  /** What boot calls with the value it read. Every section is installed under its own Config too,
    * so that code that needs one section asks for that section and not for the whole class.
    */
  def install(value: A): Unit

  /** What a handler reads, as it reads a table: the value installed last. */
  def current: A

  /** A suite's scope: `value`, and every section of it, is current inside `body`, and what was
    * installed before is back after, also when `body` throws.
    */
  def installed[R](value: A)(body: => R): R

  private[config] def slots: List[Config.Slot]

  /** Reads this class from `tree`, the resolved object at `prefix`. `None` is a section the file
    * never states, whose fields are then read as absent.
    */
  private[config] def readAt(
      tree: Option[ConfigObject],
      prefix: List[String],
      file: Config.Written
  ): Either[List[Config.Offender], A]

  /** Puts `value` as current here and in every section, and gives back what restores the values
    * that were there before.
    */
  private[config] def put(value: Option[A]): () => Unit
}

object Config {

  def apply[A](using config: Config[A]): Config[A] = config

  /** What an operator has to know about one variable before setting it. */
  enum Need {
    case Required, Defaulted, Optional
  }

  /** One variable, beside the path it feeds. `redacted` is decided from the field's type, so that
    * whatever lists these never shows a Redacted value, nor asks for one to be typed in the clear.
    */
  final case class Name(variable: String, path: String, redacted: Boolean, need: Need)

  /** One reason the case class was not built, named by its path. It holds the path, the variable
    * and the Parser's reason and never the text found, so that printing one, or a [[Refused]],
    * cannot leak what the file or the environment holds.
    */
  enum Offender {

    /** `variables` are the ones the file names for the path, empty when the file names none. */
    case Missing(path: String, variables: List[String])
    case Malformed(path: String, reason: String)

    /** The file feeds the path from a variable under the framework's prefix. */
    case Reserved(path: String, variable: String)

    /** The file states a Redacted path as text, which puts a Redacted value in a committed file. */
    case Literal(path: String)
  }

  /** Every offender at once, a variable the framework reserves first and then the fields' in
    * declaration order, because a deployment fixed one value per restart is a deployment fixed
    * slowly.
    *
    * A value rather than an exception: following ADR 0006, the `Either` is answered where it is
    * returned, as a Form's is, and the boot that reads a Config decides how a refusal stops it.
    */
  final case class Refused(offenders: Seq[Offender]) {

    /** One line per offender, the path first, for an operator reading a boot log. */
    def message: String =
      offenders
        .map {
          case Offender.Missing(path, Nil)       => s"$path is missing: add it to application.conf"
          case Offender.Missing(path, variables) =>
            s"$path is missing: set ${variables.mkString(" or ")}, which application.conf names for it"
          case Offender.Malformed(path, reason)  => s"$path $reason"
          case Offender.Reserved(path, variable) =>
            s"$path is fed from $variable, and $FrameworkPrefix is the framework's prefix for its " +
              "own variables: name another variable in application.conf"
          case Offender.Literal(path) =>
            s"$path is stated as a literal in application.conf, and it is a Redacted: a Redacted " +
              "value reaches the file only through a variable"
        }
        .mkString("\n")
  }

  /** The framework reads its own variables under this prefix, and a file that feeds a path from one
    * would read the framework's value or hand it the application's.
    */
  private[config] val FrameworkPrefix = "EEZO_"

  /** `inline` only long enough for the macro to read the fields, then straight into `make`, which
    * is compiled once, so no anonymous class is copied to every `derives` site.
    */
  inline def derived[A](using m: Mirror.ProductOf[A]): Config[A] =
    make[A](constValue[m.MirroredLabel], slots[A], m)

  private inline def slots[A]: List[Slot] = ${ internal.ConfigMacro.slots[A] }

  /** What the macro learns of one field. Public types, because the code `derives Config` expands to
    * is compiled in the application's package, where nothing private to this one is visible.
    */
  sealed trait Slot {
    def field: String
  }

  /** A field read from one value through its Parser. */
  final case class Leaf(
      field: String,
      parser: Parser[Any],
      default: Option[() => Any],
      optional: Boolean,
      redacted: Boolean
  ) extends Slot {

    def need: Need =
      if (default.isDefined) Need.Defaulted else if (optional) Need.Optional else Need.Required
  }

  /** A field whose type derives Config itself, read from the object under the field's name. */
  final case class Section(field: String, config: Config[Any]) extends Slot

  /** One substitution of the file, by the path it feeds. */
  private[config] final case class Substitution(path: String, variable: String)

  /** The file as the author wrote it, before resolution: what a substitution is, and whether a path
    * is stated as text, can only be asked of this tree, since resolution replaces both with values.
    */
  private[config] final class Written(file: Tree) {

    val root: ConfigObject = file.root()

    val substitutions: List[Substitution] = Config.substitutions(file)

    private val byPath: Map[String, List[String]] =
      substitutions.groupMap(_.path)(_.variable)

    /** The variables an operator may set for `path`. One under the framework's prefix is left out,
      * since setting it is the mistake its own offender reports.
      */
    def variables(path: String): List[String] =
      byPath.getOrElse(path, Nil).filterNot(_.startsWith(FrameworkPrefix))
  }

  /** The value at `path` in an unresolved object, literal or substitution, or `None` where the file
    * neither states nor feeds it. Walked by hand, because `hasPath` resolves on the way. Where the
    * path passes through a value only resolution can open, a section that inherits a block through
    * `${base}`, that value is the answer: the file may state the path there, and only `None` may
    * mean that it does not.
    */
  private[config] def at(obj: ConfigObject, path: List[String]): Option[ConfigValue] =
    reach(obj, path).map(_._1)

  /** The value the author wrote as text at `path` itself. One on the way is no answer: the text of
    * `keys = ${base} { region = "us" }` belongs to `keys.region`, not to `keys.secret`.
    */
  private[config] def literal(obj: ConfigObject, path: List[String]): Option[ConfigValue] =
    reach(obj, path).collect { case (v, true) if stated(v) => v }

  /** `at`, with whether the value is the one at the path itself. */
  private def reach(obj: ConfigObject, path: List[String]): Option[(ConfigValue, Boolean)] =
    path match {
      case Nil         => Some(obj -> true)
      case key :: rest =>
        unresolvable(Option(obj.get(key))) match {
          case None                          => Some(obj -> false)
          case Some(Some(o: ConfigObject))   => reach(o, rest)
          case Some(Some(v)) if rest.isEmpty => Some(v -> true)
          case Some(Some(v)) if !resolved(v) => Some(v -> false)
          case _                             => None
        }
    }

  /** Whether the library knows what `v` is before resolution: a substitution, or a merge or a
    * concatenation that holds one, is known only after.
    */
  private[config] def resolved(v: ConfigValue): Boolean =
    unresolvable(v.valueType()).isDefined

  /** `None` when the library cannot answer without resolving first. */
  private def unresolvable[X](answer: => X): Option[X] =
    try Some(answer)
    catch { case _: ConfigException.NotResolved => None }

  /** Whether the value at a path is text the author wrote, alone or merged with a substitution.
    * `null` is no text, and an object or a list is not one value, which the read reports itself.
    */
  private[config] def stated(v: ConfigValue): Boolean =
    unresolvable(v.valueType()) match {
      case Some(ConfigValueType.NULL | ConfigValueType.OBJECT | ConfigValueType.LIST) => false
      case Some(_)                                                                    => true
      case None => Rendered.scan(v.render(Rendered.Options)).literal
    }

  /** Every substitution of the unresolved tree that names a variable, by the path it feeds, in file
    * order. A substitution of a path the file states itself is a reference inside the file, which
    * resolution answers from the file, so it is not a variable and is left out.
    *
    * A reference still carries the variables of what it copies to the path it is written at, as in
    * `stripe = ${base}`, where only resolution sees that a variable of `base` feeds `stripe` too.
    * So the paths a variable lands on under no path where it is written follow its first entry:
    * they are what the operator sets the variable for, and the field read there decides its Name.
    */
  private def substitutions(file: Tree): List[Substitution] = {
    val root                      = file.root()
    val landed                    = scala.collection.mutable.Map.empty[String, List[List[String]]]
    def landing(variable: String) = landed.getOrElseUpdate(variable, landings(file, variable))
    def walk(obj: ConfigObject, prefix: List[String]): List[(List[String], Substitution)] =
      obj.keySet.asScala.toList
        .map(key => key -> obj.get(key))
        .sortBy((key, v) => (v.origin().lineNumber(), key))
        .flatMap { (key, v) =>
          val path = prefix :+ key
          v match {
            case o: ConfigObject if unresolvable(o.keySet).isDefined => walk(o, path)
            case _                                                   =>
              Rendered
                .scan(v.render(Rendered.Options))
                .references
                .filter(reference => at(root, Rendered.segments(reference)).isEmpty)
                .flatMap { variable =>
                  val under = landing(variable).filter(_.startsWith(path))
                  (if (under.isEmpty) List(path) else under)
                    .map(p => path -> Substitution(dotted(p), variable))
                }
                .distinct
          }
        }
    val written = walk(root, Nil)
    val where   = written.groupMap(_._2.variable)(_._1)
    val copied  = where.map { (variable, paths) =>
      variable -> landing(variable)
        .filterNot(l => paths.exists(l.startsWith(_)))
        .map(l => Substitution(dotted(l), variable))
    }
    val listed = scala.collection.mutable.Set.empty[String]
    written.flatMap { (_, s) =>
      if (listed.add(s.variable)) s :: copied(s.variable) else List(s)
    }
  }

  /** What a variable's value is, in the tree resolved by [[landings]]: text that no file and no
    * environment holds, so that every path it reaches is a path the variable feeds.
    */
  private val Landing = "\u0000eezo substitution\u0000"

  /** Every path `variable` lands on once resolved, with only it answered. A value merged with a
    * substitution, `stripe { ... }` and then `stripe = ${?STRIPE}`, renders as one piece, so the
    * walk cannot tell from it which leaf a substitution inside it feeds; resolution can. Only one
    * variable is answered at a time, so that one feeding the section as a whole lands on the
    * section and does not hide the leaves under it from the others.
    */
  private def landings(file: Tree, variable: String): List[List[String]] = {
    def under(v: ConfigValue, path: List[String]): List[List[String]] =
      v match {
        case o: ConfigObject =>
          unresolvable(o.keySet.asScala.toList.sorted).toList.flatten
            .flatMap(key => under(o.get(key), path :+ key))
        case _ =>
          unresolvable(v.valueType()) match {
            case Some(ConfigValueType.STRING | ConfigValueType.LIST)
                if v.unwrapped().toString.contains(Landing) =>
              List(path)
            case _ => Nil
          }
      }
    try under(resolve(file, Map(variable -> Landing)).root(), Nil)
    catch { case _: ConfigException => Nil }
  }

  private[config] def dotted(path: List[String]): String = path.mkString(".")

  /** The tree resolved against `env` alone, through the library's resolver hook: a substitution is
    * answered from the file first and then, with the process's environment switched off, from the
    * map. The hook and not `withFallback`, so the resolved tree holds the file's paths and nothing
    * of the map; and not `resolveWith`, which throws on a value merged with a substitution.
    * Unresolved substitutions are allowed, so that every required one left unset is a Missing
    * offender beside the others instead of one exception that hides them.
    */
  private[config] def resolve(file: Tree, env: Map[String, String]): Tree =
    file.resolve(
      ConfigResolveOptions
        .defaults()
        .setUseSystemEnvironment(false)
        .setAllowUnresolved(true)
        .appendResolver(Variables(env))
    )

  /** The origin of every value the map answers, so that a resolved Redacted leaf of any other
    * origin is known to be text of the file, also where the file copies it in through a reference
    * or under a section merged with a substitution, which the unresolved walk cannot see into.
    */
  private val VariableOrigin = "eezo variable"

  private final case class Variables(env: Map[String, String], next: Option[ConfigResolver] = None)
      extends ConfigResolver {

    def lookup(path: String): ConfigValue =
      env
        .get(path)
        .map(ConfigValueFactory.fromAnyRef(_, VariableOrigin))
        .orElse(next.map(_.lookup(path)))
        .orNull

    def withFallback(fallback: ConfigResolver): ConfigResolver =
      copy(next = Some(next.fold(fallback)(_.withFallback(fallback))))
  }

  /** What the resolved tree holds at one key. */
  private enum Found {
    case Value(v: ConfigValue)
    case Unresolved
    case Absent
  }

  private def find(tree: Option[ConfigObject], key: String): Found =
    tree.fold(Found.Absent) { obj =>
      unresolvable(Option(obj.get(key)).map(v => v -> v.valueType())) match {
        case None                                  => Found.Unresolved
        case Some(None)                            => Found.Absent
        case Some(Some((_, ConfigValueType.NULL))) => Found.Absent
        case Some(Some((v, _)))                    => Found.Value(v)
      }
    }

  private def make[A](label: String, slots: List[Slot], m: Mirror.ProductOf[A]): Config[A] = {
    val outer = slots

    def leaf(
        slot: Leaf,
        tree: Option[ConfigObject],
        path: List[String],
        file: Written
    ): Either[Offender, Any] = {
      val name = dotted(path)
      if (slot.redacted && literal(file.root, path).isDefined) Left(Offender.Literal(name))
      else
        find(tree, slot.field) match {
          case Found.Value(v) =>
            v.valueType() match {
              case ConfigValueType.OBJECT =>
                Left(Offender.Malformed(name, "is an object, and one value was expected"))
              case ConfigValueType.LIST =>
                Left(Offender.Malformed(name, "is a list, and one value was expected"))
              case _ if slot.redacted && v.origin().description() != VariableOrigin =>
                Left(Offender.Literal(name))
              case _ =>
                slot.parser.read(v.unwrapped().toString).left.map(Offender.Malformed(name, _))
            }
          case Found.Unresolved => Left(Offender.Missing(name, file.variables(name)))
          case Found.Absent     =>
            slot.default match {
              case Some(value)           => Right(value())
              case None if slot.optional => Right(None)
              case None                  => Left(Offender.Missing(name, file.variables(name)))
            }
        }
    }

    def section(
        slot: Section,
        tree: Option[ConfigObject],
        path: List[String],
        file: Written
    ): Either[List[Offender], Any] = {
      val name = dotted(path)
      find(tree, slot.field) match {
        case Found.Value(o: ConfigObject) => slot.config.readAt(Some(o), path, file)
        case Found.Value(_)               =>
          Left(List(Offender.Malformed(name, "is a value, and a section was expected")))
        case Found.Unresolved => Left(List(Offender.Missing(name, file.variables(name))))
        case Found.Absent     => slot.config.readAt(None, path, file)
      }
    }

    new Config[A] {

      private val held = new AtomicReference[Option[A]](None)

      private[config] def slots: List[Slot] = outer

      def read(file: Tree, env: Map[String, String]): Either[Refused, A] = {
        val written  = Written(file)
        val reserved = written.substitutions.collect {
          case s if s.variable.startsWith(FrameworkPrefix) => Offender.Reserved(s.path, s.variable)
        }
        readAt(Some(resolve(file, env).root()), Nil, written) match {
          case Right(value) if reserved.isEmpty => Right(value)
          case Right(_)                         => Left(Refused(reserved))
          case Left(offenders)                  => Left(Refused(reserved ++ offenders))
        }
      }

      private[config] def readAt(
          tree: Option[ConfigObject],
          prefix: List[String],
          file: Written
      ): Either[List[Offender], A] = {
        val (offenders, values) = outer.partitionMap {
          case s: Leaf    => leaf(s, tree, prefix :+ s.field, file).left.map(List(_))
          case s: Section => section(s, tree, prefix :+ s.field, file)
        }
        if (offenders.nonEmpty) Left(offenders.flatten)
        else Right(m.fromProduct(Tuple.fromArray(values.toArray)))
      }

      def names(file: Tree): Seq[Name] = {
        val fields = leaves(this, Nil).toMap
        Written(file).substitutions.map { s =>
          fields.get(s.path) match {
            case Some(slot) => Name(s.variable, s.path, slot.redacted, slot.need)
            case None       => Name(s.variable, s.path, redacted = false, Need.Optional)
          }
        }
      }

      def install(value: A): Unit = {
        val _ = put(Some(value))
      }

      def current: A =
        held
          .get()
          .getOrElse(
            throw new IllegalStateException(
              s"Config[$label].current was asked for before a $label was installed. Boot " +
                s"installs the one it reads from application.conf; a suite wraps its call in " +
                s"Config[$label].installed(value) { ... }."
            )
          )

      def installed[R](value: A)(body: => R): R = {
        val restore = put(Some(value))
        try body
        finally restore()
      }

      private[config] def put(value: Option[A]): () => Unit = {
        val before   = held.getAndSet(value)
        val restores = outer.zipWithIndex.collect { case (s: Section, i) =>
          s.config.put(value.map(_.asInstanceOf[Product].productElement(i)))
        }
        () => {
          restores.reverse.foreach(_())
          held.set(before)
        }
      }
    }
  }

  /** Every leaf of `config`'s tree by its full path, for `names`. */
  private def leaves(config: Config[?], prefix: List[String]): List[(String, Leaf)] =
    config.slots.flatMap {
      case s: Leaf    => List(dotted(prefix :+ s.field) -> s)
      case s: Section => leaves(s.config, prefix :+ s.field)
    }

  /** The class the application reads, checked against the file at compile time where the build
    * writes the root into the generated unit.
    */
  final class Root[A](val config: Config[A])

  object Root {

    /** What the generated unit's object is: the build names the class, and the plugin writes
      * `object ConfigFile extends Config.Root.Generated` beside `Routes`.
      */
    trait Generated {
      def root: Root[?]
    }
  }

  /** The root with no file to check against: what a suite or a project without the plugin writes.
    * It still walks the whole tree of sections, so a section type at two paths is refused here too.
    */
  inline def root[A](using c: Config[A]): Root[A] = ${ internal.ConfigMacro.root[A]('c) }

  /** The root the generated unit writes: the class checked at compile time against the file at
    * `path`, so a required value the file never mentions fails the build and not the boot.
    */
  inline def checked[A](inline path: String)(using c: Config[A]): Root[A] =
    ${ internal.ConfigMacro.checked[A]('c, 'path) }
}
