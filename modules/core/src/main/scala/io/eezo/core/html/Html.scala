package io.eezo.core.html

/** eezo's HTML node tree, in four cases.
  *
  * [[Html.doctype]] is a `Raw` that a page puts first, and [[Html.raw]] carries anything the four
  * cases do not, such as a comment.
  */
enum Html {

  /** Text that is **already escaped**. Build one with [[Html.text]]: the constructor is private to
    * `io.eezo.core.html`, and the pattern match works from anywhere.
    */
  case Text private[html] (escaped: String)

  /** The single unescaped path into the tree.
    *
    * A mount does not look inside it. `Route.under` moves a [[Url.Mounted]] that sits in an
    * attribute, and a link written inside raw content stays as its author wrote it.
    */
  case Raw(html: String)

  /** An element. Whether it is void is decided by the tag name when it renders, so
    * `Element("br", …)` never renders children.
    *
    * `key` renders as `data-eezo-key`. Give it with [[key]] among the arguments of a tag.
    */
  case Element(
      name: String,
      attrs: Vector[Attr],
      key: Option[String],
      children: Vector[Html]
  )

  /** Several nodes with no element of their own.
    *
    * A tag splices a `Fragment` child into its own children, then merges adjacent `Text` children
    * and drops empty ones. So `div(span("a"), text("x") ++ text("y"), span("z"))` holds three
    * children, as the browser parses `<div><span>a</span>xy<span>z</span></div>`. An `Element`
    * built by hand, or a `Fragment` inside a `Fragment`, does not get this guard. Use a `Fragment`
    * for several nodes with no parent element, such as a view that answers two siblings or the
    * result of `++`: it renders its children and nothing of its own.
    */
  case Fragment(children: Vector[Html])

  /** Concatenation, flattening adjacent fragments rather than nesting them. */
  def ++(that: Html): Html = (this, that) match {
    case (Fragment(a), Fragment(b)) => Fragment(a ++ b)
    case (Fragment(a), b)           => Fragment(a :+ b)
    case (a, Fragment(b))           => Fragment(a +: b)
    case (a, b)                     => Fragment(Vector(a, b))
  }

  private[eezo] def under(prefix: String): Html = transform {
    case node @ Element(name, attrs, key, children) =>
      val rewritten = attrs.map(_.under(prefix))
      if (Html.same(rewritten, attrs)) node else Element(name, rewritten, key, children)
    case leaf => leaf
  }

  private[eezo] def transform(f: Html => Html): Html = f(this) match {
    case node @ Element(name, attrs, key, children) =>
      val walked = children.map(_.transform(f))
      if ((node eq this) && Html.same(walked, children)) this
      else Element(name, attrs, key, walked)
    case node @ Fragment(children) =>
      val walked = children.map(_.transform(f))
      if ((node eq this) && Html.same(walked, children)) this else Fragment(walked)
    case leaf => leaf
  }

  /** The rendered markup, as a `String`. The HTTP boundary encodes it as UTF-8. */
  def render: String = {
    val sb = new StringBuilder
    renderTo(sb)
    sb.result()
  }

  private[eezo] def renderTo(sb: StringBuilder): Unit = {
    def put(s: String): Unit = { val _ = sb.append(s) }

    this match {
      case Text(escaped) => put(escaped)
      case Raw(html)     => put(html)

      case Element(name, attrs, key, children) =>
        put("<")
        put(name)
        attrs.foreach { attr =>
          put(" ")
          put(attr.name)
          attr.value.foreach { v =>
            put("=\"")
            put(Html.escape(v.text))
            put("\"")
          }
        }
        key.foreach { k =>
          put(" data-eezo-key=\"")
          put(Html.escape(k))
          put("\"")
        }
        put(">")
        if (!Html.VoidTags.contains(name)) {
          children.foreach(_.renderTo(sb))
          put("</")
          put(name)
          put(">")
        }

      case Fragment(children) => children.foreach(_.renderTo(sb))
    }
  }
}

object Html {

  /** The only door from plain text into the tree. */
  def text(value: String): Html = Text(escape(value))

  /** Markup that is inserted verbatim. The caller owns its safety. */
  def raw(html: String): Html = Raw(html)

  /** Nothing. Renders to the empty string and disappears into any parent. */
  val empty: Html = Fragment(Vector.empty)

  /** The document type declaration, which a page puts first. */
  val doctype: Html = Raw("<!DOCTYPE html>")

  /** Renders `body` when `cond` holds, and nothing otherwise. There is no `unless`: write
    * `when(!cond)`.
    */
  def when(cond: Boolean)(body: => Html): Html = if (cond) body else empty

  private[eezo] val VoidTags: Set[String] =
    Set(
      "area",
      "base",
      "br",
      "col",
      "embed",
      "hr",
      "img",
      "input",
      "link",
      "meta",
      "param",
      "source",
      "track",
      "wbr"
    )

  private def same[A <: AnyRef](mapped: Vector[A], original: Vector[A]): Boolean =
    mapped.corresponds(original)(_ eq _)

  private[eezo] def unescape(escaped: String): String =
    if (!escaped.contains('&')) escaped
    else
      escaped
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

  private[html] def escape(value: String): String = {
    val sb = new StringBuilder(value.length)
    value.foreach {
      case '<'  => sb.append("&lt;")
      case '>'  => sb.append("&gt;")
      case '&'  => sb.append("&amp;")
      case '"'  => sb.append("&quot;")
      case '\'' => sb.append("&#39;")
      case c    => sb.append(c)
    }
    sb.result()
  }
}

/** An attribute. `None` renders the bare name and `Some(Literal(""))` renders `value=""`, so an
  * empty text input and a boolean flag stay distinct.
  *
  * Build one with `:=` on an [[AttrName]]: the constructor is private to `io.eezo.core.html`, and
  * the pattern match works from anywhere.
  */
final case class Attr private[html] (name: String, value: Option[AttrValue]) {

  private[eezo] def under(prefix: String): Attr = value match {
    case Some(AttrValue.Link(url)) => copy(value = Some(AttrValue.Link(url.under(prefix))))
    case _                         => this
  }
}

/** What an attribute carries: finished text, or a [[Url]] that a mount can still move. Only the url
  * bearing names of [[Attrs]] build a [[Link]].
  */
enum AttrValue {

  case Literal(value: String)

  /** An address a mount may still move. Build one with `:=` on a [[UrlAttrName]]: the constructor
    * is private to `io.eezo.core.html`, and the pattern match works from anywhere.
    */
  case Link private[html] (url: Url)

  private[eezo] def text: String = this match {
    case Literal(value) => value
    case Link(url)      => url.path
  }
}
