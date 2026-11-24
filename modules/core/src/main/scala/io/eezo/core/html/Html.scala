package io.eezo.core.html

/** eezo's HTML node tree.
  *
  * Four cases and no more. There is no `Comment` case, because nothing eezo renders emits one and
  * [[Html.raw]] covers it if something ever does, and no `Doctype` case, because [[Html.doctype]]
  * is a `Raw` the caller puts first. A case that may legally appear at exactly one position in the
  * tree is a case every `match` in `modules/live` has to handle and then ignore.
  */
enum Html {

  /** Text that is **already escaped**. The constructor is package private, so [[Html.text]] is the
    * only way to build one from outside `io.eezo.core.html`, while the pattern match that the
    * differ in `modules/live` needs still compiles from anywhere.
    */
  case Text private[html] (escaped: String)

  /** The single unescaped path into the tree.
    *
    * Mounting stops at its layer. `Route.under` moves a [[Url.Mounted]] wherever one sits in an
    * attribute, and raw content is a string eezo never parses, so a link written inside it keeps
    * whatever it says and stays where the author put it.
    */
  case Raw(html: String)

  /** An element. Void ness is a property of the tag *name*, looked up in an internal void tag table
    * at render time, so `Element("br", …, void = false)` is not a state that exists.
    *
    * `key` is a field rather than an ordinary attribute because `modules/live` addresses list
    * children by identity, and a requirement one module places on another belongs in a type rather
    * than in a string two modules agree on by convention. It renders as `data-eezo-key`.
    */
  case Element(
      name: String,
      attrs: Vector[Attr],
      key: Option[String],
      children: Vector[Html]
  )

  /** Several nodes with no element of their own.
    *
    * A `Fragment` renders its children and nothing of its own, so one tree child can become several
    * DOM nodes, and every index after it would run ahead of the tree if the fragment were left in
    * place. For example, `div(span("a"), text("x") ++ text("y"), span("z"))` renders
    * `<div><span>a</span>xy<span>z</span></div>`, which a browser parses as three DOM children;
    * left unspliced, the two text nodes would sit behind one tree child. [[Tag.apply]] is the
    * guard: it splices a `Fragment` child into its parent's children, then merges adjacent `Text`
    * children and drops empty ones, because the parser knows no boundary between `x` and `y`
    * either, so an `Element` built through a tag holds exactly three children here and tree child
    * index equals DOM child index. That guard is `Tag.apply`'s alone; building an `Element` by hand
    * with a `Fragment` among its children, or nesting one `Fragment` inside another before it
    * reaches a tag, sits outside it. The case survives as a root level value, where there is no
    * parent to index into.
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

  /** Renders `body` when `cond` holds, and nothing otherwise. There is no `unless`: `when(!cond)`
    * is the same length and one concept instead of two.
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

/** An attribute. The value is optional rather than a plain [[AttrValue]] so that `value := ""` and
  * `disabled` stay distinct: `None` renders the bare name, `Some(Literal(""))` renders `value=""`.
  * Collapsing the two would make an empty text input indistinguishable from a boolean flag.
  *
  * The constructor is package private, the same shape as [[Html.Text]], so `:=` on an [[AttrName]]
  * is the only way to build one from outside `io.eezo.core.html`, while the pattern match that the
  * differ in `modules/live` needs still compiles from anywhere. Pairing a name of a call site's own
  * choosing with an [[AttrValue.Link]] is what would otherwise reopen the set of url bearing names
  * that [[Attrs]] closes.
  */
final case class Attr private[html] (name: String, value: Option[AttrValue]) {

  private[eezo] def under(prefix: String): Attr = value match {
    case Some(AttrValue.Link(url)) => copy(value = Some(AttrValue.Link(url.under(prefix))))
    case _                         => this
  }
}

/** What an attribute carries.
  *
  * Two cases because a mount has to tell them apart: text is finished, and a [[Url]] is a value
  * `Route.under` can still move. Only the url bearing names build a [[Link]], so the arm exists
  * exactly where prefixing an address is meaningful.
  */
enum AttrValue {

  case Literal(value: String)

  /** An address a mount may still move. The constructor is package private, so `:=` on a
    * [[UrlAttrName]] is the only way to build one from outside `io.eezo.core.html`, while the
    * pattern match [[Attr.under]] and the differ in `modules/live` need still compiles from
    * anywhere.
    */
  case Link private[html] (url: Url)

  private[eezo] def text: String = this match {
    case Literal(value) => value
    case Link(url)      => url.path
  }
}
