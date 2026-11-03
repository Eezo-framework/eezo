package io.eezo.config.internal

import scala.quoted.*

import com.typesafe.config.{
  ConfigException,
  ConfigFactory,
  ConfigObject,
  ConfigParseOptions,
  ConfigValueType
}

import io.eezo.config.Config
import io.eezo.core.{Parser, Redacted}
import io.eezo.core.internal.util

/** The two macros of `Config`.
  *
  * `slots` is `derives Config`: what a `Mirror` cannot see of a case class, the defaults and where
  * each field is written, plus whether a field is a value or a section. `root` and `checked` are
  * the application's one root: they walk the whole tree of sections from the root class, and
  * `checked` holds the file the build names against that tree.
  */
object ConfigMacro {

  /** What one field is, decided once for both macros. */
  private class Shape[Q <: Quotes & Singleton](using val q: Q) {
    import q.reflect.*

    sealed trait Kind

    /** `parser` is `None` only on the root's walk, which runs where the root is written and not
      * where the class derived Config, so a Parser the class's own scope imports is out of its
      * reach. The derivation already proved that Parser exists.
      */
    final case class Leaf(
        parser: Option[Expr[Parser[?]]],
        default: Option[Expr[Any]],
        optional: Boolean,
        redacted: Boolean
    ) extends Kind
    final case class Section(config: Expr[Config[?]], tpe: TypeRepr) extends Kind

    /** `kind` is `None` once an error was reported on the field. */
    final case class Field(name: String, pos: Position, kind: Option[Kind])

    def fields(repr: TypeRepr, here: Position, derived: Boolean = false): List[Field] = {
      val sym = repr.typeSymbol

      // An object's class carries a trailing `$` that the author never wrote.
      if (!sym.flags.is(Flags.Case) || sym.flags.is(Flags.Module))
        report.errorAndAbort(
          s"${sym.name.stripSuffix("$")} is not a case class, and only one can derive Config.",
          here
        )
      val ctor = sym.primaryConstructor
      if (ctor.paramSymss.exists(_.exists(_.isTypeParam)))
        report.errorAndAbort(
          s"${sym.name} has type parameters, and a Config is read from one fixed file.",
          here
        )
      val params = ctor.paramSymss.headOption.getOrElse(Nil)

      // The default getters are found by their compiled name on the companion, which is only a
      // stable member when the class is not local to a method. Checked once a field is known to
      // be a value, since a section's default is refused for a reason of its own.
      def local(s: Symbol): Boolean = {
        val method = s.isTerm && !s.flags.is(Flags.Module)
        !s.isNoSymbol && !s.isPackageDef && (method || local(s.owner))
      }
      val unreachable = local(sym.owner)
      val companion   = sym.companionModule

      params.zipWithIndex.map { (p, i) =>
        val name = p.name
        val at   = p.pos.getOrElse(here)
        val tpe  = repr.memberType(p)
        tpe.asType match {
          case '[t] =>
            val parser   = Expr.summon[Parser[t]]
            val section  = Expr.summon[Config[t]]
            val optional = tpe.dealias.typeSymbol == TypeRepr.of[Option[Any]].typeSymbol
            // `Parser.option` lifts any inner Parser, so an Option field lacks the inner one. The
            // guard keeps a Some or None field, which the invariant Parser.option never serves,
            // asking for its own Parser.
            val (shown, inner) = Type.of[t] match {
              case '[Option[x]] if optional => (Type.show[x], Expr.summon[Config[x]].isDefined)
              case _                        => (Type.show[t], false)
            }
            val defaulted                       = p.flags.is(Flags.HasDefault)
            lazy val default: Option[Expr[Any]] =
              Option.when(defaulted) {
                val getter =
                  companion.declaredMethod(s"$$lessinit$$greater$$default$$${i + 1}").head
                Select(Ref(companion), getter).asExprOf[t]
              }
            val redacted = tpe =:= TypeRepr.of[Redacted] || tpe =:= TypeRepr.of[Option[Redacted]]
            val kind     = (parser, section) match {
              // A class that derived Config had every field checked there; a Parser missing here
              // is only out of scope, so the field is a section exactly when it derives Config.
              case (None, Some(config)) if derived => Some(Section(config, tpe))
              // A case class here is ambiguous: a section whose Config given sits outside the
              // companion looks exactly like a value whose Parser does, and a section mistaken
              // for a value escapes the twice check and the file checks under it.
              case (None, None) if derived && tpe.dealias.typeSymbol.flags.is(Flags.Case) =>
                val short = tpe.dealias.typeSymbol.name
                report.errorAndAbort(
                  s"`$short` at field `$name` is a case class, and the root cannot tell a section " +
                    s"from a value: neither Config[$short] nor Parser[$short] is in scope here. " +
                    s"Put the given in the companion of `$short`, or write `derives Config` on it.",
                  here
                )
              case (None, None) if derived => Some(Leaf(None, default, optional, redacted))
              case (Some(_), _) if defaulted && unreachable =>
                report.errorAndAbort(
                  s"${sym.name} is defined inside a method and has a default, which a Config " +
                    "cannot read there. Move it to an object or the top level.",
                  here
                )
              case (Some(parser), _) => Some(Leaf(Some(parser), default, optional, redacted))
              case (None, Some(_)) if defaulted =>
                report.error(
                  s"Field `$name` is a section, and a section has no default: its fields have theirs.",
                  at
                )
                None
              case (None, Some(config))  => Some(Section(config, tpe))
              case (None, None) if inner =>
                report.error(
                  s"Field `$name` is an Option of a section, and a section is never absent: its " +
                    "fields have their own defaults and Options.",
                  at
                )
                None
              case (None, None) =>
                report.error(
                  s"""No Parser for $shown at field `$name`, and $shown does not derive Config either.
                     |A field is a value with a Parser in scope:
                     |  given Parser[$shown] = text => ... // Right(value), or Left("is not a ...")
                     |or a section, a case class that derives Config.
                     |""".stripMargin,
                  at
                )
                None
            }
            Field(name, at, kind)
        }
      }
    }
  }

  def slots[A: Type](using q: Quotes): Expr[List[Config.Slot]] = {
    import q.reflect.*
    val shape  = new Shape[q.type]
    val here   = Position.ofMacroExpansion
    val fields = shape.fields(TypeRepr.of[A], here)
    if (fields.exists(_.kind.isEmpty))
      report.errorAndAbort(
        s"${TypeRepr.of[A].typeSymbol.name} cannot derive Config, for the reasons above.",
        here
      )
    fields.foreach {
      case shape.Field(name, at, Some(shape.Leaf(None, _, _, _))) =>
        report.errorAndAbort(s"Field `$name` has no Parser to read it with.", at)
      case _ => ()
    }
    Expr.ofList(fields.collect {
      case shape.Field(name, _, Some(shape.Leaf(Some(parser), default, optional, redacted))) =>
        val d: Expr[Option[() => Any]] = default match {
          case Some(v) => '{ Some(() => $v) }
          case None    => '{ None }
        }
        '{
          Config.Leaf(
            ${ Expr(name) },
            $parser.asInstanceOf[Parser[Any]],
            $d,
            ${ Expr(optional) },
            ${ Expr(redacted) }
          )
        }
      case shape.Field(name, _, Some(shape.Section(config, _))) =>
        '{ Config.Section(${ Expr(name) }, $config.asInstanceOf[Config[Any]]) }
    })
  }

  def root[A: Type](c: Expr[Config[A]])(using Quotes): Expr[Config.Root[A]] =
    walk[A](c, None)

  /** The path reaches the macro as an argument because the plugin writes the call itself, into a
    * unit whose text carries the file's digest, so the incremental compiler recompiles it whenever
    * the file changes. No channel through the compiler's flags is needed, and none is experimental.
    */
  def checked[A: Type](c: Expr[Config[A]], path: Expr[String])(using Quotes): Expr[Config.Root[A]] =
    walk[A](c, Some(path.valueOrAbort))

  private def walk[A: Type](c: Expr[Config[A]], path: Option[String])(using
      q: Quotes
  ): Expr[Config.Root[A]] = {
    import q.reflect.*
    val here  = Position.ofMacroExpansion
    val shape = new Shape[q.type]

    // Parsed and never resolved: what is checked is what the author wrote, the literal or the
    // substitution, and not what the compiler's environment holds.
    val parsed: Option[ConfigObject] = path.map { p =>
      try
        ConfigFactory
          .parseFile(new java.io.File(p), ConfigParseOptions.defaults().setAllowMissing(true))
          .root()
      catch {
        case e: ConfigException => report.errorAndAbort(Config.unparsable(e), here)
      }
    }

    // The class edit alone. Summoning a product mirror makes the compiler record, in the unit that
    // summons it, a dependency on the class and a use of its constructor's name, so that a field
    // added to or removed from any class of the tree recompiles the unit and checks it again. The
    // mirror itself is thrown away.
    def tracked(tpe: TypeRepr): Unit =
      tpe.asType match {
        case '[t] => Expr.summon[scala.deriving.Mirror.ProductOf[t]].foreach(_ => ())
      }

    def variable(at: List[String]): String =
      at.map(util.snake).mkString("_").toUpperCase(java.util.Locale.ROOT)

    var failed = false
    var seen   = Map.empty[String, String]

    // `file` is `None` below a section the file states as a value, which is refused once at the
    // section: the walk goes on for the mirrors and the twice check, and holds nothing against
    // the fields of a section the file never wrote.
    def visit(tpe: TypeRepr, prefix: List[String], file: Option[ConfigObject]): Unit = {
      tracked(tpe)
      shape.fields(tpe, here, derived = true).foreach { f =>
        val segments = prefix :+ f.name
        val at       = Config.dotted(segments)
        val under    = if (prefix.isEmpty) "" else s" under `${Config.dotted(prefix)}`"
        f.kind match {
          case None                                             => failed = true
          case Some(shape.Leaf(_, default, optional, redacted)) =>
            file.foreach { root =>
              // `read` counts a null as absent, so the check does too. The guard comes first,
              // because the type of a substitution is known only after resolution.
              val value = Config
                .at(root, segments)
                .filterNot(v => Config.resolved(v) && v.valueType() == ConfigValueType.NULL)
              if (value.isEmpty && default.isEmpty && !optional) {
                failed = true
                report.error(
                  s"application.conf neither states `$at` nor feeds it from a variable, and " +
                    s"`${f.name}` has no default. Write `${f.name} = $${?${variable(segments)}}`" +
                    s"$under in the file, or give the field a default.",
                  f.pos
                )
              }
              Config.literal(root, segments).filter(_ => redacted).foreach { v =>
                failed = true
                report.error(
                  s"application.conf states `$at` as a literal at line ${v.origin().lineNumber()}, " +
                    s"and `${f.name}` is a Redacted. A secret reaches the file only through a " +
                    s"variable: `${f.name} = $${?${variable(segments)}}`.",
                  f.pos
                )
              }
            }
          case Some(shape.Section(_, sectionType)) =>
            val key = sectionType.typeSymbol.fullName
            seen.get(key) match {
              case Some(first) =>
                failed = true
                report.error(
                  s"`${sectionType.typeSymbol.name}` is the section at `$first` and again at `$at`. " +
                    "A section is installed and read by its type, so each type appears once in the " +
                    s"tree. Give `${f.name}` a case class of its own.",
                  f.pos
                )
              case None =>
                seen = seen.updated(key, at)
                // A substitution at the section path may resolve to a block, so only a value the
                // library knows before resolution is refused here; the read refuses the rest. A
                // null is no value, as `read` counts it as an absent section, so the fields
                // below decide whether the absent section is complete.
                val asValue =
                  file
                    .flatMap(Config.at(_, segments))
                    .filter(v =>
                      Config.resolved(v) && v.valueType() != ConfigValueType.NULL &&
                        !v.isInstanceOf[ConfigObject]
                    )
                asValue.foreach { v =>
                  failed = true
                  report.error(
                    s"application.conf states `$at` as a value at line ${v.origin().lineNumber()}, " +
                      s"and `${f.name}` is a section: write `$at { ... }`.",
                    f.pos
                  )
                }
                visit(sectionType, segments, file.filter(_ => asValue.isEmpty))
            }
        }
      }
    }

    visit(TypeRepr.of[A], Nil, parsed)
    if (failed)
      report.errorAndAbort(
        s"${TypeRepr.of[A].typeSymbol.name} cannot be read from application.conf, for the reasons above.",
        here
      )
    '{ new Config.Root[A]($c) }
  }
}
