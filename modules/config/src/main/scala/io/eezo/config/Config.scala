package io.eezo.config

import java.util.concurrent.atomic.AtomicReference

import scala.compiletime.constValue
import scala.deriving.Mirror
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

import com.typesafe.config.{
  Config as Tree,
  ConfigException,
  ConfigObject,
  ConfigResolveOptions,
  ConfigResolver,
  ConfigUtil,
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

  /** The offenders the file shows on its own, before any environment: a variable under the
    * framework's prefix, and a Redacted stated as text. What a command checks for an operator who
    * has set nothing yet, so a value no variable feeds yet is not one of them. The file is read
    * twice. With every variable answered by text no environment holds, each path is fed and the
    * read finds the two offenders at the leaves. With none answered, a variable that replaces a
    * whole section stays unset, so the section stays a section and its literal leaf is still
    * reached, as at boot with that variable unset. An unset variable is only a Missing there, and
    * the filter drops it.
    */
  def check(file: Tree): Seq[Config.Offender] = {
    val answered = Config.Written(file).substitutions.map(_.variable -> Config.Answered).toMap
    (read(file, answered).left.toSeq ++ read(file, Map.empty).left.toSeq)
      .flatMap(_.offenders)
      .filter {
        case _: Config.Offender.Reserved | _: Config.Offender.Literal => true
        case _                                                        => false
      }
      .distinct
  }

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
    def message: String = lines.mkString("\n")

    /** [[message]] line by line, for a caller that prefixes each, as boot's `[eezo] ` does. */
    def lines: Seq[String] =
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
  }

  /** What [[Config.check]] answers every variable with: no value an operator sets, so nothing a
    * refusal could quote.
    */
  private[config] val Answered = "\u0000eezo check\u0000"

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

    /** The variables, among those the file names for `path`, whose value reaches the path under
      * `env`: every set part of a concatenation, and of two fed in turn only the later one set,
      * since it wins. Each set one is answered with the placeholder in turn, and feeds the path
      * when the placeholder lands there; the file alone says which variable a shape of the file
      * reads, so a reader of the report never has to know the shape.
      */
    def feeding(path: String, env: Map[String, String]): List[String] =
      variables(path).filter(env.contains).filter { variable =>
        try {
          val tree = resolve(file, env.updated(variable, Landing))
          tree.hasPath(path) && tree.getValue(path).unwrapped().toString.contains(Landing)
        } catch { case _: ConfigException => false }
      }
  }

  /** The value at `path` in an unresolved object, literal or substitution, or `None` where the file
    * neither states nor feeds it. Walked by hand, because `hasPath` resolves on the way. Where the
    * path passes through a value only resolution can open, a section that inherits a block through
    * `${base}`, the file may state the path there, so resolution decides: against the file alone,
    * with every variable answered, the path is there or it is not. Only when resolution fails is
    * the value on the way the answer.
    */
  private[config] def at(obj: ConfigObject, path: List[String]): Option[ConfigValue] =
    reach(obj, path).flatMap {
      case (v, Nil)                      => Some(v)
      case (v, _) if supplies(obj, path) => Some(v)
      case _                             => None
    }

  /** The value the author wrote as text at `path` itself, or, where the path holds a reference
    * inside the file or passes through one, the value that reference copies it from. One on the way
    * is no text of the path: the text of `keys = ${base} { region = "us" }` belongs to
    * `keys.region`, not to `keys.secret`, while the text of `base.secret` is the text of
    * `keys.secret`. A reference that loops back is followed once.
    */
  private[config] def literal(obj: ConfigObject, path: List[String]): Option[ConfigValue] = {
    def copies(v: ConfigValue, rest: List[String]): List[List[String]] =
      if (rest.isEmpty && unresolvable(v.valueType()).exists(Container.contains)) Nil
      else {
        val scan = Rendered.scan(v.render(Rendered.Options))
        // A reference inside a list or an object feeds an element or a key of its own, so `r ++ rest`
        // never names where the path takes its text from: only a reference beside the value can.
        scan.copies
          .map(Rendered.segments)
          .filter(r => r.nonEmpty && at(obj, r).isDefined)
      }
    def go(path: List[String], seen: Set[List[String]]): Option[ConfigValue] =
      if (seen(path)) None
      else
        reach(obj, path).flatMap { (v, rest) =>
          Option.when(rest.isEmpty && stated(v))(v).orElse {
            copies(v, rest).iterator.flatMap(r => go(r ++ rest, seen + path)).nextOption()
          }
        }
    go(path, Set.empty)
  }

  private val Container = Set(ConfigValueType.OBJECT, ConfigValueType.LIST)

  /** `at`, with what of the path is left once the value is reached: nothing where it is the value
    * at the path itself.
    */
  private def reach(obj: ConfigObject, path: List[String]): Option[(ConfigValue, List[String])] =
    path match {
      case Nil         => Some(obj -> Nil)
      case key :: rest =>
        unresolvable(Option(obj.get(key))) match {
          case None                          => Some(obj -> path)
          case Some(Some(o: ConfigObject))   => reach(o, rest)
          case Some(Some(v)) if rest.isEmpty => Some(v -> Nil)
          case Some(Some(v)) if !resolved(v) => Some(v -> rest)
          case _                             => None
        }
    }

  /** Whether the file, resolved alone, holds `path`. Every variable is answered, so that a path an
    * optional substitution may feed counts as fed, and a reference inside the file is followed.
    * Where resolution fails the file may still state the path, so it counts as held.
    */
  private def supplies(obj: ConfigObject, path: List[String]): Boolean = {
    def holds(o: ConfigObject, path: List[String]): Boolean =
      path match {
        case Nil         => true
        case key :: rest =>
          Option(o.get(key)) match {
            case Some(next: ConfigObject) => holds(next, rest)
            case Some(_)                  => rest.isEmpty
            case None                     => false
          }
      }
    try
      holds(
        obj.toConfig
          .resolve(
            ConfigResolveOptions
              .defaults()
              .setUseSystemEnvironment(false)
              .setAllowUnresolved(true)
              .appendResolver(Everything)
          )
          .root(),
        path
      )
    catch { case _: ConfigException => true }
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

  /** Answers every variable with the placeholder, so that resolving the file alone drops no
    * optional substitution and the paths it feeds stay in the tree.
    */
  private val Everything: ConfigResolver = new ConfigResolver {
    def lookup(path: String): ConfigValue = ConfigValueFactory.fromAnyRef(Landing)
    def withFallback(fallback: ConfigResolver): ConfigResolver = this
  }

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

  /** The path in HOCON's own quoting, so that a key that holds a dot and the nested path it looks
    * like are different paths, in a message and in every map keyed by one.
    */
  private[config] def dotted(path: List[String]): String = ConfigUtil.joinPath(path.asJava)

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

  /** What a refusal says of a path whose value resolution cannot build. It never holds the
    * library's message, which quotes the variable's value.
    */
  private val Unjoinable =
    "cannot be resolved: it joins a list or an object with a single value, or substitutes itself"

  /** The file as a whole, for a refusal that has no one path to name. */
  private val FileName = "application.conf"

  /** What a refusal says of a file that does not parse, at compile time and at boot alike. The
    * library's own message quotes the tokens around the fault, which can be a secret, so only the
    * line is kept.
    */
  private[config] def unparsable(e: ConfigException): String = {
    val line = Option(e.origin()).map(_.lineNumber()).filter(_ > 0)
    s"$FileName does not parse${line.fold("")(n => s" at line $n")}: fix its syntax there."
  }

  /** The outermost paths of the unresolved tree that hold a substitution, in file order: the only
    * values resolution can fail on.
    */
  private def substituting(obj: ConfigObject, prefix: List[String] = Nil): List[List[String]] =
    obj.keySet.asScala.toList
      .map(key => key -> obj.get(key))
      .sortBy((key, v) => (v.origin().lineNumber(), key))
      .flatMap { (key, v) =>
        val path = prefix :+ key
        v match {
          case o: ConfigObject if unresolvable(o.keySet).isDefined => substituting(o, path)
          case _ if Rendered.scan(v.render(Rendered.Options)).references.nonEmpty => List(path)
          case _                                                                  => Nil
        }
      }

  private def without(file: Tree, paths: List[List[String]]): Tree =
    paths.foldLeft(file)((f, p) => f.withoutPath(ConfigUtil.joinPath(p.asJava)))

  private def resolves(file: => Tree, env: Map[String, String]): Boolean =
    try {
      val _ = resolve(file, env)
      true
    } catch { case _: ConfigException => false }

  /** The value the unresolved tree holds at `path`, reached object by object, since the tree's own
    * accessors throw on a value still to be resolved.
    */
  private def valueAt(root: ConfigObject, path: List[String]): ConfigValue =
    path.tail.foldLeft(root.get(path.head)) { (v, key) => v.asInstanceOf[ConfigObject].get(key) }

  /** Which of `candidates` each one substitutes, through other candidates too: a reference feeds on
    * a candidate when it names that path, a path under it or a path above it.
    */
  private def fed(
      file: Tree,
      candidates: List[List[String]]
  ): Map[List[String], Set[List[String]]] = {
    val direct = candidates.map { p =>
      val references = Rendered
        .scan(valueAt(file.root(), p).render(Rendered.Options))
        .references
        .map(r => ConfigUtil.splitPath(r).asScala.toList)
      p -> candidates.filter(c => references.exists(r => r.startsWith(c) || c.startsWith(r))).toSet
    }.toMap
    @tailrec def close(
        m: Map[List[String], Set[List[String]]]
    ): Map[List[String], Set[List[String]]] = {
      val next = m.map((p, fs) => p -> fs.flatMap(f => m(f) + f))
      if (next == m) m else close(next)
    }
    close(direct)
  }

  /** The paths resolution fails on in `file`, found one round at a time: first those that fail with
    * every other substitution left out; then, when they fail together as a loop does, one whose
    * removal lets the file resolve, and of those the one no other feeds on, so that a value joined
    * wrongly is blamed and not the path it copies. The library's exception is no help, since its
    * origin is the variable's, not the file's.
    */
  private def blame(file: Tree, env: Map[String, String]): List[List[String]] = {
    val candidates = substituting(file.root())
    val alone      =
      candidates.filter(p => !resolves(without(file, candidates.filterNot(_ == p)), env))
    if (alone.nonEmpty) alone
    else {
      val fixing = candidates.filter(p => resolves(without(file, List(p)), env))
      val feeds  = fed(file, candidates)
      fixing
        .find(p => !fixing.exists(q => q != p && feeds(q).contains(p)))
        .orElse(fixing.headOption)
        .toList
    }
  }

  /** Every path resolution fails on, blamed round after round until the file resolves without them,
    * or until no one path is left to blame.
    */
  private def unjoinable(file: Tree, env: Map[String, String]): List[List[String]] = {
    @tailrec def go(rest: Tree, blamed: List[List[String]]): List[List[String]] =
      if (blamed.nonEmpty && resolves(rest, env)) blamed
      else
        blame(rest, env) match {
          case Nil  => blamed
          case more => go(without(rest, more), blamed ++ more)
        }
    go(file, Nil)
  }

  /** The resolved tree, or, where resolution fails, the offenders for what it fails on and the tree
    * resolved without them, so that the fields beside them are still read.
    */
  private def settle(file: Tree, env: Map[String, String]): (List[Offender], Option[ConfigObject]) =
    try (Nil, Some(resolve(file, env).root()))
    catch {
      case _: ConfigException =>
        val bad       = unjoinable(file, env)
        val offenders = bad.map(p => Offender.Malformed(dotted(p), Unjoinable))
        try (offenders, Some(resolve(without(file, bad), env).root()))
        catch {
          case _: ConfigException =>
            (if (bad.isEmpty) List(Offender.Malformed(FileName, Unjoinable)) else offenders, None)
        }
    }

  private def pathOf(offender: Offender): String =
    offender match {
      case Offender.Missing(path, _)   => path
      case Offender.Malformed(path, _) => path
      case Offender.Reserved(path, _)  => path
      case Offender.Literal(path)      => path
    }

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
        val (unjoined, tree) = settle(file, env)
        val read             =
          tree.fold[Either[List[Offender], A]](Left(Nil))(t => readAt(Some(t), Nil, written))
        // A path that failed to resolve is refused once, not again as missing from the tree.
        val rest = read.left.getOrElse(Nil).filterNot { o =>
          unjoined.exists(u => pathOf(o) == pathOf(u) || pathOf(o).startsWith(pathOf(u) + "."))
        }
        read match {
          case Right(value) if reserved.isEmpty && unjoined.isEmpty => Right(value)
          case _ => Left(Refused(reserved ++ unjoined ++ rest))
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
                "installs the one it reads from application.conf when the build names the class " +
                s"with eezoConfigClass; a suite wraps its call in Config[$label].installed(value) " +
                "{ ... }."
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
  final class Root[A](val config: Config[A]) {

    /** What boot does with the file: the read, and on success the install. Here, where the class is
      * known, since a caller that holds only a `Root[?]` cannot hand the value it read back to
      * `install`.
      */
    private[config] def boot(file: Tree, env: Map[String, String]): Option[Refused] =
      config.read(file, env) match {
        case Right(value) =>
          config.install(value)
          None
        case Left(refused) => Some(refused)
      }
  }

  object Root {

    /** What the generated unit's object is: the build names the class, and the plugin writes
      * `object ConfigFile extends Config.Root.Generated` beside `Routes`.
      */
    trait Generated {
      def root: Root[?]
    }

    /** The name the plugin writes the object under, fixed so that boot finds it without the entry
      * object naming it.
      */
    private val GeneratedName = "io.eezo.generated.ConfigFile"

    /** The object's class, else a class of the same name with no object, which is someone else's
      * class and must be refused like an object of the wrong type: read as absent, it would boot
      * with nothing installed.
      */
    private def generatedClass: Option[Class[?]] =
      try Some(loader.loadClass(GeneratedName + "$"))
      catch {
        case _: ClassNotFoundException =>
          try Some(loader.loadClass(GeneratedName))
          catch { case _: ClassNotFoundException => None }
      }

    /** The root the build names, or `None` when it names none. Looked up by name and not linked,
      * since the object exists only where the build sets `eezoConfigClass`. Only a class nobody
      * wrote is `None`: one that fails to load or to initialise is the build gone wrong, and taking
      * it for absent would boot with nothing installed and fail later, on the first `current`.
      */
    def generated: Option[Root[?]] =
      generatedClass.map { cls =>
        val instance =
          try cls.getField("MODULE$").get(null)
          catch { case _: NoSuchFieldException => null }
        instance match {
          case g: Generated => g.root
          case _            =>
            throw new IllegalStateException(
              s"$GeneratedName is not a Config.Root.Generated, so the plugin did not write it: " +
                "move the class that has its name, which only the plugin writes"
            )
        }
      }
  }

  /** The loader boot finds the generated object and `application.conf` through. The context loader
    * first, as `Assets` has it: sbt runs an application and a suite under a loader of its own, with
    * eezo's jars in a parent layer that cannot see the application's classes or resources.
    */
  private[config] def loader: ClassLoader =
    Option(Thread.currentThread.getContextClassLoader).getOrElse(getClass.getClassLoader)

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
