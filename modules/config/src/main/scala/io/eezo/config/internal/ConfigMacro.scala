package io.eezo.config.internal

import java.io.File

import scala.quoted.*

import com.typesafe.config.{ConfigFactory, ConfigObject, ConfigParseOptions, ConfigRenderOptions}

import io.eezo.config.Config
import io.eezo.core.{Parser, Redacted}

/** SPIKE (issue 393): the two macros of `Config`.
  *
  * `slots` is `derives Config`: what a `Mirror` cannot see of a case class, the defaults and the
  * field positions, plus the new case of a field that is a section. `root` is the entry object's
  * one value: it walks the whole tree of sections from the root class and, when the build hands it
  * the path of `application.conf` as a macro setting, checks the file against that tree.
  */
object ConfigMacro {

  /** What one field is, decided once for both macros. */
  private class Shape[Q <: Quotes & Singleton](using val q: Q) {
    import q.reflect.*

    sealed trait Kind
    final case class Leaf(
        parser: Expr[Parser[?]],
        default: Option[Expr[Any]],
        optional: Boolean,
        redacted: Boolean
    ) extends Kind
    final case class Section(config: Expr[Config[?]], tpe: TypeRepr) extends Kind

    /** `kind` is `None` once an error was reported on the field. */
    final case class Field(name: String, pos: Position, kind: Option[Kind])

    def fields(repr: TypeRepr, here: Position): List[Field] = {
      val sym = repr.typeSymbol

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

      def local(s: Symbol): Boolean =
        !s.isNoSymbol && !s.isPackageDef && (s.isTerm && !s.flags.is(Flags.Module) || local(s.owner))
      if (params.exists(_.flags.is(Flags.HasDefault)) && local(sym.owner))
        report.errorAndAbort(
          s"${sym.name} is defined inside a method and has a default, which a Config cannot read " +
            "there. Move it to an object or the top level.",
          here
        )
      val companion = sym.companionModule

      params.zipWithIndex.map { (p, i) =>
        val name = p.name
        val at   = p.pos.getOrElse(here)
        val tpe  = repr.memberType(p)
        tpe.asType match {
          case '[t] =>
            val parser   = Expr.summon[Parser[t]]
            val section  = Expr.summon[Config[t]]
            val optional = tpe.dealias.typeSymbol == TypeRepr.of[Option[Any]].typeSymbol
            val shown    = Type.of[t] match {
              case '[Option[x]] if optional => Type.show[x]
              case _                        => Type.show[t]
            }
            val default: Option[Expr[Any]] =
              Option.when(p.flags.is(Flags.HasDefault)) {
                val getter = companion.declaredMethod(s"$$lessinit$$greater$$default$$${i + 1}").head
                Select(Ref(companion), getter).asExprOf[t]
              }
            val redacted = tpe =:= TypeRepr.of[Redacted] || tpe =:= TypeRepr.of[Option[Redacted]]
            val kind     = (parser, section) match {
              case (Some(parser), _) => Some(Leaf(parser, default, optional, redacted))
              case (None, Some(_)) if default.isDefined =>
                report.error(
                  s"Field `$name` is a section, and a section has no default: its fields have theirs.",
                  at
                )
                None
              case (None, Some(config)) => Some(Section(config, tpe))
              case (None, None)         =>
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
    Expr.ofList(fields.map { f =>
      val name = Expr(f.name)
      f.kind.get match {
        case shape.Leaf(parser, default, optional, redacted) =>
          val d: Expr[Option[() => Any]] = default match {
            case Some(v) => '{ Some(() => $v) }
            case None    => '{ None }
          }
          '{
            Config.Leaf(
              $name,
              $parser.asInstanceOf[Parser[Any]],
              $d,
              ${ Expr(optional) },
              ${ Expr(redacted) }
            )
          }
        case shape.Section(config, _) =>
          '{ Config.Section($name, $config.asInstanceOf[Config[Any]]) }
      }
    })
  }

  private val Reference = """\$\{(\??)([^}]+)\}""".r
  private val Concise   = ConfigRenderOptions.concise()

  def root[A: Type](c: Expr[Config[A]])(using q: Quotes): Expr[Config.Root[A]] = {
    import q.reflect.*
    val here  = Position.ofMacroExpansion
    val shape = new Shape[q.type]

    // The path reaches the macro through `io.eezo.generated.ConfigFile`, an object the sbt plugin
    // writes beside `Routes`, because `CompilationInfo.XmacroSettings`, the channel a
    // `-Xmacro-settings` flag would use, is `@experimental` in Scala 3.8.4 and would make every
    // entry object experimental with it. A project without the plugin has no such object, and
    // the check is silent.
    // `requiredModule` answers a stub for a path nothing defines, so the object counts as present
    // only when both constants are there to read.
    val generated: Option[Symbol] =
      try {
        Some(Symbol.requiredModule("io.eezo.generated.ConfigFile")).filter { m =>
          !m.isNoSymbol && !m.fieldMember("path").isNoSymbol && !m.fieldMember("digest").isNoSymbol
        }
      } catch { case _: Exception => None }

    def constant(name: String): Option[String] =
      generated.map(_.fieldMember(name)).flatMap { field =>
        field.termRef.widenTermRefByName match {
          case ConstantType(StringConstant(value)) => Some(value)
          case _                                   => None
        }
      }

    val path = constant("path")

    // The expansion names the generated object's digest, so that the class depends on it: when the
    // plugin rewrites the object for an edited file, the incremental compiler recompiles the class
    // and the check runs again. Without the reference, an edit to the file alone recompiles nothing.
    val digest: Expr[String] =
      generated.map(m => Ref(m).select(m.fieldMember("digest")).asExprOf[String]).getOrElse(Expr(""))

    // The file, when the build named one. Parsed and never resolved: what is checked is what the
    // author wrote, the literal or the reference, not what the compiler's environment holds.
    val file: Option[ConfigObject] = path.map { p =>
      ConfigFactory.parseFile(new File(p), ConfigParseOptions.defaults().setAllowMissing(true)).root()
    }

    var failed = false
    var seen   = Map.empty[TypeRepr, String]

    def variable(path: String): String =
      path.replace('.', '_').replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT)

    def walk(tpe: TypeRepr, prefix: String): Unit =
      shape.fields(tpe, here).foreach { f =>
        val at = if (prefix.isEmpty) f.name else s"$prefix.${f.name}"
        f.kind match {
          case None => failed = true
          case Some(shape.Leaf(_, default, optional, redacted)) =>
            file.foreach { root =>
              val value = Config.at(root, at.split('.').toList)
              if (value.isEmpty && default.isEmpty && !optional) {
                failed = true
                report.error(
                  s"application.conf neither states `$at` nor feeds it from a variable, and " +
                    s"`${f.name}` has no default. Write `${f.name} = $${?${variable(at)}}`" +
                    (if (prefix.isEmpty) "" else s" under `$prefix`") +
                    " in the file, or give the field a default.",
                  f.pos
                )
              }
              value.foreach { v =>
                val literal =
                  Config.resolved(v) || Reference.replaceAllIn(v.render(Concise), "").trim.nonEmpty
                if (redacted && literal) {
                  failed = true
                  report.error(
                    s"application.conf states `$at` as a literal at line ${v.origin().lineNumber()}, " +
                      s"and `${f.name}` is a Redacted. A secret reaches the file only through a " +
                      s"variable: `${f.name} = $${?${variable(at)}}`.",
                    f.pos
                  )
                }
              }
            }
          case Some(shape.Section(_, sectionType)) =>
            seen.get(sectionType) match {
              case Some(first) =>
                failed = true
                report.error(
                  s"`${sectionType.typeSymbol.name}` is the section at `$first` and again at `$at`. " +
                    "A section is installed and read by its type, so each type appears once in the " +
                    s"tree. Give `${f.name}` a case class of its own.",
                  f.pos
                )
              case None =>
                seen = seen.updated(sectionType, at)
                file.foreach { root =>
                  Config.at(root, at.split('.').toList).foreach {
                    case _: ConfigObject => ()
                    case v               =>
                      failed = true
                      report.error(
                        s"application.conf states `$at` as a value at line ${v.origin().lineNumber()}, " +
                          s"and `${f.name}` is a section: write `$at { ... }`.",
                        f.pos
                      )
                  }
                }
                walk(sectionType, at)
            }
        }
      }

    walk(TypeRepr.of[A], "")
    if (failed)
      report.errorAndAbort(
        s"${TypeRepr.of[A].typeSymbol.name} cannot be read from application.conf, for the reasons above.",
        here
      )
    '{ new Config.Root[A]($c, "application.conf", $digest) }
  }
}
