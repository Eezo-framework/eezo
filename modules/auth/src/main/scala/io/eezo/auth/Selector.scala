package io.eezo.auth

import scala.quoted.*

/** Which field of a model a `_.field` selector picks, read off the selector itself while it is
  * still a tree.
  *
  * It exists so that an ownership declaration names the field once, in the one spelling the
  * compiler checks, instead of twice: a string beside a getter is two things to keep in step, and
  * the copy that drifts is the one `db` renders into a `where` clause, which is a query against a
  * column nobody has rather than a compile error.
  *
  * Anything but a plain field is refused rather than approximated. A selector that computes
  * something has no field name to be read off it, and inventing one from the last `Select` in the
  * tree would produce a name that compiles, looks right, and scopes by the wrong column.
  *
  * The one rule it enforces belongs to `auth` rather than to `core` because `auth` is the only
  * module that turns a selector into an [[io.eezo.core.OwnerOf]]; `core` holds the result and never
  * the way of getting there.
  */
private[auth] object Selector {

  inline def nameOf[A, V](inline selector: A => V): String = ${ nameOfImpl('selector) }

  private def nameOfImpl[A: Type, V: Type](selector: Expr[A => V])(using Quotes): Expr[String] = {
    import quotes.reflect.*

    /** The lambda as written, with whatever inlining and ascription the compiler wrapped it in
      * taken off. Matching the wrappers rather than ignoring them is what keeps a selector spelled
      * `(p: Post) => p.author` and one spelled `_.author` reading the same.
      */
    def bare(term: Term): Term = term match {
      case Inlined(_, _, inner) => bare(inner)
      case Typed(inner, _)      => bare(inner)
      case Block(Nil, inner)    => bare(inner)
      case other                => other
    }

    def refused: Nothing =
      report.errorAndAbort(
        "an ownership selector has to be a field of the model, written `_.author`: the field's " +
          "name is what names the column its rows are scoped by, and an expression has no name " +
          "to be read off it."
      )

    bare(selector.asTerm) match {
      case Block(List(DefDef(_, List(TermParamClause(List(param))), _, Some(body))), _) =>
        bare(body) match {
          case Select(Ident(from), field) if from == param.name => Expr(field)
          case _                                                => refused
        }
      case _ => refused
    }
  }
}
