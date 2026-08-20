package io.eezo.db.macros

import scala.quoted.*

class Structure[Q <: Quotes](using val q: Q) {
  import q.reflect.*

  case class FieldInfo(
      name: String,
      tpe: TypeRepr,
      pos: Position,
      default: Option[Term],
      annots: List[Term]
  )

  def of[T: Type]: List[FieldInfo] = {
    val repr = TypeRepr.of[T]
    val sym  = repr.typeSymbol

    if (!sym.flags.is(Flags.Case)) {
      report.errorAndAbort(
        s"${sym.name} is not a case class. `derives Table` needs a case class.",
        Position.ofMacroExpansion
      )
    }

    val ctor       = sym.primaryConstructor
    val paramLists = ctor.paramSymss.filterNot(_.exists(_.isTypeParam))

    if (ctor.paramSymss.exists(_.exists(_.isTypeParam))) {
      report.errorAndAbort(
        s"${sym.name} has type parameters; not supported yet.",
        Position.ofMacroExpansion
      )
    }
    if (paramLists.sizeIs > 1) {
      report.errorAndAbort(
        s"${sym.name} has multiple parameter lists; not supported yet.",
        Position.ofMacroExpansion
      )
    }

    val comp = sym.companionModule

    paramLists.headOption.getOrElse(Nil).zipWithIndex.map { case (p, i) =>
      val default =
        if (p.flags.is(Flags.HasDefault)) {
          comp
            .declaredMethod(s"$$lessinit$$greater$$default$$${i + 1}")
            .headOption
            .map(m => Select(Ref(comp), m))
        } else None

      FieldInfo(
        name = p.name,
        tpe = repr.memberType(p),
        pos = p.pos.getOrElse(Position.ofMacroExpansion),
        default = default,
        annots = p.annotations
      )
    }
  }
}
