package io.eezo.core.html

/** The identity of a list child, which the live layer uses to tell one row from another across a
  * diff. Build one with [[key]].
  */
final case class Key(value: String)

/** Everything a tag accepts as an argument: an attribute, a key, a node, text, a number, or an
  * `Iterable` of those.
  */
type Mod =
  Attr | Key | Html | String | Int | Long | Double |
    Iterable[Attr | Key | Html | String | Int | Long | Double]

/** A tag name, applied to build an element. */
final class Tag(val name: String) {

  /** Builds the element.
    *
    * Attributes keep insertion order. A repeated name keeps its last value, at the position of its
    * last occurrence, and `class` is no exception. A `Fragment` child is spliced into the children
    * of this element, adjacent text is merged and empty text is dropped, so the element never
    * contains a `Fragment`.
    */
  def apply(mods: Mod*): Html = {
    val attrs    = Vector.newBuilder[Attr]
    val children = Vector.newBuilder[Html]
    var key      = Option.empty[String]

    def add(mod: Mod): Unit = mod match {
      case a: Attr                      => attrs += a; ()
      case k: Key                       => key = Some(k.value)
      case h: Html                      => children ++= Tag.spliced(h); ()
      case s: String                    => children += Html.text(s); ()
      case i: Int                       => children += Html.text(i.toString); ()
      case l: Long                      => children += Html.text(l.toString); ()
      case d: Double                    => children += Html.text(d.toString); ()
      case it: Iterable[Mod @unchecked] => it.foreach(add)
    }

    mods.foreach(add)
    Html.Element(name, Tag.lastWins(attrs.result()), key, Tag.mergedText(children.result()))
  }
}

object Tag {

  private def spliced(node: Html): Vector[Html] = node match {
    case Html.Fragment(children) => children
    case other                   => Vector(other)
  }

  private def mergedText(children: Vector[Html]): Vector[Html] =
    children.foldLeft(Vector.empty[Html]) {
      case (acc, Html.Text(text)) if text.isEmpty => acc
      case (acc :+ Html.Text(a), Html.Text(b))    => acc :+ Html.Text(a + b)
      case (acc, child)                           => acc :+ child
    }

  private def lastWins(attrs: Vector[Attr]): Vector[Attr] =
    if (attrs.sizeIs < 2) attrs
    else
      attrs.zipWithIndex.collect {
        case (attr, i) if !attrs.view.drop(i + 1).exists(_.name == attr.name) => attr
      }
}

/** An attribute name, waiting for its value. */
sealed class AttrName(val name: String) {

  def :=(value: String): Attr = Attr(name, Some(AttrValue.Literal(value)))

  def :=(value: Int): Attr = Attr(name, Some(AttrValue.Literal(value.toString)))

  /** `true` renders the bare name and `false` renders nothing, so a conditional attribute needs no
    * helper.
    */
  def :=(value: Boolean): Iterable[Attr] = if (value) Seq(Attr(name, None)) else Nil
}

/** An attribute name whose value is an address: `href`, `action`, `src`.
  *
  * Only these names take a [[Url]], so `Attrs.cls := Url.Mounted("/posts")` does not compile.
  * [[Attrs]] is the one list of them.
  */
final class UrlAttrName private[html] (name: String) extends AttrName(name) {

  def :=(value: Url): Attr = Attr(name, Some(AttrValue.Link(value)))
}

/** Builds a list child's [[Key]]. */
def key(value: String): Key = Key(value)
