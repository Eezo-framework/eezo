package io.eezo.core.internal

import scala.quoted.*

import io.eezo.core.{Config, Parser, Redacted}

/** The half of `derives Config` that a `Mirror` cannot do: it sees neither a field's default nor
  * where the field is written, and both a default and an error that names its field need them.
  *
  * The technique is the one `db`'s `Structure` uses, written again here because `core` cannot see
  * `db`. Everything that reads a value is left to `Config.make`, so the code each `derives` expands
  * to is a list of slots and nothing else.
  */
object ConfigMacro {

  def slots[A: Type](using q: Quotes): Expr[List[Config.Slot]] = {
    import q.reflect.*

    val repr = TypeRepr.of[A]
    val sym  = repr.typeSymbol
    val here = Position.ofMacroExpansion

    // An object's class carries a trailing `$` that the author never wrote.
    if (!sym.flags.is(Flags.Case) || sym.flags.is(Flags.Module))
      report.errorAndAbort(
        s"${sym.name.stripSuffix("$")} is not a case class, and only one can derive Config.",
        here
      )

    val ctor = sym.primaryConstructor
    if (ctor.paramSymss.exists(_.exists(_.isTypeParam)))
      report.errorAndAbort(
        s"${sym.name} has type parameters, and a Config is read from one fixed set of variables.",
        here
      )

    val params = ctor.paramSymss.headOption.getOrElse(Nil)

    // The default getters are found by their compiled name on the companion, which is only a
    // stable member when the class is not local to a method.
    def local(s: Symbol): Boolean =
      !s.isNoSymbol && !s.isPackageDef && (s.isTerm && !s.flags.is(Flags.Module) || local(s.owner))
    if (params.exists(_.flags.is(Flags.HasDefault)) && local(sym.owner))
      report.errorAndAbort(
        s"${sym.name} is defined inside a method and has a default, which a Config cannot read " +
          "there. Move it to an object or the top level.",
        here
      )

    val companion = sym.companionModule

    val found: List[Option[Expr[Config.Slot]]] = params.zipWithIndex.map { (p, i) =>
      val field    = p.name
      val at       = p.pos.getOrElse(here)
      val variable = Config.variable(field)
      val clash    = variable.startsWith(Config.FrameworkPrefix)
      val tpe      = repr.memberType(p)
      tpe.asType match {
        case '[t] =>
          val parser   = Expr.summon[Parser[t]]
          val optional = tpe.dealias.typeSymbol == TypeRepr.of[Option[Any]].typeSymbol
          // `Parser.option` lifts any inner Parser, so an Option field lacks the inner one, and
          // a given for the whole Option would replace the lift that reads blank text as None.
          // The guard keeps a Some or None field, which conforms to Option[x] but which the
          // invariant Parser.option never serves, asking for its own Parser.
          val shown = Type.of[t] match {
            case '[Option[x]] if optional => Type.show[x]
            case _                        => Type.show[t]
          }
          if (clash)
            report.error(
              s"Field `$field` would be read from $variable, and ${Config.FrameworkPrefix} is the " +
                "framework's prefix for its own variables. Rename the field.",
              at
            )
          if (parser.isEmpty)
            report.error(
              s"""No Parser for $shown at field `$field`, so $variable cannot be read.
                 |Provide one in scope:
                 |  given Parser[$shown] = text => ... // Right(value), or Left("is not a ...")
                 |""".stripMargin,
              at
            )
          parser.filterNot(_ => clash).map { parser =>
            val default: Expr[Option[() => Any]] =
              if (!p.flags.is(Flags.HasDefault)) '{ None }
              else {
                val getter =
                  companion.declaredMethod(s"$$lessinit$$greater$$default$$${i + 1}").head
                val value = Select(Ref(companion), getter).asExprOf[t]
                '{ Some(() => $value) }
              }
            val redacted = tpe =:= TypeRepr.of[Redacted] || tpe =:= TypeRepr.of[Option[Redacted]]
            '{
              (
                field = ${ Expr(field) },
                parser = $parser.asInstanceOf[Parser[Any]],
                default = $default,
                optional = ${ Expr(optional) },
                redacted = ${ Expr(redacted) }
              )
            }
          }
      }
    }

    if (found.contains(None))
      report.errorAndAbort(s"${sym.name} cannot derive Config, for the reasons above.", here)

    Expr.ofList(found.flatten)
  }
}
