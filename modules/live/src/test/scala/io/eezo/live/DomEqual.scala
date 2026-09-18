package io.eezo.live

import io.eezo.core.html.Html

/** Equality as a browser would judge it, for the suites' assertions.
  *
  * Structural `==` on [[Html]] is stricter than the DOM in exactly two ways, and the differ
  * deliberately follows the DOM (see `Differ`'s comment): attribute order carries no meaning,
  * because `setAttribute` cannot reorder what exists, and a bare attribute is indistinguishable
  * from an empty-valued one. A suite comparing renders as strings would fail on both while no
  * browser can see either, so the round trip is asserted with this instead. The jsdom harness
  * applies the same judgement from the other side, over parsed attributes.
  */
object DomEqual {

  def apply(a: Html, b: Html): Boolean = (a, b) match {
    case (Html.Text(x), Html.Text(y))       => x == y
    case (Html.Raw(x), Html.Raw(y))         => x == y
    case (x: Html.Element, y: Html.Element) =>
      x.name == y.name && attrsOf(x) == attrsOf(y) && all(x.children, y.children)
    case (Html.Fragment(x), Html.Fragment(y)) => all(x, y)
    case _                                    => false
  }

  def all(a: Vector[Html], b: Vector[Html]): Boolean =
    a.length == b.length && a.lazyZip(b).forall(apply)

  private def attrsOf(element: Html.Element): Map[String, String] =
    element.attrs.map(attr => attr.name -> attr.value.map(_.text).getOrElse("")).toMap ++
      element.key.map("data-eezo-key" -> _)
}
