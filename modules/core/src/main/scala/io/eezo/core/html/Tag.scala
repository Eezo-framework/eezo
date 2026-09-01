package io.eezo.core.html

/** A list child's identity, which `modules/live` needs to tell one row from another across a diff.
  * It reaches [[Html.Element.key]] through its own type rather than through an intercepted
  * attribute name, because interception by string is exactly the convention-not-type problem the
  * field exists to avoid.
  */
final case class Key(value: String)

/** Everything a tag accepts as an argument.
  *
  * A union rather than a `Modifier` trait with a `given Conversion`: under this build's own
  * `-feature -Werror`, a conversion is a hard error at every use site unless the view file opens
  * with `import scala.language.implicitConversions`, and a language import in every view file is
  * the opposite of effortless.
  *
  * Users cannot add their own arm today. That loss is recoverable: widening a union parameter is
  * both source and binary compatible, so `| Modifier` can be added the day somebody needs it.
  */
type Mod =
  Attr | Key | Html | String | Int | Long | Double |
    Iterable[Attr | Key | Html | String | Int | Long | Double]

/** A tag name, applied to build an element. */
final class Tag(val name: String) {

  /** Builds the element.
    *
    * Attributes keep insertion order and a repeated name collapses to its last value. A `Fragment`
    * child is spliced into this element's children, so a constructed `Element` never contains one
    * and a differ addressing nodes by child index never has to skip a node with no DOM counterpart.
    */
  def apply(mods: Mod*): Html = {
    val attrs    = Vector.newBuilder[Attr]
    val children = Vector.newBuilder[Html]
    var key      = Option.empty[String]

    def add(mod: Mod): Unit = mod match {
      case a: Attr   => attrs += a; ()
      case k: Key    => key = Some(k.value)
      case h: Html   => children ++= Tag.spliced(h); ()
      case s: String => children += Html.text(s); ()
      case i: Int    => children += Html.text(i.toString); ()
      case l: Long   => children += Html.text(l.toString); ()
      case d: Double => children += Html.text(d.toString); ()
      // Erasure leaves nothing to check here beyond `Iterable`; the union is what keeps the
      // element type honest at the call site.
      case it: Iterable[Mod @unchecked] => it.foreach(add)
    }

    mods.foreach(add)
    Html.Element(name, Tag.lastWins(attrs.result()), key, children.result())
  }
}

object Tag {

  /** A fragment contributes its children; anything else contributes itself. */
  private def spliced(node: Html): Vector[Html] = node match {
    case Html.Fragment(children) => children
    case other                   => Vector(other)
  }

  /** Collapses repeated attribute names, keeping the last value and its position. There is no
    * special case joining `class`: a merging rule for one attribute is a second mental model.
    */
  private def lastWins(attrs: Vector[Attr]): Vector[Attr] =
    if (attrs.sizeIs < 2) attrs
    else
      attrs.zipWithIndex.collect {
        case (attr, i) if !attrs.view.drop(i + 1).exists(_.name == attr.name) => attr
      }
}

/** An attribute name, waiting for its value.
  *
  * `sealed` rather than `final` so that [[UrlAttrName]] can add the one arm the url bearing names
  * need, and no view file can invent a fourth kind of attribute name from outside this file.
  */
sealed class AttrName(val name: String) {

  def :=(value: String): Attr = Attr(name, Some(AttrValue.Literal(value)))

  def :=(value: Int): Attr = Attr(name, Some(AttrValue.Literal(value.toString)))

  /** A present attribute *is* the truth, so `true` renders the bare name and `false` renders
    * nothing at all. The `Iterable` arm of [[Mod]] absorbs both, which is why a conditional
    * attribute needs no helper of its own.
    */
  def :=(value: Boolean): Iterable[Attr] = if (value) Seq(Attr(name, None)) else Nil
}

/** An attribute name whose value is an address: `href`, `action`, `src`.
  *
  * Separate from [[AttrName]] because a [[Url]] means something only where a browser will follow
  * it. Restricting the arm to these three is what makes `Attrs.cls := Url.Mounted("/posts")` a
  * compile error rather than a class attribute that quietly moves under a mount. The constructor
  * stays `private[html]` so [[Attrs]] is the one list that decides which names carry a `Url`, and
  * the constructors of [[Attr]] and [[AttrValue.Link]] are package private for the same reason:
  * either of them left public would let a call site outside `io.eezo.core.html` pair a name of its
  * own with an [[AttrValue.Link]], minting one outright or lifting one out of an `href` attribute
  * it had just built, and so mint a fourth url bearing name that a mount would then rewrite.
  */
final class UrlAttrName private[html] (name: String) extends AttrName(name) {

  def :=(value: Url): Attr = Attr(name, Some(AttrValue.Link(value)))
}

/** Builds a list child's [[Key]]. */
def key(value: String): Key = Key(value)
